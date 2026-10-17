package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import kotlin.math.*

/**
 * EXPERIMENTAL: camera Bitmap -> 192px palm SSD -> rotated 224px crop ->
 * 21 hand landmarks. Both neural networks are invoked via LiteRT NPU CompiledModel.
 *
 * IMPORTANT: A successful model invocation is NOT proof of exclusively NPU
 * execution. LiteRT may create CPU partitions. Hardware profiling is mandatory.
 * This class does not change the stable MediaPipe pipeline in main.
 */
class NpuHandPipeline private constructor(
    private val palm: NpuSubmodelProbe,
    private val hand: NpuSubmodelProbe
) : AutoCloseable {
    data class Point(val x: Float, val y: Float, val z: Float)
    private data class Roi(val x: Float, val y: Float, val side: Float, val theta: Float)
    private data class Anchor(val x: Float, val y: Float)
    private val anchors: List<Anchor> = buildList {
        for ((stride, count) in listOf(8 to 2, 16 to 6)) {
            val grid = 192 / stride
            for (y in 0 until grid) for (x in 0 until grid) repeat(count) {
                add(Anchor((x + .5f) / grid, (y + .5f) / grid))
            }
        }
    }
    private val palmBmp = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
    private val handBmp = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
    private val palmCanvas = Canvas(palmBmp)
    private val handCanvas = Canvas(handBmp)
    private val palmPixels = IntArray(192 * 192)
    private val handPixels = IntArray(224 * 224)
    private val palmInput = FloatArray(192 * 192 * 3)
    private val handInput = FloatArray(224 * 224 * 3)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var trackedRoi: Roi? = null
    private var missing = 0

    fun inferenceMetrics(): String =
        "手掌模型：${palm.metrics().shortText()} · 关键点模型：${hand.metrics().shortText()}"

    companion object {
        fun create(context: Context): NpuHandPipeline? {
            val detector = NpuSubmodelProbe.create(context, "palm_detection.tflite") ?: return null
            val landmarks = NpuSubmodelProbe.create(context, "hand_landmark.tflite")
            if (landmarks == null) {
                detector.close()
                return null
            }
            return NpuHandPipeline(detector, landmarks)
        }
    }

    /**
     * Returns null when hand is absent (this is NOT an inference error).
     * Throws for unsupported tensor shapes or failed NPU execution so that the
     * parent can rebuild the pre-existing MediaPipe GPU/CPU recognizer.
     */
    fun process(frame: Bitmap): List<Point>? {
        val roi = trackedRoi ?: locatePalm(frame) ?: return null
        val output = runHand(frame, roi)
        if (output == null) {
            trackedRoi = null
            missing++
            return null
        }
        val p = output
        val wrist = p[0]
        val middle = p[9]
        val angle = (PI.toFloat() / 2f) - atan2(wrist.y - middle.y, middle.x - wrist.x)
        val xMin = p.minOf { it.x }; val xMax = p.maxOf { it.x }
        val yMin = p.minOf { it.y }; val yMax = p.maxOf { it.y }
        val side = (max((xMax - xMin) * frame.width, (yMax - yMin) * frame.height)
            * 2.1f).coerceAtLeast(60f)
        val cx = (xMax + xMin) * frame.width * .5f
        val cy = (yMax + yMin) * frame.height * .5f
        trackedRoi = Roi(cx, cy, side, angle)
        missing = 0
        return p
    }

    private fun locatePalm(frame: Bitmap): Roi? {
        val scale = min(192f / frame.width, 192f / frame.height)
        val ww = frame.width * scale
        val hh = frame.height * scale
        val offX = (192 - ww) * .5f
        val offY = (192 - hh) * .5f
        palmCanvas.drawColor(Color.BLACK)
        palmCanvas.drawBitmap(frame, null, RectF(offX, offY, offX + ww, offY + hh), paint)
        palmBmp.getPixels(palmPixels, 0, 192, 0, 0, 192, 192)
        fillRgb(palmPixels, palmInput)
        val out = palm.runPreprocessedFloat(palmInput)
        val raw = out.firstOrNull { it.size == 2016 * 18 }
            ?: error("Palm regression tensor is not 2016×18")
        val scores = out.firstOrNull { it.size == 2016 }
            ?: error("Palm score tensor is not 2016")
        var best = -1; var bestLogit = 0f
        for (i in scores.indices) {
            if (scores[i] > bestLogit) { bestLogit = scores[i]; best = i }
        }
        if (best < 0 || bestLogit < 0f) return null // sigmoid(logit) < .5
        val p = best * 18
        val a = anchors[best]
        fun imageX(k: Int): Float = ((a.x + raw[p + k] / 192f) * 192f - offX) / scale
        fun imageY(k: Int): Float = ((a.y + raw[p + k] / 192f) * 192f - offY) / scale
        val centerX = imageX(0)
        val centerY = imageY(1)
        val width = abs(raw[p + 2] / 192f * 192f / scale)
        val height = abs(raw[p + 3] / 192f * 192f / scale)
        // MediaPipe palm SSD: keypoint 0 = wrist, keypoint 2 = middle MCP.
        val wristX = imageX(4)
        val wristY = imageY(5)
        val middleX = imageX(8)
        val middleY = imageY(9)
        val theta = PI.toFloat() / 2f - atan2(wristY - middleY, middleX - wristX)
        val side = (max(width, height) * 2.6f).coerceAtLeast(64f)
        // DetectionToRect/RectTransformation center is shifted towards fingers.
        val roiX = (wristX + middleX) * .5f
        val roiY = (wristY + middleY) * .5f
        val cx = if (roiX.isFinite()) roiX else centerX
        val cy = if (roiY.isFinite()) roiY else centerY
        return Roi(cx, cy, side, theta)
    }

    private fun runHand(frame: Bitmap, roi: Roi): List<Point>? {
        val canvas = handCanvas
        canvas.drawColor(Color.BLACK)
        canvas.save()
        canvas.translate(112f, 112f)
        canvas.rotate(-Math.toDegrees(roi.theta.toDouble()).toFloat())
        canvas.scale(224f / roi.side, 224f / roi.side)
        canvas.translate(-roi.x, -roi.y)
        canvas.drawBitmap(frame, 0f, 0f, paint)
        canvas.restore()
        handBmp.getPixels(handPixels, 0, 224, 0, 0, 224, 224)
        fillRgb(handPixels, handInput)
        val out = hand.runPreprocessedFloat(handInput)
        // Official MediaPipe HandLandmarkCpu graph: [landmarks, hand_flag,
        // handedness, world_landmarks]. Keep this order (two tensors have 63).
        if (out.size < 4 || out[0].size != 63 || out[1].size != 1) {
            error("Unexpected landmark tensors: ${out.map { it.size }}")
        }
        if (out[1][0] < .5f) return null
        val coords = out[0]
        val cosA = cos(roi.theta)
        val sinA = sin(roi.theta)
        return (0 until 21).map { i ->
            val localX = (coords[i * 3] / 224f - .5f) * roi.side
            val localY = (coords[i * 3 + 1] / 224f - .5f) * roi.side
            val x = (roi.x + localX * cosA - localY * sinA) / frame.width
            val y = (roi.y + localX * sinA + localY * cosA) / frame.height
            val z = coords[i * 3 + 2] * .4f / 224f * roi.side / frame.width
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
                error("Non-finite NPU hand landmark")
            }
            Point(x, y, z)
        }
    }

    private fun fillRgb(pixels: IntArray, input: FloatArray) {
        var j = 0
        for (pixel in pixels) {
            input[j++] = ((pixel ushr 16) and 255) / 255f
            input[j++] = ((pixel ushr 8) and 255) / 255f
            input[j++] = (pixel and 255) / 255f
        }
    }

    override fun close() {
        try { palm.close() } finally {
            try { hand.close() } finally {
                palmBmp.recycle()
                handBmp.recycle()
            }
        }
    }
}

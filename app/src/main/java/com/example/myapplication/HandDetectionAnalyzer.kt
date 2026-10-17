package com.example.myapplication

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2

/** MediaPipe Gesture Recognizer + extra landmark classifiers.
 * Backwards-compatible callback type for the existing Doom Activity and Service.
 */
class HandDetectionAnalyzer(
    context: Context,
    private val onHandsDetected: (HandGesture?) -> Unit
) : ImageAnalysis.Analyzer {
    data class NormalizedPoint(val x: Float, val y: Float, val z: Float = 0f)
    data class IndexFingerPosition(val x: Float, val y: Float)
    data class HandGesture(
        val isFiveFingersOpen: Boolean,
        val isFiveFingersClosed: Boolean,
        val isFourFingersOpen: Boolean,
        val isPinching: Boolean,
        val pinchDistanceSquared: Float?,
        val pinchRatio: Float = 1f,
        val indexFingerAngleDeg: Float?,
        val indexFingerPosition: IndexFingerPosition?,
        val landmarks: List<NormalizedPoint>,
        val gestureName: String = "None",
        val confidence: Float = 0f
    )

    private val busy = AtomicBoolean(false)
    private var lastTimeMs = 0L
    private var lastFrameAtMs = 0L
    @Volatile private var lastHandAtMs = SystemClock.uptimeMillis()
    private var fpsWindowStart = 0L
    private var frameCount = 0
    private var measuredFps = 0f
    private var lastDiagnosticsAt = 0L
    private var recognizer: GestureRecognizer? = null

    init {
        try {
            val options = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build()
                )
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setMinHandDetectionConfidence(.5f)
                .setMinHandPresenceConfidence(.5f)
                .setMinTrackingConfidence(.5f)
                .setResultListener { result: GestureRecognizerResult, image: MPImage ->
                    try {
                        handle(result)
                    } catch (e: Exception) {
                        Log.e("HandDetection", "Failed to process gesture", e)
                        onHandsDetected(null)
                    } finally {
                        image.close()
                        busy.set(false)
                    }
                }
                .setErrorListener { error ->
                    Log.e("HandDetection", "MediaPipe error", error)
                    busy.set(false)
                }
                .build()
            recognizer = GestureRecognizer.createFromOptions(context, options)
        } catch (e: Exception) {
            Log.e("HandDetection", "Missing or invalid gesture_recognizer.task", e)
        }
    }

    override fun analyze(imageProxy: ImageProxy) {
        // Adaptive inference rate; the user-enabled camera stays on, with
        // hand detection at low standby frequency to allow rapid wake-up.
        val now = SystemClock.uptimeMillis()
        val minInterval = RecognitionCadence.intervalMs(
            GestureRuntime.airMouse, GestureRuntime.powerSaving,
            now - lastHandAtMs
        )
        if (now - lastFrameAtMs < minInterval || recognizer == null ||
            !busy.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        lastFrameAtMs = now
        try {
            // Prefer packed CameraX RGBA data: no JPEG encode/decode.
            val image = BitmapImageBuilder(imageProxy.toOptimizedBitmap()).build()
            val timestamp = maxOf(SystemClock.uptimeMillis(), lastTimeMs + 1L)
            lastTimeMs = timestamp
            recognizer!!.recognizeAsync(image, timestamp)
        } catch (e: Exception) {
            Log.e("HandDetection", "Frame analysis failed", e)
            busy.set(false)
        } finally {
            imageProxy.close()
        }
    }

    private fun handle(result: GestureRecognizerResult) {
        val hand = result.landmarks().firstOrNull()
        if (hand == null || hand.size != 21) {
            val now = SystemClock.uptimeMillis()
            recordFrame(now, "None", 0f)
            onHandsDetected(null)
            return
        }
        // The six released gestures and air-mouse use landmarks exclusively.
        // Do not allocate legacy geometry descriptors or run an optional TFLite
        // classifier on every frame: neither influences any mapped action.
        lastHandAtMs = SystemClock.uptimeMillis()
        val p = hand.map { NormalizedPoint(it.x(), it.y(), it.z()) }
        val top = result.gestures().firstOrNull()?.firstOrNull()
        val topName = GestureCatalog.canonical(top?.categoryName() ?: "None")
        val score = top?.score() ?: 0f
        val name = if (score >= .62f && topName in GestureCatalog.labels)
            topName else "检测到手部"
        recordFrame(SystemClock.uptimeMillis(), name, score)

        val dx = p[4].x - p[8].x
        val dy = p[4].y - p[8].y
        val dist2 = dx * dx + dy * dy
        val palmWidth = kotlin.math.hypot(p[5].x - p[17].x, p[5].y - p[17].y)
            .coerceAtLeast(.035f)
        val pinchRatio = kotlin.math.sqrt(dist2) / palmWidth
        val angle = Math.toDegrees(atan2(
            (p[0].y - p[8].y).toDouble(),
            (p[8].x - p[0].x).toDouble()
        )).toFloat()

        onHandsDetected(HandGesture(
            isFiveFingersOpen = name == "Open_Palm",
            isFiveFingersClosed = name == "Closed_Fist",
            isFourFingersOpen = false,
            isPinching = pinchRatio < .47f || name == "OK",
            pinchDistanceSquared = dist2,
            pinchRatio = pinchRatio,
            indexFingerAngleDeg = angle,
            indexFingerPosition = IndexFingerPosition(p[8].x, p[8].y),
            landmarks = p,
            gestureName = name,
            confidence = score
        ))
    }

    private fun recordFrame(now: Long, name: String, confidence: Float) {
        if (fpsWindowStart == 0L) fpsWindowStart = now
        frameCount++
        if (now - fpsWindowStart >= 1000L) {
            measuredFps = frameCount * 1000f / (now - fpsWindowStart)
            frameCount = 0
            fpsWindowStart = now
        }
        if (now - lastDiagnosticsAt >= 350L) {
            lastDiagnosticsAt = now
            GestureDiagnostics.recognition(measuredFps, name, confidence)
        }
    }

    fun release() {
        recognizer?.close()
        recognizer = null
    }
}
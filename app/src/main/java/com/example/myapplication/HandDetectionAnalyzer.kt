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
    data class NormalizedPoint(val x: Float, val y: Float)
    data class IndexFingerPosition(val x: Float, val y: Float)
    data class HandGesture(
        val isFiveFingersOpen: Boolean,
        val isFiveFingersClosed: Boolean,
        val isFourFingersOpen: Boolean,
        val isPinching: Boolean,
        val pinchDistanceSquared: Float?,
        val indexFingerAngleDeg: Float?,
        val indexFingerPosition: IndexFingerPosition?,
        val landmarks: List<NormalizedPoint>,
        val gestureName: String = "None",
        val confidence: Float = 0f
    )

    private val busy = AtomicBoolean(false)
    private var lastTimeMs = 0L
    private val custom = OptionalCustomGestureModel(context)
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
        if (recognizer == null || !busy.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        try {
            // Doom's original YUV_420_888 to Bitmap converter handles rotation.
            val image = BitmapImageBuilder(imageProxy.toBitmap()).build()
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
            onHandsDetected(null)
            return
        }
        val p = hand.map { GestureGeometry.Point(it.x(), it.y(), it.z()) }
        val top = result.gestures().firstOrNull()?.firstOrNull()
        val modelName = top?.categoryName() ?: "None"
        val modelConfidence = top?.score() ?: 0f

        // Use MediaPipe's original seven categories first. Only fall back
        // to user model and geometric shapes when no strong native match.
        val fallback = if (modelName == "None" || modelConfidence < .55f) {
            custom.predict(p) ?: GestureGeometry.recognize(p)
        } else null
        val name = fallback?.first ?:
            if (modelConfidence >= .55f) modelName else "None"
        val confidence = fallback?.second ?: modelConfidence

        val dx = p[4].x - p[8].x
        val dy = p[4].y - p[8].y
        val dist2 = dx * dx + dy * dy
        val angle = Math.toDegrees(atan2(
            (p[0].y - p[8].y).toDouble(),
            (p[8].x - p[0].x).toDouble()
        )).toFloat()

        onHandsDetected(HandGesture(
            isFiveFingersOpen = name == "Open_Palm",
            isFiveFingersClosed = name == "Fist" || name == "Closed_Fist",
            isFourFingersOpen = false,
            isPinching = name == "OK" || dist2 < .0025f,
            pinchDistanceSquared = dist2,
            indexFingerAngleDeg = angle,
            indexFingerPosition = IndexFingerPosition(p[8].x, p[8].y),
            landmarks = p.map { NormalizedPoint(it.x, it.y) },
            gestureName = name,
            confidence = confidence
        ))
    }

    fun release() {
        recognizer?.close()
        recognizer = null
        custom.close()
    }
}
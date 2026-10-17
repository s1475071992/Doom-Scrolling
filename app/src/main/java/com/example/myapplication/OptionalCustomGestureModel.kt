package com.example.myapplication

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Optional gesture_custom.tflite + gesture_labels.json support.
 * If neither model nor labels exist, seven MediaPipe + six geometry gestures still work.
 */
class OptionalCustomGestureModel(context: Context) {
    private var interpreter: Interpreter? = null
    private var labels = emptyList<String>()

    init {
        try {
            val assets = context.assets
            if ("gesture_custom.tflite" in (assets.list("") ?: emptyArray())) {
                val bytes = assets.open("gesture_custom.tflite").use { it.readBytes() }
                val buf = ByteBuffer.allocateDirect(bytes.size)
                    .order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
                val candidate = Interpreter(buf)
                val outputCount = candidate.getOutputTensor(0).shape().last()
                val j = assets.open("gesture_labels.json")
                    .bufferedReader().use { JSONArray(it.readText()) }
                val names = (0 until j.length()).map { j.getString(it) }
                if (outputCount >= 2 && outputCount == names.size) {
                    interpreter = candidate
                    labels = names
                } else {
                    candidate.close()
                    Log.w("CustomGesture", "Invalid model output count or labels")
                }
            }
        } catch (e: Exception) {
            Log.w("CustomGesture", "No valid optional model: ${e.message}")
        }
    }
    fun predict(p: List<GestureGeometry.Point>): Pair<String, Float>? {
        val model = interpreter ?: return null
        if (p.size != 21) return null
        return try {
            val data = ByteBuffer.allocateDirect(63 * 4).order(ByteOrder.nativeOrder())
            p.forEach { data.putFloat(it.x); data.putFloat(it.y); data.putFloat(it.z) }
            data.rewind()
            val scores = Array(1) { FloatArray(labels.size) }
            synchronized(model) { model.run(data, scores) }
            val order = scores[0].indices.sortedByDescending { scores[0][it] }
            val top = order[0]
            val probability = scores[0][top]
            val margin = probability - scores[0][order[1]]
            val name = labels[top]
            if (probability >= .90f && margin >= .35f &&
                name != "None" && name != "No_Gesture") name to probability else null
        } catch (e: Exception) {
            Log.w("CustomGesture", "Inference failed: ${e.message}")
            null
        }
    }
    fun close() { interpreter?.close(); interpreter = null }
}
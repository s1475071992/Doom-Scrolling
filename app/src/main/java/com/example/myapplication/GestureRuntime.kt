package com.example.myapplication

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** User preferences are independent from camera and accessibility permissions. */
object GestureRuntime {
    private const val FILE = "gesture_runtime"
    @Volatile var airMouse: Boolean = false
        private set
    @Volatile var feedback: Boolean = true
        private set

    fun init(context: Context) {
        val pref = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        airMouse = pref.getBoolean("air_mouse", false)
        feedback = pref.getBoolean("feedback", true)
    }

    fun setAirMouse(context: Context, enabled: Boolean) {
        airMouse = enabled
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean("air_mouse", enabled).apply()
        if (!enabled) ScrollAccessibilityService.getInstance()?.hideCursor()
    }

    fun setFeedback(context: Context, enabled: Boolean) {
        feedback = enabled
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean("feedback", enabled).apply()
    }
}

/** Compose snapshot updated only on main thread; no camera images are persisted. */
object GestureDiagnostics {
    data class Snapshot(
        val fps: Float = 0f,
        val name: String = "未识别",
        val confidence: Float = 0f,
        val lastAction: String = "—",
        val state: String = "等待动作",
        val overlay: String = "尚未建立"
    )
    private val main = Handler(Looper.getMainLooper())
    var current by mutableStateOf(Snapshot())
        private set

    fun recognition(fps: Float, name: String, confidence: Float) {
        main.post {
            current = current.copy(
                fps = fps, name = GestureCatalog.label(name), confidence = confidence
            )
        }
    }
    fun state(state: String) {
        main.post { current = current.copy(state = state) }
    }
    fun action(text: String) {
        main.post { current = current.copy(lastAction = text) }
    }
    fun overlay(status: String) {
        main.post { current = current.copy(overlay = status) }
    }
    fun reset() {
        main.post { current = Snapshot() }
    }
    fun text(): String = String.format(
        Locale.US, "帧率 %.1f · 置信度 %.0f%% · %s · %s",
        current.fps, current.confidence * 100f, current.name, current.state
    )
}
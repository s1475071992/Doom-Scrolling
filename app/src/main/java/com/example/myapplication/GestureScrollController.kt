package com.example.myapplication

import android.os.SystemClock

/** Pose stabilizer and action trigger, replacing the old finger-angle scrolling.
 * Keeps the result type used by Doom's Activity / background service.
 */
class GestureScrollController {
    enum class GestureAction { SCROLL_DOWN, SCROLL_UP, PAUSE, RESUME, NONE }
    data class GestureResult(val action: GestureAction, val scrollAmount: Int = 0)

    private var candidate = "None"
    private var candidateCount = 0
    private var lastFired = "None"
    private var lastFiredAt = 0L
    private var missingSince = 0L
    private var noneSince = 0L

    fun processGesture(gesture: HandDetectionAnalyzer.HandGesture?): GestureResult {
        val now = SystemClock.uptimeMillis()
        if (gesture == null) {
            reset()
            return GestureResult(GestureAction.NONE)
        }
        missingSince = 0L
        val name = if (gesture.confidence >= .55f) gesture.gestureName else "None"
        if (name == "None") {
            if (noneSince == 0L) noneSince = now
            if (now - noneSince >= 500L) lastFired = "None"
            candidate = "None"
            candidateCount = 0
            return GestureResult(GestureAction.NONE)
        }
        noneSince = 0L
        if (name != candidate) {
            candidate = name
            candidateCount = 1
        } else {
            candidateCount++
        }
        if (candidateCount >= 4 && name != lastFired && now - lastFiredAt >= 1000L) {
            lastFired = name
            lastFiredAt = now
            GestureActionRouter.dispatch(name)
        }
        // The router handles system actions directly; avoid old angle-scroll handling.
        return GestureResult(GestureAction.NONE)
    }

    fun reset() {
        candidate = "None"
        candidateCount = 0
        noneSince = 0L
        val now = SystemClock.uptimeMillis()
        if (missingSince == 0L) missingSince = now
        if (now - missingSince >= 500L) lastFired = "None"
    }

    /** Legacy API, no longer used to pause gesture recognition. */
    fun isPaused(): Boolean = false
}
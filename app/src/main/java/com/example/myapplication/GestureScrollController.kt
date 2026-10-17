package com.example.myapplication

import android.os.SystemClock

/**
 * Explicit idle -> candidate -> fired -> release finite-state machine.
 * Avoids frame-count-dependent firing, repeated gestures and jitter.
 */
class GestureScrollController {
    enum class GestureAction { SCROLL_DOWN, SCROLL_UP, PAUSE, RESUME, NONE }
    data class GestureResult(val action: GestureAction = GestureAction.NONE, val scrollAmount: Int = 0)

    private enum class Phase { IDLE, CANDIDATE, HELD }
    private var phase = Phase.IDLE
    private var candidate = "None"
    private var candidateSince = 0L
    private var releaseSince = 0L
    private var lastActionAt = 0L
    private var pinchSince = 0L
    private var pinchLatched = false
    private var wasAirMouse = false

    private val holdMs = 230L
    private val releaseMs = 300L
    private val cooldownMs = 550L

    fun processGesture(gesture: HandDetectionAnalyzer.HandGesture?): GestureResult {
        val now = SystemClock.uptimeMillis()
        if (GestureRuntime.airMouse != wasAirMouse) {
            wasAirMouse = GestureRuntime.airMouse
            phase = Phase.IDLE
            candidate = "None"
            candidateSince = 0L
            releaseSince = 0L
            pinchSince = 0L
            pinchLatched = false
            if (!wasAirMouse) ScrollAccessibilityService.getInstance()?.hideCursor()
        }
        if (wasAirMouse) {
            processMouse(gesture, now)
            return GestureResult()
        }
        val name = if (gesture != null && gesture.confidence >= .65f)
            GestureCatalog.canonical(gesture.gestureName) else "None"

        when (phase) {
            Phase.IDLE -> {
                if (name != "None") {
                    phase = Phase.CANDIDATE
                    candidate = name
                    candidateSince = now
                    GestureDiagnostics.state("CANDIDATE")
                }
            }
            Phase.CANDIDATE -> {
                if (name != candidate) {
                    if (name == "None") {
                        phase = Phase.IDLE
                        candidate = "None"
                        GestureDiagnostics.state("IDLE")
                    } else {
                        candidate = name
                        candidateSince = now
                    }
                } else if (now - candidateSince >= holdMs &&
                    now - lastActionAt >= cooldownMs) {
                    phase = Phase.HELD
                    releaseSince = 0L
                    lastActionAt = now
                    GestureDiagnostics.state("HELD")
                    GestureActionRouter.dispatch(candidate)
                }
            }
            Phase.HELD -> {
                // Any other pose MUST be held for releaseMs before next action.
                // This prevents quick A <-> B model label oscillation.
                if (name == candidate) {
                    releaseSince = 0L
                } else {
                    if (releaseSince == 0L) releaseSince = now
                    if (now - releaseSince >= releaseMs) {
                        phase = Phase.IDLE
                        candidate = "None"
                        releaseSince = 0L
                        GestureDiagnostics.state("IDLE")
                    }
                }
            }
        }
        return GestureResult()
    }

    private fun processMouse(gesture: HandDetectionAnalyzer.HandGesture?, now: Long) {
        GestureDiagnostics.state("AIR MOUSE")
        val accessibility = ScrollAccessibilityService.getInstance()
        if (accessibility == null || !ScrollAccessibilityService.isEnabled()) {
            GestureDiagnostics.action("Accessibility not connected / 无障碍未连接")
            return
        }
        if (gesture == null) {
            accessibility.hideCursor()
            pinchSince = 0L
            pinchLatched = false
            return
        }
        val pointer = gesture.indexFingerPosition ?: return
        // Ratio = fingertip separation / palm width, robust over camera distance.
        val ratio = gesture.pinchRatio
        val down = ratio < .49f
        val released = ratio > .75f

        if (released || (pinchSince == 0L && !down)) {
            pinchSince = 0L
            pinchLatched = false
            accessibility.moveCursor(pointer.x, pointer.y)
        } else if (down) {
            if (pinchSince == 0L) {
                pinchSince = now
                // Initial pinch position: cursor stays here while fingers converge.
                accessibility.moveCursor(pointer.x, pointer.y)
            }
            if (!pinchLatched && now - pinchSince >= 115L &&
                now - lastActionAt >= 430L) {
                pinchLatched = true
                lastActionAt = now
                GestureActionRouter.clickCursor()
            }
        }
        // Between thresholds, preserve cursor position to avoid jitter.
    }

    fun reset() {
        // Called if no hand is found. Maintain release timing rather than
        // immediately arming another copy of the same gesture.
        processGesture(null)
    }
    fun isPaused() = false
}
package com.example.myapplication

/**
 * In standard gesture mode, perform one recognition per second while waiting
 * for a starting pose. Once a potential pose is found, run at a higher cadence
 * so the existing 5-frame / 440ms confirmation and direction detection work.
 *
 * This throttles model inference, not the camera sensor itself.
 */
object RecognitionCadence {
    const val WAITING_INTERVAL_MS = 1000L

    fun intervalMs(airMouse: Boolean, powerSaving: Boolean, poseInProgress: Boolean): Long =
        when {
            airMouse && powerSaving -> 40L
            airMouse -> 32L
            !poseInProgress -> WAITING_INTERVAL_MS
            powerSaving -> 68L
            else -> 55L
        }
}

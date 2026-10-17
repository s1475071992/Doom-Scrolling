package com.example.myapplication

/** Adaptive inference policy, separate from actual camera sensor usage. */
object RecognitionCadence {
    fun intervalMs(airMouse: Boolean, powerSaving: Boolean, noHandForMs: Long): Long =
        when {
            !powerSaving && airMouse -> 32L
            !powerSaving -> 55L
            airMouse -> 40L
            noHandForMs > 2500L -> 125L
            else -> 68L
        }
}

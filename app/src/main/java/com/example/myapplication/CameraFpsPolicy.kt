package com.example.myapplication

/**
 * Camera2 auto-exposure frame-rate selection. Only choose ranges actually
 * advertised by the device; [1,1] cannot be fabricated by the app.
 */
data class CameraFpsBand(val min: Int, val max: Int) {
    init { require(min > 0 && max >= min) }
    override fun toString(): String = if (min == max) "$min FPS" else "$min–$max FPS"
}

object CameraFpsPolicy {
    // Lowest advertised *maximum* FPS, not merely lower bound [1,30].
    fun standby(ranges: List<CameraFpsBand>): CameraFpsBand? =
        ranges.minWithOrNull(compareBy<CameraFpsBand>({ it.max }, { it.min }))

    // Prefer steady 30 FPS on a valid device-advertised range when active.
    fun active(ranges: List<CameraFpsBand>): CameraFpsBand? =
        ranges.minWithOrNull(compareBy<CameraFpsBand>(
            { kotlin.math.abs(it.max - 30) },
            { 30 - it.min }
        ))
}

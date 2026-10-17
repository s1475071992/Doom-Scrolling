package com.example.myapplication

import kotlin.math.hypot

/** Landmark-based fallback for GestureControl's additional hand shapes.
 * Confidence is a heuristic score, not calibrated model probability.
 */
object GestureGeometry {
    data class Point(val x: Float, val y: Float, val z: Float = 0f)

    fun recognize(p: List<Point>): Pair<String, Float>? {
        if (p.size != 21) return null
        val thumb = thumbExtended(p)
        val index = extended(p, 8, 6, 5)
        val middle = extended(p, 12, 10, 9)
        val ring = extended(p, 16, 14, 13)
        val pinky = extended(p, 20, 18, 17)
        val width = distance(p[5], p[17]).coerceAtLeast(0.02f)
        val ok = distance(p[4], p[8]) < width * .38f
        return when {
            ok && middle && ring && pinky -> "OK" to .84f
            thumb && !index && !middle && !ring && pinky -> "Shaka" to .82f
            thumb && index && !middle && !ring && !pinky -> "L_Shape" to .82f
            else -> null
        }
    }

    private fun extended(p: List<Point>, tip: Int, pip: Int, mcp: Int): Boolean {
        val wrist = p[0]
        return distance(p[tip], wrist) > distance(p[pip], wrist) * 1.09f &&
            distance(p[tip], p[mcp]) > distance(p[pip], p[mcp]) * 1.48f
    }
    private fun thumbExtended(p: List<Point>): Boolean =
        distance(p[4], p[2]) > distance(p[3], p[2]) * 1.32f &&
            distance(p[4], p[5]) > distance(p[3], p[5]) * 1.12f

    private fun distance(a: Point, b: Point): Float = hypot(a.x - b.x, a.y - b.y)
}
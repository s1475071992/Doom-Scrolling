package com.example.myapplication

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Pixel-space, upright-frame crop used by the experimental NPU hand model. */
internal data class NpuRoi(val x: Float, val y: Float, val side: Float, val theta: Float)
internal data class NpuPoint2(val x: Float, val y: Float)

/**
 * Keep ROI geometry separately testable: returned landmarks must be reprojected
 * in the *original image* coordinate space for gesture direction detection.
 */
internal object NpuRoiGeometry {
    private fun angle(wx: Float, wy: Float, mx: Float, my: Float): Float =
        (PI.toFloat() / 2f) - atan2(wy - my, mx - wx)

    fun fallback(x: Float, y: Float, boxSide: Float): NpuRoi =
        NpuRoi(x, y, (boxSide * 2.6f).coerceAtLeast(64f), 0f)

    fun fromPalm(
        wx: Float, wy: Float, mx: Float, my: Float,
        palmWidth: Float, palmHeight: Float
    ): NpuRoi {
        val dx = mx - wx
        val dy = my - wy
        // The two palm keypoints do not include fingertips: shift crop from
        // wrist/MCP midpoint toward the fingers to keep V fingertips inside.
        val cx = (wx + mx) * .5f + dx * .62f
        val cy = (wy + my) * .5f + dy * .62f
        val side = max(
            max(palmWidth, palmHeight) * 2.6f,
            hypot(dx, dy) * 3.0f
        ).coerceAtLeast(64f)
        return NpuRoi(cx, cy, side, angle(wx, wy, mx, my))
    }

    fun fromLandmarks(points: List<NpuPoint2>, width: Int, height: Int): NpuRoi {
        require(points.size == 21)
        val wrist = points[0]
        val middle = points[9]
        val wx = wrist.x * width
        val wy = wrist.y * height
        val mx = middle.x * width
        val my = middle.y * height
        val theta = angle(wx, wy, mx, my)
        val c = cos(theta)
        val s = sin(theta)
        var left = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        for (p in points) {
            val dx = p.x * width - wx
            val dy = p.y * height - wy
            // Rotate landmarks into the same axes as the rotated hand crop.
            val rx = dx * c + dy * s
            val ry = -dx * s + dy * c
            left = kotlin.math.min(left, rx)
            right = max(right, rx)
            top = kotlin.math.min(top, ry)
            bottom = max(bottom, ry)
        }
        val ax = (left + right) * .5f
        val ay = (top + bottom) * .5f
        val centerX = wx + ax * c - ay * s
        val centerY = wy + ax * s + ay * c
        val palmSpan = hypot(
            (points[5].x - points[17].x) * width,
            (points[5].y - points[17].y) * height
        )
        // Smaller/more stable than 2.1x axis-aligned bounds, but leave
        // ~20% padding on each side and enough room for curled fingertips.
        val side = max(
            max(right - left, bottom - top) * 1.40f,
            palmSpan * 2.05f
        ).coerceAtLeast(64f)
        return NpuRoi(centerX, centerY, side, theta)
    }

    fun smooth(previous: NpuRoi, target: NpuRoi): NpuRoi {
        val turn = atan2(
            sin(target.theta - previous.theta),
            cos(target.theta - previous.theta)
        )
        return NpuRoi(
            previous.x + (target.x - previous.x) * .85f,
            previous.y + (target.y - previous.y) * .85f,
            previous.side + (target.side - previous.side) * .60f,
            previous.theta + turn * .70f
        )
    }
}

package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NpuRoiGeometryTest {
    private fun hand(dx: Float = 0f, dy: Float = 0f): List<NpuPoint2> {
        val p = MutableList(21) { NpuPoint2(.5f, .65f) }
        p[0] = NpuPoint2(.50f, .88f)
        p[5] = NpuPoint2(.36f, .62f)
        p[8] = NpuPoint2(.36f, .24f)
        p[9] = NpuPoint2(.50f, .59f)
        p[12] = NpuPoint2(.50f, .23f)
        p[13] = NpuPoint2(.61f, .63f)
        p[17] = NpuPoint2(.65f, .64f)
        return p.map { NpuPoint2(it.x + dx, it.y + dy) }
    }

    @Test fun palmCropMovesTowardFingersSoTipsAreNotClipped() {
        val roi = NpuRoiGeometry.fromPalm(240f, 315f, 240f, 222f, 100f, 100f)
        assertTrue(roi.y < (315f + 222f) * .5f)
        assertTrue("V fingertips should be inside top edge",
            roi.y - roi.side * .5f < 90f)
    }

    @Test fun trackingCropFollowsAllFourDirectionsWithoutShrinking() {
        val base = NpuRoiGeometry.fromLandmarks(hand(), 480, 360)
        for ((dx, dy) in listOf(
            -.15f to 0f, .15f to 0f, 0f to -.15f, 0f to .15f
        )) {
            val moved = NpuRoiGeometry.fromLandmarks(hand(dx, dy), 480, 360)
            assertEquals(dx * 480f, moved.x - base.x, .02f)
            assertEquals(dy * 360f, moved.y - base.y, .02f)
            assertEquals(base.side, moved.side, .02f)
            assertEquals(base.theta, moved.theta, .001f)
        }
    }

    @Test fun roiSmoothingFollowsMotionAndRestrainsScaleJitter() {
        val start = NpuRoi(160f, 140f, 220f, 0f)
        val updated = NpuRoiGeometry.smooth(start, NpuRoi(210f, 170f, 130f, 0f))
        assertTrue(updated.x > 197f)
        assertTrue(updated.y > 161f)
        assertTrue(updated.side > 130f)
        assertTrue(updated.side < 220f)
    }
}

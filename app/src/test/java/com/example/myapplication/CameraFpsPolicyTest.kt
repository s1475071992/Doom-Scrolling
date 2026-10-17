package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraFpsPolicyTest {
    @Test fun trulyOneFpsWhenDriverAdvertisesFixedOne() {
        val ranges = listOf(CameraFpsBand(1, 1), CameraFpsBand(15, 15), CameraFpsBand(15, 30))
        assertEquals(CameraFpsBand(1, 1), CameraFpsPolicy.standby(ranges))
        assertEquals(CameraFpsBand(15, 30), CameraFpsPolicy.active(ranges))
    }

    @Test fun variableOneToThirtyIsNotMisreportedAsOneFps() {
        val ranges = listOf(CameraFpsBand(1, 30), CameraFpsBand(15, 15), CameraFpsBand(30, 30))
        assertEquals(CameraFpsBand(15, 15), CameraFpsPolicy.standby(ranges))
        assertEquals(CameraFpsBand(30, 30), CameraFpsPolicy.active(ranges))
    }

    @Test fun chooseLowestMaximumOnlyFromAvailableRanges() {
        val ranges = listOf(CameraFpsBand(7, 30), CameraFpsBand(5, 15), CameraFpsBand(15, 30))
        assertEquals(CameraFpsBand(5, 15), CameraFpsPolicy.standby(ranges))
        assertEquals(CameraFpsBand(15, 30), CameraFpsPolicy.active(ranges))
    }

    @Test fun emptyCharacteristicsLeaveCameraControlUntouched() {
        assertNull(CameraFpsPolicy.standby(emptyList()))
        assertNull(CameraFpsPolicy.active(emptyList()))
    }
}

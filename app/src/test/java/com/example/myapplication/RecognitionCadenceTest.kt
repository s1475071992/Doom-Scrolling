package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionCadenceTest {
    @Test fun waitingPoseAlwaysUsesOneFps() {
        assertEquals(1000L, RecognitionCadence.intervalMs(false, true, false))
        assertEquals(1000L, RecognitionCadence.intervalMs(false, false, false))
    }

    @Test fun firstCandidateWakesHigherRecognitionRate() {
        assertEquals(68L, RecognitionCadence.intervalMs(false, true, true))
        assertEquals(55L, RecognitionCadence.intervalMs(false, false, true))
        assertTrue(RecognitionCadence.intervalMs(false, true, false) >
            RecognitionCadence.intervalMs(false, true, true))
    }

    @Test fun airMouseRemainsSmoothEvenWhenNoGesturePoseIsArmed() {
        assertEquals(40L, RecognitionCadence.intervalMs(true, true, false))
        assertEquals(32L, RecognitionCadence.intervalMs(true, false, false))
        assertEquals(40L, RecognitionCadence.intervalMs(true, true, true))
    }
}

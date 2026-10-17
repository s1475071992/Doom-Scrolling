package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionCadenceTest {
    @Test fun standbyAndWakeup() {
        assertEquals(68L, RecognitionCadence.intervalMs(false,true,0L))
        assertEquals(68L, RecognitionCadence.intervalMs(false,true,2500L))
        assertEquals(125L, RecognitionCadence.intervalMs(false,true,2501L))
        assertEquals(68L, RecognitionCadence.intervalMs(false,true,100L))
    }
    @Test fun airMouseRemainsSmooth() {
        assertEquals(40L, RecognitionCadence.intervalMs(true,true,120_000L))
        assertEquals(32L, RecognitionCadence.intervalMs(true,false,120_000L))
    }
    @Test fun offSwitchRestoresFrameRate() {
        assertEquals(55L, RecognitionCadence.intervalMs(false,false,120_000L))
        assertTrue(RecognitionCadence.intervalMs(false,true,120_000L) >
            RecognitionCadence.intervalMs(false,false,120_000L))
    }
}

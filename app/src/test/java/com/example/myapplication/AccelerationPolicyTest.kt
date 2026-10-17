package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Test

class AccelerationPolicyTest {
    @Test fun automaticModeAlwaysRequestsNpuFirstThenFallsBack() {
        assertEquals(
            listOf(InferenceBackend.NPU, InferenceBackend.GPU, InferenceBackend.CPU),
            AccelerationPolicy.candidates(AccelerationMode.AUTO)
        )
    }

    @Test fun gpuAndCpuPreferencesHaveSafeFallthrough() {
        assertEquals(listOf(InferenceBackend.GPU, InferenceBackend.CPU),
            AccelerationPolicy.candidates(AccelerationMode.GPU))
        assertEquals(listOf(InferenceBackend.CPU),
            AccelerationPolicy.candidates(AccelerationMode.CPU))
    }

    @Test fun invalidPreferenceResetsToAuto() {
        assertEquals(AccelerationMode.AUTO, AccelerationMode.fromKey("invalid"))
        assertEquals(AccelerationMode.AUTO, AccelerationMode.fromKey(null))
        assertEquals(AccelerationMode.GPU, AccelerationMode.fromKey("gpu"))
    }
}

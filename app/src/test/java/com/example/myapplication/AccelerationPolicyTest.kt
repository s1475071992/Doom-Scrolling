package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccelerationPolicyTest {
    @Test fun automaticModeKeepsFallbackOrder() {
        assertEquals(listOf(InferenceBackend.GPU, InferenceBackend.CPU),
            AccelerationPolicy.candidates(AccelerationMode.AUTO))
        assertEquals(listOf(InferenceBackend.NPU, InferenceBackend.GPU, InferenceBackend.CPU),
            AccelerationPolicy.candidates(AccelerationMode.AUTO, npuRunnerValidated = true))
    }

    @Test fun gpuAndCpuPreferencesHaveSafeFallthrough() {
        assertEquals(listOf(InferenceBackend.GPU, InferenceBackend.CPU),
            AccelerationPolicy.candidates(AccelerationMode.GPU))
        assertEquals(listOf(InferenceBackend.CPU),
            AccelerationPolicy.candidates(AccelerationMode.CPU))
    }

    @Test fun invalidPreferenceResetsToAuto() {
        assertEquals(AccelerationMode.AUTO, AccelerationMode.fromKey("invalid"))
        assertEquals(AccelerationMode.GPU, AccelerationMode.fromKey("gpu"))
        assertFalse(ExperimentalNpuBridge.validatedRunnerInstalled)
        assertTrue(ExperimentalNpuBridge.status.contains("NPU"))
    }
}

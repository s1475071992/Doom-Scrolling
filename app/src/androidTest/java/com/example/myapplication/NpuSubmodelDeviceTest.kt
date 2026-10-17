package com.example.myapplication

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Must be executed on real Qualcomm/MediaTek NPU hardware.
 * Passing this test is only model-execution evidence: vendor profiler logs
 * are still required to exclude LiteRT's silent CPU partition fallback.
 */
@RunWith(AndroidJUnit4::class)
class NpuSubmodelDeviceTest {
    @Test fun landmarkSubmodelCompilesAndRunsOnDevice() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val vendor = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else ""
        assumeTrue(vendor.contains("qualcomm", ignoreCase = true) ||
            vendor.contains("mediatek", ignoreCase = true))
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val data = ctx.assets.open("hand_landmark.tflite").use { it.readBytes() }
        val modelBuffer = ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder())
        modelBuffer.put(data).rewind()

        val reference = Interpreter(modelBuffer)
        val shape = reference.getInputTensor(0).shape()
        reference.close()
        val values = FloatArray(shape.fold(1) { a, b -> a * b })
        val probe = NpuSubmodelProbe.create(ctx, "hand_landmark.tflite")
        assertNotNull("Vendor runtime or model NPU JIT is unavailable", probe)
        probe!!.use {
            val outputs = it.runPreprocessedFloat(values)
            assertTrue(outputs.isNotEmpty())
            assertTrue(outputs.all { tensor -> tensor.isNotEmpty() &&
                tensor.all { value -> value.isFinite() } })
        }
    }
}

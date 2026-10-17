package com.example.myapplication

import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.util.Locale
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer

/**
 * An actual LiteRT CompiledModel NPU inference probe for extracted palm/landmark
 * TFLite submodels, on Qualcomm/MediaTek Android 12+ hardware.
 *
 * IMPORTANT: this does NOT implement the camera-to-landmarks pipeline. A model
 * returned from Options(NPU) can contain CPU fallback partitions, so a passing
 * inference is NOT proof of NPU utilization without chipset profiler/tracing.
 * Never publish NPU as an active gesture backend on this basis alone.
 */
class NpuSubmodelProbe private constructor(
    private val environment: Environment,
    private val model: CompiledModel,
    private val inputs: List<TensorBuffer>,
    private val outputs: List<TensorBuffer>
) : AutoCloseable {
    data class RunMetrics(val count: Long, val averageMs: Double, val lastMs: Double) {
        fun shortText(): String = String.format(
            Locale.US, "%d次 · 均%.1fms · 最近%.1fms", count, averageMs, lastMs
        )
    }

    private var runCount = 0L
    private var totalRunNanos = 0L
    private var lastRunNanos = 0L

    fun metrics(): RunMetrics = RunMetrics(
        runCount,
        if (runCount > 0) totalRunNanos / runCount / 1_000_000.0 else 0.0,
        lastRunNanos / 1_000_000.0
    )

    companion object {
        fun deviceLabel(): String =
            if (Build.VERSION.SDK_INT >= 31)
                "${Build.SOC_MANUFACTURER} / ${Build.SOC_MODEL} · ${Build.HARDWARE}"
            else "${Build.MANUFACTURER} / ${Build.HARDWARE}"


        fun create(context: Context, assetName: String): NpuSubmodelProbe? {
            GestureDiagnostics.chipset(deviceLabel())
            if (Build.VERSION.SDK_INT < 31 || !Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")) {
                GestureDiagnostics.fallbackReason("Android 12 或 arm64 不满足 NPU 条件")
                return null
            }
            val provider = try {
                BuiltinNpuAcceleratorProvider(context)
            } catch (e: Exception) {
                Log.w("NpuSubmodel", "NPU provider unavailable", e)
                return null
            }
            if (!provider.isDeviceSupported() || !provider.isLibraryReady()) {
                GestureDiagnostics.fallbackReason("NPU 提供程序/厂商运行库不可用")
                Log.i("NpuSubmodel", "Vendor libraries not installed or SoC unsupported")
                return null
            }
            var env: Environment? = null
            var compiled: CompiledModel? = null
            try {
                env = Environment.create(context, provider)
                compiled = CompiledModel.create(
                    context.assets,
                    assetName,
                    CompiledModel.Options(Accelerator.NPU),
                    env
                )
                val inBuffers = compiled.createInputBuffers()
                val outBuffers = compiled.createOutputBuffers()
                Log.i("NpuSubmodel", "Requested NPU(+CPU fallback): $assetName, " +
                    "supported=${provider.isDeviceSupported()}, " +
                    "libReady=${provider.isLibraryReady()}. Delegate coverage NOT VERIFIED")
                return NpuSubmodelProbe(env, compiled, inBuffers, outBuffers)
            } catch (e: Exception) {
                Log.w("NpuSubmodel", "NPU submodel JIT compile failed: $assetName", e)
                GestureDiagnostics.fallbackReason("$assetName 初始化失败: ${e.javaClass.simpleName}")
                try { compiled?.close() } catch (_: Exception) { }
                try { env?.close() } catch (_: Exception) { }
                return null
            }
        }
    }

    /**
     * Caller supplies a correctly cropped/normalized tensor in model-native
     * input order. No dummy inference is silently used by the app.
     * Must be called on a dedicated serial worker, not the UI thread.
     */
    fun runPreprocessedFloat(input: FloatArray): List<FloatArray> {
        check(inputs.size == 1) { "Expected one tensor input" }
        inputs[0].writeFloat(input)
        val start = SystemClock.elapsedRealtimeNanos()
        model.run(inputs, outputs)
        lastRunNanos = SystemClock.elapsedRealtimeNanos() - start
        totalRunNanos += lastRunNanos
        runCount++
        return outputs.map { it.readFloat() }
    }

    override fun close() {
        inputs.forEach { it.close() }
        outputs.forEach { it.close() }
        model.close()
        environment.close()
    }
}

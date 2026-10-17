package com.example.myapplication

/** Experimental inference acceleration settings, independent of accessibility permissions. */
enum class AccelerationMode(val key: String, val title: String) {
    AUTO("auto", "自动（NPU → GPU → CPU）"),
    GPU("gpu", "GPU 优先（失败回退 CPU）"),
    CPU("cpu", "仅 CPU（兼容模式）");

    companion object {
        fun fromKey(value: String?): AccelerationMode =
            entries.firstOrNull { it.key == value } ?: AUTO
    }
}

enum class InferenceBackend { NPU, GPU, CPU }

/**
 * Only return NPU when an actual tested NPU runner is installed. Detecting a
 * Snapdragon/Dimensity SoC is NOT proof that a .task model can execute on NPU.
 * This bridge is intentionally disabled until palm + landmark preprocess,
 * delegates and postprocess are implemented and verified on both vendors.
 */
object ExperimentalNpuBridge {
    const val validatedRunnerInstalled: Boolean = false
    const val status: String = "NPU 引擎开发中：当前 MediaPipe .task 不能直接执行 NPU 委托"
}

object AccelerationPolicy {
    fun candidates(
        mode: AccelerationMode,
        npuRunnerValidated: Boolean = ExperimentalNpuBridge.validatedRunnerInstalled
    ): List<InferenceBackend> = when (mode) {
        AccelerationMode.CPU -> listOf(InferenceBackend.CPU)
        AccelerationMode.GPU -> listOf(InferenceBackend.GPU, InferenceBackend.CPU)
        AccelerationMode.AUTO -> (if (npuRunnerValidated)
            listOf(InferenceBackend.NPU) else emptyList()) +
            listOf(InferenceBackend.GPU, InferenceBackend.CPU)
    }
}

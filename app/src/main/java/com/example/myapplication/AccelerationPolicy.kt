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
 * Order of attempts, not a claim about physical accelerator dispatch.
 * The NPU runner checks the vendor runtime/model at startup and can return null;
 * GPU and CPU are then tried automatically without user opt-in.
 */
object AccelerationPolicy {
    fun candidates(mode: AccelerationMode): List<InferenceBackend> = when (mode) {
        AccelerationMode.AUTO -> listOf(
            InferenceBackend.NPU, InferenceBackend.GPU, InferenceBackend.CPU
        )
        AccelerationMode.GPU -> listOf(InferenceBackend.GPU, InferenceBackend.CPU)
        AccelerationMode.CPU -> listOf(InferenceBackend.CPU)
    }
}

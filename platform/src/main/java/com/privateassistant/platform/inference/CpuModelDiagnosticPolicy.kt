package com.privateassistant.platform.inference

data class ModelMemoryRequirements(
    val minimumTotalBytes: Long,
    val minimumAvailableBytes: Long,
)

/**
 * Conservative admission for the fixed synthetic CPU diagnostic only.
 * These thresholds are application configuration, not a measured model minimum,
 * a phone compatibility guarantee or authorization to answer document questions.
 */
object CpuModelDiagnosticPolicy {
    fun requirements(model: RegisteredModel): ModelMemoryRequirements? =
        requirementsForRuntime(model, LiteRtLmLoadProbe.RUNTIME_VERSION)

    fun permits(
        model: RegisteredModel,
        totalBytes: Long,
        availableBytes: Long,
        is64Bit: Boolean,
        lowMemory: Boolean,
    ): Boolean {
        val required = requirements(model) ?: return false
        if (!is64Bit || lowMemory || totalBytes <= 0 || availableBytes < 0 || availableBytes > totalBytes) {
            return false
        }
        return totalBytes >= required.minimumTotalBytes && availableBytes >= required.minimumAvailableBytes
    }

    // A runtime upgrade needs an explicit resource-profile review. Keeping the
    // runtime argument internal also lets JVM regressions exercise that denial.
    internal fun requirementsForRuntime(model: RegisteredModel, runtimeVersion: String): ModelMemoryRequirements? {
        if (runtimeVersion != "0.9.0" || model.id != "qwen25-15b-q8-legacy" ||
            model.sha256 != QWEN_SHA256 || model.byteCount != QWEN_BYTES ||
            !model.experimental || "CPU" !in model.backends) return null

        // The earlier nominal 6 GiB emulator was killed for LOW_MEMORY. A nominal
        // 10 GiB target completed generation/cancellation; neither observation
        // establishes a minimum. Require additional conservative phone headroom.
        return ModelMemoryRequirements(
            minimumTotalBytes = maxOf(model.minimumRamBytes, 10 * GIB),
            minimumAvailableBytes = maxOf(model.byteCount + 512 * MIB, 8 * GIB),
        )
    }

    private const val MIB = 1024L * 1024
    private const val GIB = 1024 * MIB
    private const val QWEN_BYTES = 1_597_931_520L
    private const val QWEN_SHA256 = "faa60663b333290c1496c499828b21d3e3254a788cacd8cce917ce0f761a2dc9"
}

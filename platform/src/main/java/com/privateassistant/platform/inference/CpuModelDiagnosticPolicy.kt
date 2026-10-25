package com.privateassistant.platform.inference

data class ModelMemoryRequirements(
    val minimumTotalBytes: Long,
    val minimumAvailableBytes: Long,
)

/**
 * Reviewed-artifact admission for the fixed synthetic CPU diagnostic only.
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
        if (runtimeVersion != "0.16.0" || model.runtimeVersion != runtimeVersion ||
            model.id != "qwen3-06b-q4-nothink" ||
            model.sha256 != QWEN_SHA256 || model.byteCount != QWEN_BYTES ||
            !model.experimental || "CPU" !in model.backends) return null

        // These settings admit a bounded experiment on nominal 4 GB devices,
        // whose Android-reported total is below 4 GiB. They are not measured
        // sufficiency: the exact quantized artifact still needs device probes.
        // The former Qwen 2.5/0.9 profile is deliberately retired, not weakened.
        return ModelMemoryRequirements(
            minimumTotalBytes = maxOf(model.minimumRamBytes, 3 * GIB),
            minimumAvailableBytes = maxOf(model.byteCount + 512 * MIB, 1536 * MIB),
        )
    }

    private const val MIB = 1024L * 1024
    private const val GIB = 1024 * MIB
    private const val QWEN_BYTES = 347_251_840L
    private const val QWEN_SHA256 = "2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139"
}

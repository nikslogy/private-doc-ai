package com.privateassistant.platform.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Public metadata and synthetic memory snapshots only; no model load or phone proof. */
class CpuModelDiagnosticPolicyTest {
    private fun model(
        id: String = "qwen25-15b-q8-legacy",
        sha256: String = QWEN_SHA256,
        bytes: Long = 1_597_931_520,
        minimumRamBytes: Long = 6 * GIB,
        backends: List<String> = listOf("CPU"),
    ) = RegisteredModel(
        id = id,
        revision = "1".repeat(40),
        fileName = "synthetic-public-metadata.litertlm",
        byteCount = bytes,
        sha256 = sha256,
        licenseIdentifier = "synthetic-test",
        licenseTermsUrl = "https://example.invalid/licence",
        requiresLicenseAcceptance = true,
        contextTokens = 512,
        minimumRamBytes = minimumRamBytes,
        evaluatedLanguages = emptyList(),
        evaluatedTasks = emptyList(),
        backends = backends,
    )

    @Test fun recordedSixGiBMemoryFailureIsRejectedBeforeNativeWork() {
        assertFalse(CpuModelDiagnosticPolicy.permits(model(),
            totalBytes = 6_072_516L * 1024, availableBytes = 4_457_868L * 1024,
            is64Bit = true, lowMemory = false))
    }

    @Test fun higherMemorySyntheticSnapshotCanPassTheConservativeGuard() {
        assertTrue(CpuModelDiagnosticPolicy.permits(model(),
            totalBytes = 11 * GIB, availableBytes = 9 * GIB,
            is64Bit = true, lowMemory = false))
        assertEquals(ModelMemoryRequirements(10 * GIB, 8 * GIB), CpuModelDiagnosticPolicy.requirements(model()))
    }

    @Test fun totalAndAvailableThresholdsAreBothRequired() {
        assertFalse(CpuModelDiagnosticPolicy.permits(model(), 10 * GIB - 1, 8 * GIB, true, false))
        assertFalse(CpuModelDiagnosticPolicy.permits(model(), 12 * GIB, 8 * GIB - 1, true, false))
        assertTrue(CpuModelDiagnosticPolicy.permits(model(), 10 * GIB, 8 * GIB, true, false))
    }

    @Test fun thirtyTwoBitAndAndroidLowMemoryStatesFailClosed() {
        assertFalse(CpuModelDiagnosticPolicy.permits(model(), 12 * GIB, 9 * GIB, false, false))
        assertFalse(CpuModelDiagnosticPolicy.permits(model(), 12 * GIB, 9 * GIB, true, true))
    }

    @Test fun invalidMemorySnapshotsCannotAdmitNativeWork() {
        listOf(-1L to 0L, 0L to 0L, 12 * GIB to -1L, 12 * GIB to 12 * GIB + 1).forEach { (total, available) ->
            assertFalse(CpuModelDiagnosticPolicy.permits(model(), total, available, true, false))
        }
    }

    @Test fun unreviewedArtifactIdentitiesAndNonCpuBackendsHaveNoProfile() {
        listOf(
            model(id = "synthetic-other"), model(sha256 = "a".repeat(64)),
            model(bytes = 1_597_931_519), model(backends = listOf("GPU")),
        ).forEach {
            assertNull(CpuModelDiagnosticPolicy.requirements(it))
            assertFalse(CpuModelDiagnosticPolicy.permits(it, 32 * GIB, 24 * GIB, true, false))
        }
    }

    @Test fun runtimeChangesNeedAnExplicitProfileReview() {
        listOf("", "0.8.0", "0.9.1", "0.18.0").forEach { version ->
            assertNull(CpuModelDiagnosticPolicy.requirementsForRuntime(model(), version))
        }
        assertEquals(ModelMemoryRequirements(10 * GIB, 8 * GIB),
            CpuModelDiagnosticPolicy.requirementsForRuntime(model(), "0.9.0"))
    }

    @Test fun stricterCatalogueMemoryRequirementIsPreserved() {
        val stricter = model(minimumRamBytes = 16 * GIB)
        assertEquals(ModelMemoryRequirements(16 * GIB, 8 * GIB), CpuModelDiagnosticPolicy.requirements(stricter))
        assertFalse(CpuModelDiagnosticPolicy.permits(stricter, 12 * GIB, 9 * GIB, true, false))
        assertTrue(CpuModelDiagnosticPolicy.permits(stricter, 16 * GIB, 9 * GIB, true, false))
    }

    companion object {
        private const val GIB = 1024L * 1024 * 1024
        private const val QWEN_SHA256 = "faa60663b333290c1496c499828b21d3e3254a788cacd8cce917ce0f761a2dc9"
    }
}

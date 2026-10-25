package com.privateassistant.platform.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reviewed public identities and synthetic memory snapshots; no model/phone proof. */
class CpuModelDiagnosticPolicyTest {
    private fun model(
        id: String = "qwen3-06b-q4-nothink",
        sha256: String = QWEN_SHA256,
        bytes: Long = 347_251_840,
        minimumRamBytes: Long = 3 * GIB,
        backends: List<String> = listOf("CPU"),
        runtimeVersion: String = "0.16.0",
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
        runtimeVersion = runtimeVersion,
    )

    @Test fun reviewedSmallModelUsesItsOwnExperimentalMemoryProfile() {
        assertEquals(ModelMemoryRequirements(3 * GIB, 1536 * MIB), CpuModelDiagnosticPolicy.requirements(model()))
    }

    @Test fun nominalFourGbSyntheticSnapshotDoesNotNeedFourGibReportedTotal() {
        assertTrue(CpuModelDiagnosticPolicy.permits(model(),
            totalBytes = 3800 * MIB, availableBytes = 1700 * MIB,
            is64Bit = true, lowMemory = false))
    }

    @Test fun totalAndAvailableThresholdsAreBothRequired() {
        assertFalse(CpuModelDiagnosticPolicy.permits(model(), 3 * GIB - 1, 1536 * MIB, true, false))
        assertFalse(CpuModelDiagnosticPolicy.permits(model(), 4 * GIB, 1536 * MIB - 1, true, false))
        assertTrue(CpuModelDiagnosticPolicy.permits(model(), 3 * GIB, 1536 * MIB, true, false))
    }

    @Test fun thirtyTwoBitAndAndroidLowMemoryStatesFailClosed() {
        assertFalse(CpuModelDiagnosticPolicy.permits(model(), 4 * GIB, 2 * GIB, false, false))
        assertFalse(CpuModelDiagnosticPolicy.permits(model(), 4 * GIB, 2 * GIB, true, true))
    }

    @Test fun invalidMemorySnapshotsCannotAdmitNativeWork() {
        listOf(-1L to 0L, 0L to 0L, 4 * GIB to -1L, 4 * GIB to 4 * GIB + 1).forEach { (total, available) ->
            assertFalse(CpuModelDiagnosticPolicy.permits(model(), total, available, true, false))
        }
    }

    @Test fun unreviewedArtifactIdentitiesAndNonCpuBackendsHaveNoProfile() {
        listOf(
            model(id = "synthetic-other"), model(sha256 = "a".repeat(64)),
            model(bytes = 347_251_839), model(backends = listOf("GPU")),
        ).forEach {
            assertNull(CpuModelDiagnosticPolicy.requirements(it))
            assertFalse(CpuModelDiagnosticPolicy.permits(it, 32 * GIB, 24 * GIB, true, false))
        }
    }

    @Test fun runtimeChangesNeedAnExplicitProfileReview() {
        listOf("", "0.9.0", "0.16.1", "0.18.0").forEach { version ->
            assertNull(CpuModelDiagnosticPolicy.requirementsForRuntime(model(), version))
        }
        assertEquals(ModelMemoryRequirements(3 * GIB, 1536 * MIB),
            CpuModelDiagnosticPolicy.requirementsForRuntime(model(), "0.16.0"))
    }

    @Test fun modelMetadataCannotClaimADifferentRuntime() {
        assertNull(CpuModelDiagnosticPolicy.requirements(model(runtimeVersion = "0.9.0")))
    }

    @Test fun oldHeavyModelCannotUseTheNewSmallModelAdmissionLimits() {
        val legacy = model(id = "qwen25-15b-q8-legacy",
            sha256 = "faa60663b333290c1496c499828b21d3e3254a788cacd8cce917ce0f761a2dc9",
            bytes = 1_597_931_520, minimumRamBytes = 6 * GIB, runtimeVersion = "0.9.0")
        assertNull(CpuModelDiagnosticPolicy.requirements(legacy))
        assertNull(CpuModelDiagnosticPolicy.requirementsForRuntime(legacy, "0.9.0"))
        assertFalse(CpuModelDiagnosticPolicy.permits(legacy, 32 * GIB, 24 * GIB, true, false))
    }

    @Test fun stricterCatalogueMemoryRequirementIsPreserved() {
        val stricter = model(minimumRamBytes = 5 * GIB)
        assertEquals(ModelMemoryRequirements(5 * GIB, 1536 * MIB), CpuModelDiagnosticPolicy.requirements(stricter))
        assertFalse(CpuModelDiagnosticPolicy.permits(stricter, 4 * GIB, 2 * GIB, true, false))
        assertTrue(CpuModelDiagnosticPolicy.permits(stricter, 5 * GIB, 2 * GIB, true, false))
    }

    companion object {
        private const val MIB = 1024L * 1024
        private const val GIB = 1024 * MIB
        private const val QWEN_SHA256 = "2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139"
    }
}

package com.privateassistant.platform.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiteRtLmM0InstrumentedTest {
    @Test
    fun emptyCatalogueReportsUnavailable() {
        val json = InstrumentationRegistry.getInstrumentation().context.assets.open("models.json")
            .bufferedReader().use { it.readText() }
        val catalogue = ModelCatalogue.parse(json)
        if (catalogue.models.isEmpty()) assertTrue(catalogue.availability() is ModelAvailability.Unavailable)
    }

    @Test
    fun modelLoadProbeRequiresExplicitVerifiedModel() {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("modelPath")
        assumeTrue("No model supplied; runtime load is not validated", !path.isNullOrBlank())
        require(args.getString("modelLicenseAccepted") == "true") { "Model licence must be acknowledged explicitly" }
        require(args.getString("runtimeVersion") == LiteRtLmLoadProbe.RUNTIME_VERSION) { "Pinned runtime version required" }
        val file = File(requireNotNull(path))
        val expectedHash = requireNotNull(args.getString("modelSha256"))
        val expectedBytes = requireNotNull(args.getString("modelByteCount")).toLong()
        val verified = ModelFileVerifier.verify(file, expectedBytes, expectedHash, licenseAccepted = true)
        val result = LiteRtLmLoadProbe().loadAndRelease(verified)
        assertEquals("0.9.0", result.runtimeVersion)
        assertEquals("CPU", result.backend)
        assertTrue(result.manualToolCallingConfigured)
    }
}

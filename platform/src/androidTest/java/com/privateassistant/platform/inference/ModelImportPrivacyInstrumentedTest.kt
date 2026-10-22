package com.privateassistant.platform.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Wrong SAF selections remain untrusted until byte verification; fixtures are synthetic only. */
@RunWith(AndroidJUnit4::class)
class ModelImportPrivacyInstrumentedTest {
    @Test fun wrongModelInputNeverAppearsAsPlaintextWhileStreamingOrAfterRejection() = runBlocking {
        val canary = "SYNTHETIC_PRIVATE_MODEL_IMPORT_CANARY_729451"
        val approved = ByteArray(180000) { 73 }
        val selected = approved.copyOf().also { canary.toByteArray(Charsets.US_ASCII).copyInto(it) }
        val digest = MessageDigest.getInstance("SHA-256").digest(approved)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val record = JSONObject().apply {
            put("id", "synthetic-model"); put("displayName", "Synthetic privacy fixture")
            put("assessment", "experimental"); put("revision", "2".repeat(40))
            put("fileName", "synthetic.litertlm"); put("byteCount", approved.size); put("sha256", digest)
            put("downloadUrl", "https://example.invalid/synthetic-fixture")
            put("license", JSONObject().put("identifier", "synthetic-test")
                .put("termsUrl", "https://example.invalid/licence").put("requiresAcceptance", true))
            put("runtimeVersion", "0.9.0"); put("format", "litertlm")
            put("contextTokens", 512); put("minimumRamBytes", 1)
            put("evaluatedLanguages", JSONArray()); put("evaluatedTasks", JSONArray())
            put("backends", JSONArray().put("CPU"))
        }
        val catalogue = ModelCatalogue.parse(JSONObject().put("schemaVersion", 1)
            .put("runtime", JSONObject().put("id", "litert-lm").put("version", "0.9.0").put("format", "litertlm"))
            .put("models", JSONArray().put(record)).toString())
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir.canonicalFile,
            "model-import-privacy-${UUID.randomUUID()}")
        fun assertCanaryAbsent() {
            var total = 0L
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                assertTrue("Synthetic probe must remain bounded", file.length() <= 512 * 1024)
                total += file.length()
                assertTrue("Synthetic probe total must remain bounded", total <= 1024 * 1024)
                assertFalse("Unverified selected input must not be persisted as private plaintext",
                    file.readBytes().toString(Charsets.ISO_8859_1).contains(canary))
            }
        }
        try {
            val store = InstalledModelStore(root, catalogue)
            var progressChecks = 0
            try {
                store.install("synthetic-model", ByteArrayInputStream(selected), licenseAccepted = true,
                    onProgress = { _, _ -> progressChecks++; assertCanaryAbsent() })
                fail("Wrong model bytes must not publish an install")
            } catch (failure: ModelInstallException) {
                assertEquals(ModelInstallFailure.INTEGRITY, failure.reason)
            }
            assertTrue("The test must inspect files while real chunks are being copied", progressChecks >= 2)
            assertCanaryAbsent()
            assertEquals(InstalledModelStatus.ABSENT, store.installedModels().single().status)
            assertTrue("Rejected input staging must be removed", root.listFiles()!!.isEmpty())
        } finally {
            root.deleteRecursively(); selected.fill(0)
        }
    }
}

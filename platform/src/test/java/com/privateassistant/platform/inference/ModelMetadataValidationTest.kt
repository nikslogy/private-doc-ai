package com.privateassistant.platform.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Metadata safety only. Tiny synthetic identities do not establish model/runtime compatibility. */
class ModelMetadataValidationTest {
    private fun model(
        id: String = "synthetic-test", revision: String = "1".repeat(40),
        fileName: String = "synthetic.litertlm", bytes: Long = 1,
        sha256: String = "a".repeat(64), terms: String = "https://example.invalid/licence",
        download: String = "https://example.invalid/synthetic-fixture",
        contextTokens: Int = 512, backends: List<String> = listOf("CPU"),
        languages: List<String> = emptyList(), tasks: List<String> = emptyList(), experimental: Boolean = true,
    ) = RegisteredModel(id, revision, fileName, bytes, sha256, "synthetic-test", terms, true,
        contextTokens, 1, languages, tasks, backends, downloadUrl = download, experimental = experimental)

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Unsafe metadata must fail before storage or runtime work") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun pathLikeModelIdentifiersAndFilenamesAreRejected() {
        listOf("../model", "model/path", "model\\path", "", "-model").forEach { id -> rejected { model(id = id) } }
        listOf("../model.litertlm", "a/b.litertlm", "a\\b.litertlm", "model.gguf", ".litertlm").forEach { file ->
            rejected { model(fileName = file) }
        }
    }

    @Test fun mutableRevisionsAndNonExactHashesAreRejected() {
        listOf("main", "latest", "A".repeat(40), "1".repeat(39)).forEach { revision -> rejected { model(revision = revision) } }
        listOf("a".repeat(63), "A".repeat(64), "g".repeat(64), "").forEach { hash -> rejected { model(sha256 = hash) } }
    }

    @Test fun modelByteAndContextBudgetsAreEnforced() {
        listOf(0L, -1L, RegisteredModel.MAX_MODEL_BYTES + 1).forEach { bytes -> rejected { model(bytes = bytes) } }
        listOf(0, 127, 32769).forEach { context -> rejected { model(contextTokens = context) } }
        assertEquals(RegisteredModel.MAX_MODEL_BYTES, model(bytes = RegisteredModel.MAX_MODEL_BYTES).byteCount)
        assertEquals(128, model(contextTokens = 128).contextTokens)
    }

    @Test fun publicLinksCannotEmbedCredentialsFragmentsOrAlternateSchemes() {
        listOf("http://example.invalid/model", "file:///private/model", "content://provider/model",
            "https://user:secret@example.invalid/model", "https://example.invalid:8443/model",
            "https://example.invalid/model#fragment", "https://example.invalid/model\n").forEach { url ->
            assertFalse(url, validPublicUrl(url))
            rejected { model(terms = url) }
            rejected { model(download = url) }
        }
    }

    @Test fun exactImmutableDownloadQueryRemainsUsable() {
        val url = "https://example.invalid/resolve/${"1".repeat(40)}/synthetic.litertlm?download=true"
        assertTrue(validPublicUrl(url))
        assertEquals(url, model(download = url).downloadUrl)
    }

    @Test fun unmeasuredLanguageTaskAndValidationClaimsCannotEnableQa() {
        rejected { model(experimental = false) }
        rejected { model(languages = listOf("en")) }
        rejected { model(tasks = listOf("document-qa")) }
        assertTrue(model().experimental)
        assertTrue(model().evaluatedTasks.isEmpty())
    }

    @Test fun backendDeclarationsStayWithinReviewedRuntimeKinds() {
        listOf(emptyList(), listOf("REMOTE"), listOf("CPU", "CPU")).forEach { backends ->
            rejected { model(backends = backends) }
        }
        assertEquals(listOf("CPU"), model().backends)
    }

    @Test fun callerMutationCannotAlterReviewedBackendMetadata() {
        val backends = mutableListOf("CPU")
        val reviewed = model(backends = backends)
        backends.clear(); backends.add("REMOTE")
        assertEquals(listOf("CPU"), reviewed.backends)
        try { (reviewed.backends as MutableList<String>).add("REMOTE"); fail("Metadata must be immutable") }
        catch (_: UnsupportedOperationException) { }
    }
}

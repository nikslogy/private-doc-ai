package com.privateassistant.platform.inference

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Tiny synthetic bytes test storage only; no synthetic artifact is passed to a native engine. */
@RunWith(AndroidJUnit4::class)
class InstalledModelStoreInstrumentedTest {
    private val bytes = "SYNTHETIC-PUBLIC-MODEL-FIXTURE".toByteArray()
    private fun record(id: String = "synthetic", content: ByteArray = bytes) = JSONObject().apply {
        put("id", id); put("displayName", "Synthetic storage fixture"); put("assessment", "experimental")
        put("revision", "1".repeat(40)); put("fileName", "$id.litertlm"); put("byteCount", content.size)
        put("sha256", MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it.toInt() and 255) })
        put("downloadUrl", "https://example.invalid/synthetic-fixture")
        put("license", JSONObject().put("identifier", "synthetic-test").put("termsUrl", "https://example.invalid/licence").put("requiresAcceptance", true))
        put("runtimeVersion", "0.9.0"); put("format", "litertlm"); put("contextTokens", 512)
        put("minimumRamBytes", 1); put("evaluatedLanguages", JSONArray()); put("evaluatedTasks", JSONArray())
        put("backends", JSONArray().put("CPU"))
    }
    private fun catalogue(vararg records: JSONObject) = ModelCatalogue.parse(JSONObject()
        .put("schemaVersion", 1).put("runtime", JSONObject().put("id", "litert-lm").put("version", "0.9.0").put("format", "litertlm"))
        .put("models", JSONArray(records.toList())).toString())
    private fun root(): File = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir.canonicalFile,
        "model-store-test-${UUID.randomUUID()}")

    @Test fun installReopenAndDuplicateUseOnlyVerifiedBytes() = runBlocking {
        val root = root(); val catalogue = catalogue(record())
        try {
            val store = InstalledModelStore(root, catalogue)
            store.install("synthetic", ByteArrayInputStream(bytes), true)
            assertEquals(InstalledModelStatus.VERIFIED, InstalledModelStore(root, catalogue).installedModels().single().status)
            store.withVerifiedModel("synthetic") { _, artifact -> assertArrayEquals(bytes, artifact.file.readBytes()) }
            store.install("synthetic", object : InputStream() { override fun read(): Int = error("Duplicate must not read input") }, true)
            assertEquals(1, root.listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }
    @Test fun wrongHashRejectsAndCleansOnlyOwnedStaging() = runBlocking {
        val root = root().apply { mkdir() }; val unknown = File(root, "keep-unknown").apply { writeText("public fixture") }
        try {
            val store = InstalledModelStore(root, catalogue(record()))
            try { store.install("synthetic", ByteArrayInputStream(bytes.map { (it.toInt() xor 1).toByte() }.toByteArray()), true); fail() }
            catch (e: ModelInstallException) { assertEquals(ModelInstallFailure.INTEGRITY, e.reason) }
            assertEquals(InstalledModelStatus.ABSENT, store.installedModels().single().status)
            assertEquals("public fixture", unknown.readText()); assertEquals(1, root.listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }
    @Test fun truncatedAndOversizedInputsCannotPublish() = runBlocking {
        val root = root()
        try {
            val store = InstalledModelStore(root, catalogue(record()))
            for (input in listOf(bytes.copyOf(bytes.size - 1), bytes + 0)) {
                try { store.install("synthetic", ByteArrayInputStream(input), true); fail() }
                catch (e: ModelInstallException) { assertEquals(ModelInstallFailure.INTEGRITY, e.reason) }
                assertEquals(InstalledModelStatus.ABSENT, store.installedModels().single().status)
                assertTrue(root.listFiles()!!.isEmpty())
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun licenceAndCatalogueIdentityAreRequiredBeforeStorage() = runBlocking {
        val root = root()
        try {
            val store = InstalledModelStore(root, catalogue(record()))
            try { store.install("synthetic", ByteArrayInputStream(bytes), false); fail() }
            catch (e: ModelInstallException) { assertEquals(ModelInstallFailure.LICENCE_REQUIRED, e.reason) }
            try { store.install("unknown", ByteArrayInputStream(bytes), true); fail() }
            catch (e: ModelInstallException) { assertEquals(ModelInstallFailure.UNKNOWN_MODEL, e.reason) }
            assertFalse(root.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun cancelledNewInstallPreservesAnExistingVerifiedModel() = runBlocking {
        val root = root(); val large = ByteArray(140000) { (it % 251).toByte() }
        try {
            val store = InstalledModelStore(root, catalogue(record(), record("other", large)))
            store.install("synthetic", ByteArrayInputStream(bytes), true)
            var cancel = false
            try { store.install("other", ByteArrayInputStream(large), true, { cancel }, { _, _ -> cancel = true }); fail() }
            catch (_: kotlinx.coroutines.CancellationException) { }
            val states = store.installedModels().associate { it.modelId to it.status }
            assertEquals(InstalledModelStatus.VERIFIED, states["synthetic"])
            assertEquals(InstalledModelStatus.ABSENT, states["other"])
            assertEquals(1, root.listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }
    @Test fun tamperingFailsClosedUntilExplicitRemoval() = runBlocking {
        val root = root()
        try {
            val store = InstalledModelStore(root, catalogue(record()))
            store.install("synthetic", ByteArrayInputStream(bytes), true)
            val file = File(root.listFiles()!!.single(), "model.litertlm")
            Os.chmod(file.path, 384); file.writeBytes(ByteArray(bytes.size))
            assertEquals(InstalledModelStatus.INVALID, store.installedModels().single().status)
            try { store.withVerifiedModel("synthetic") { _, _ -> fail("Tampered model must not receive a lease") }; fail() }
            catch (e: ModelInstallException) { assertEquals(ModelInstallFailure.INTEGRITY, e.reason) }
            assertArrayEquals(ByteArray(bytes.size), file.readBytes())
            store.remove("synthetic"); store.install("synthetic", ByteArrayInputStream(bytes), true)
            assertEquals(InstalledModelStatus.VERIFIED, store.installedModels().single().status)
        } finally { root.deleteRecursively() }
    }
    @Test fun symbolicModelPathCannotEscapePrivateSlot() = runBlocking {
        val root = root(); val outside = File(root.parentFile, "synthetic-model-outside-${UUID.randomUUID()}")
        var link: File? = null
        try {
            outside.writeBytes(bytes)
            val store = InstalledModelStore(root, catalogue(record()))
            store.install("synthetic", ByteArrayInputStream(bytes), true)
            link = File(root.listFiles()!!.single(), "model.litertlm")
            assertTrue(link.delete()); Os.symlink(outside.path, link.path)
            assertEquals(InstalledModelStatus.INVALID, store.installedModels().single().status)
            try { store.withVerifiedModel("synthetic") { _, _ -> fail() }; fail() }
            catch (e: ModelInstallException) { assertEquals(ModelInstallFailure.INTEGRITY, e.reason) }
            assertArrayEquals(bytes, outside.readBytes())
        } finally { link?.delete(); root.deleteRecursively(); outside.delete() }
    }
    @Test fun leaseCannotBeUsedAfterItsScopeReturns() = runBlocking {
        val root = root()
        try {
            val store = InstalledModelStore(root, catalogue(record()))
            store.install("synthetic", ByteArrayInputStream(bytes), true)
            val escaped = store.withVerifiedModel("synthetic") { _, token -> token }
            try { escaped.file; fail("Expired lease must reject access") } catch (_: IllegalStateException) { }
        } finally { root.deleteRecursively() }
    }
    @Test fun leasedNativeWorkExcludesRemovalAcrossSuspension() = runBlocking {
        val root = root()
        try {
            val store = InstalledModelStore(root, catalogue(record()))
            store.install("synthetic", ByteArrayInputStream(bytes), true)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val leased = async(Dispatchers.Default) { store.withVerifiedModel("synthetic") { _, artifact ->
                entered.complete(Unit); release.await(); assertArrayEquals(bytes, artifact.file.readBytes())
            } }
            entered.await()
            val deletionStarted = CompletableDeferred<Unit>()
            val deleting = async(Dispatchers.Default) { deletionStarted.complete(Unit); store.remove("synthetic") }
            deletionStarted.await(); yield(); assertFalse(deleting.isCompleted)
            release.complete(Unit); leased.await(); deleting.await()
            assertEquals(InstalledModelStatus.ABSENT, store.installedModels().single().status)
        } finally { root.deleteRecursively() }
    }
    @Test fun restartReconcilesRecognizedStagingAndKeepsUnknownEntries() = runBlocking {
        val root = root().apply { mkdir() }
        try {
            val stage = File(root, ".staging-${UUID.randomUUID()}").apply { mkdir() }
            File(stage, "model.litertlm").writeBytes(bytes)
            val unknown = File(root, "unrecognized").apply { writeText("keep") }
            InstalledModelStore(root, catalogue(record())).installedModels()
            assertFalse(stage.exists()); assertEquals("keep", unknown.readText())
        } finally { root.deleteRecursively() }
    }
}

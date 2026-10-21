package com.privateassistant.platform.vault

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID

/**
 * Opt-in real-authentication probe. Authenticate with the device credential immediately before
 * running this class with -e runAuthenticatedVaultLifecycle true. Authentication failures fail
 * the probe; the test never weakens the production Keystore policy or supplies a substitute key.
 */
@RunWith(AndroidJUnit4::class)
class VaultLifecycleInstrumentedTest {
    @Test fun authenticatedOriginalDedupReopenAndInterruptedPublicationRecovery() = runBlocking {
        assumeTrue(
            "Opt in after real device authentication; see CRYPTO_DESIGN.md",
            InstrumentationRegistry.getArguments().getString("runAuthenticatedVaultLifecycle") == "true",
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        assertTrue("Configure a secure device screen lock", base.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        val testPackage = "${base.packageName}.lifecycle-test-${UUID.randomUUID()}"
        val storage = File(base.noBackupFilesDir, "lifecycle-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = storage
            override fun getPackageName(): String = testPackage
        }
        val alias = "$testPackage.vault.root.v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val source = Uri.parse("android.resource://${instrumentation.context.packageName}/raw/vault_lifecycle_canary")
        val expected = base.contentResolver.openInputStream(source)!!.use { it.readBytes() }
        val vaultDirectory = File(storage, "private-vault-v1")
        var controller = VaultController(context)
        try {
            controller.unlock()
            val original = controller.importDocument(source, "text/plain", "PRIVATE_TITLE_CANARY.txt")
            val duplicate = controller.importDocument(source, "text/plain", "Different title.txt")
            assertEquals(original.id, duplicate.id)
            assertEquals(original.revisionId, duplicate.revisionId)
            assertEquals(1, controller.documents().size)
            assertEquals(VaultIndexState.INDEXED, original.indexState)
            assertEquals(1, original.pageCount)
            val evidence = controller.pageEvidence(original.id).single()
            assertEquals(expected.toString(Charsets.UTF_8), evidence.text)
            val hit = controller.searchDocuments("PRIVATE_VAULT_LIFECYCLE_CANARY").single()
            assertEquals(original.id, hit.document.id)
            assertEquals(original.revisionId, hit.document.revisionId)
            assertEquals(evidence.text.substring(hit.startOffset, hit.endOffset), hit.quote)
            // Explicit native reindex preserves immutable identity and exact original bytes.
            controller.indexDocument(original.id)
            assertEquals(evidence, controller.pageEvidence(original.id).single())
            assertArrayEquals(expected, ByteArrayOutputStream().also { controller.copyOriginalTo(original.id, it) }.toByteArray())
            controller.lock()
            try {
                controller.documents()
                fail("A locked controller must not return metadata")
            } catch (_: VaultLockedException) {
                // Expected.
            }
            try {
                controller.pageEvidence(original.id)
                fail("A locked controller must not return evidence")
            } catch (_: VaultLockedException) {
                // Expected.
            }
            try {
                controller.searchDocuments("PRIVATE_VAULT_LIFECYCLE_CANARY")
                fail("A locked controller must not return search excerpts")
            } catch (_: VaultLockedException) {
                // Expected.
            }

            val secrets = VaultCrypto(context, vaultDirectory).open()
            secrets.databaseKey.fill(0)
            val orphanRevision = UUID.randomUUID().toString()
            val orphan = File(vaultDirectory, "objects/$orphanRevision.blob")
            orphan.outputStream().use { encrypted ->
                secrets.streamingAead.newEncryptingStream(
                    encrypted,
                    VaultCrypto.objectAad(secrets.vaultId, UUID.randomUUID().toString(), orphanRevision),
                ).use { it.write(expected) }
            }
            val interrupted = File(vaultDirectory, "staging/${UUID.randomUUID()}.part")
                .apply { writeBytes(orphan.readBytes().let { it.copyOf(it.size / 2) }) }
            val unrecognized = File(vaultDirectory, "objects/keep-unrecognized")
                .apply { writeBytes(orphan.readBytes()) }
            controller = VaultController(context)
            controller.unlock()
            assertFalse("Uncommitted ciphertext must be reconciled", orphan.exists())
            assertFalse("Interrupted staging ciphertext must be reconciled", interrupted.exists())
            assertTrue("Recovery must not remove unrecognized files", unrecognized.exists())
            assertEquals(original, controller.documents().single())
            assertEquals(evidence, controller.pageEvidence(original.id).single())
            assertEquals(original.id, controller.searchDocuments("PRIVATE_VAULT_LIFECYCLE_CANARY").single().document.id)
            assertArrayEquals(expected, ByteArrayOutputStream().also { controller.copyOriginalTo(original.id, it) }.toByteArray())

            // A missing referenced object must reject recovery before it removes orphan ciphertext.
            controller.lock()
            val originalFile = File(vaultDirectory, "objects/${original.revisionId}.blob")
            val held = File(vaultDirectory, "held-ciphertext")
            assertTrue(originalFile.renameTo(held))
            val preservedOrphan = File(vaultDirectory, "objects/${UUID.randomUUID()}.blob")
                .apply { writeBytes(unrecognized.readBytes()) }
            controller = VaultController(context)
            try {
                controller.unlock()
                fail("Missing referenced ciphertext must fail closed")
            } catch (_: VaultIntegrityException) {
                assertTrue("Failing recovery must preserve orphan ciphertext", preservedOrphan.exists())
            }
            assertTrue(held.renameTo(originalFile))
            controller.unlock()
            assertEquals(original, controller.documents().single())

            // Scan all app-private artifacts while open; no original or title canary belongs there.
            vaultDirectory.walkTopDown().filter(File::isFile).forEach { file ->
                val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
                assertFalse("Original plaintext leaked to ${file.name}", "PRIVATE_VAULT_LIFECYCLE_CANARY" in bytes)
                assertFalse("Metadata plaintext leaked to ${file.name}", "PRIVATE_TITLE_CANARY" in bytes)
            }
            controller.deleteDocument(original.id)
            assertTrue(controller.documents().isEmpty())
            assertTrue(controller.searchDocuments("PRIVATE_VAULT_LIFECYCLE_CANARY").isEmpty())
            assertFalse(originalFile.exists())
            assertArrayEquals(expected, base.contentResolver.openInputStream(source)!!.use { it.readBytes() })
        } finally {
            controller.lock()
            expected.fill(0)
            store.deleteEntry(alias)
            storage.deleteRecursively()
        }
    }
}

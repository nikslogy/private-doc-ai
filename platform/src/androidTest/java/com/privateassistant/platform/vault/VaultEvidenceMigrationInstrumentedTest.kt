package com.privateassistant.platform.vault

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.privateassistant.core.DocumentId
import com.privateassistant.core.EvidenceValidator
import com.privateassistant.core.RevisionId
import com.privateassistant.core.RevisionRef
import com.privateassistant.core.RevisionText
import com.privateassistant.core.PageText
import com.privateassistant.core.search.GroundedLexicalSearch
import com.privateassistant.core.search.LiteralSearchQuery
import com.privateassistant.platform.documents.DocumentIndexResult
import com.privateassistant.platform.documents.ExtractionMethod
import com.privateassistant.platform.documents.IndexedPage
import com.privateassistant.platform.documents.PageIndexState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.security.SecureRandom
import java.util.UUID

/** Actual SQLCipher/Room integration tests. Authored, not a substitute for a device result. */
@RunWith(AndroidJUnit4::class)
class VaultEvidenceMigrationInstrumentedTest {
    @Test fun encryptedVersionOneMigrationPreservesOriginalIdentityAndRequiresExplicitIndexing() {
        withStorage { context, directory, path, key ->
            val document = document()
            // The migration has no reason to read or rewrite an immutable original object.
            val opaqueOriginal = File(directory, "${document.revisionId}.blob")
            val ciphertextCanary = ByteArray(79).also(SecureRandom()::nextBytes)
            opaqueOriginal.writeBytes(ciphertextCanary)
            val helper = SupportOpenHelperFactory(key.copyOf()).create(
                SupportSQLiteOpenHelper.Configuration.builder(context).name(path.absolutePath)
                    .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL("CREATE TABLE IF NOT EXISTS documents (id TEXT NOT NULL, displayName TEXT NOT NULL, " +
                                "mimeType TEXT NOT NULL, sizeBytes INTEGER NOT NULL, importedAtEpochMillis INTEGER NOT NULL, " +
                                "sha256 TEXT NOT NULL, revisionId TEXT NOT NULL, PRIMARY KEY(id))")
                            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_documents_sha256 ON documents(sha256)")
                            db.execSQL("INSERT INTO documents VALUES (?, ?, ?, ?, ?, ?, ?)", arrayOf(
                                document.id, document.displayName, document.mimeType, document.sizeBytes,
                                document.importedAtEpochMillis, document.sha256, document.revisionId,
                            ))
                        }
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected test upgrade")
                    }).build(),
            )
            helper.use { it.writableDatabase }
            database(context, path, key).use { db ->
                val migrated = db.documents().all().single()
                assertEquals(document.id, migrated.id)
                assertEquals(document.revisionId, migrated.revisionId)
                assertEquals(document.sha256, migrated.sha256)
                assertEquals(document.displayName, migrated.displayName)
                assertEquals(0, migrated.pageCount)
                assertEquals(VaultIndexState.NOT_INDEXED.name, migrated.indexState)
                assertTrue(db.evidence().pages(document.id).isEmpty())
                assertTrue(db.evidence().searchIds(LiteralSearchQuery.parse("PRIVATE").ftsMatch!!, 50).isEmpty())
            }
            assertArrayEquals(ciphertextCanary, opaqueOriginal.readBytes())
            assertEncrypted(directory, "PRIVATE_MIGRATION_TITLE")
        }
    }

    @Test fun encryptedUnicodeSearchIsLiteralCitedTransactionalAndDeletedWithEvidence() {
        withStorage { context, directory, path, key ->
            val document = document()
            val text = "PRIVATE_EVIDENCE_CANARY विमा प्रीमियम १,२३४.५० Policy 00123 MH-01-AB-0007 and OR literal."
            val evidence = page(document, text)
            database(context, path, key).use { db ->
                db.runInTransaction {
                    db.documents().insert(document)
                    VaultEvidenceStore.replace(db, document, listOf(evidence))
                }
                val query = LiteralSearchQuery.parse("विमा प्रीमियम 00123")
                val row = db.evidence().findPage(db.evidence().searchIds(query.ftsMatch!!, 50).single())!!
                val source = RevisionText(RevisionRef(DocumentId(document.id), RevisionId(document.revisionId)), listOf(PageText(1, row.text)))
                val hit = GroundedLexicalSearch.findPage(query, source, 1)
                assertNotNull(hit)
                assertTrue(EvidenceValidator().validate(hit!!.evidence, source).isValid)
                assertEquals(text.substring(hit.startOffset, hit.endOffset), hit.quote)
                assertTrue(db.evidence().searchIds(LiteralSearchQuery.parse("123").ftsMatch!!, 50).isEmpty())
                assertTrue(db.evidence().searchIds(LiteralSearchQuery.parse("missing OR 00123").ftsMatch!!, 50).isEmpty())
                assertEquals(1, db.evidence().searchIds(LiteralSearchQuery.parse("MH-01-AB-0007").ftsMatch!!, 50).size)
                assertEquals(evidence, VaultEvidenceStore.read(db, db.documents().find(document.id)!!).single())
                // Abort after replacing all rows: SQLite must restore old evidence AND its FTS row.
                try {
                    db.runInTransaction {
                        VaultEvidenceStore.replace(db, document, listOf(page(document, "replacement")))
                        throw RollbackProbe()
                    }
                } catch (_: RollbackProbe) {
                    // Expected transaction rollback.
                }
                assertEquals(text, db.evidence().pages(document.id).single().text)
                assertEquals(1, db.evidence().searchIds(query.ftsMatch!!, 50).size)
                assertTrue(db.evidence().searchIds(LiteralSearchQuery.parse("replacement").ftsMatch!!, 50).isEmpty())
            }
            assertEncrypted(directory, "PRIVATE_EVIDENCE_CANARY")
            assertEncrypted(directory, LiteralSearchQuery.indexTerms("PRIVATE_EVIDENCE_CANARY"))
            database(context, path, key).use { db ->
                assertEquals(text, VaultEvidenceStore.read(db, db.documents().find(document.id)!!).single().text)
                db.runInTransaction {
                    db.evidence().deleteSearch(document.id)
                    db.documents().delete(document.id)
                }
                assertTrue(db.evidence().pages(document.id).isEmpty())
                assertTrue(db.evidence().searchIds(LiteralSearchQuery.parse("00123").ftsMatch!!, 50).isEmpty())
                db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM page_spans").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0L, cursor.getLong(0))
                }
            }
        }
    }

    @Test fun failedPageRetainsPriorEvidenceAndInterruptedIndexRequiresRetry() {
        withStorage { context, _, path, key ->
            val document = document()
            val previous = page(document, "Retain exact premium 00019")
            val failed = DocumentIndexResult(listOf(IndexedPage(1, "", null, ExtractionMethod.OCR_LATIN,
                "test-failure-v1", state = PageIndexState.FAILED, errorCode = "OCR_FAILED")))
            val merged = VaultEvidenceStore.indexed(document, failed, listOf(previous))
            assertEquals(previous.text, merged.single().text)
            assertEquals(VaultPageState.FAILED, merged.single().state)
            assertEquals(VaultIndexState.PARTIAL, VaultEvidenceStore.indexState(merged))
            database(context, path, key).use { db ->
                db.runInTransaction {
                    db.documents().insert(document)
                    VaultEvidenceStore.replace(db, document, merged)
                    db.documents().updateIndex(document.id, 1, VaultIndexState.INDEXING.name)
                }
            }
            database(context, path, key).use { db ->
                db.documents().recoverInterruptedIndexing()
                assertEquals(VaultIndexState.FAILED.name, db.documents().find(document.id)!!.indexState)
                assertEquals(previous.text, db.evidence().pages(document.id).single().text)
            }
        }
    }

    private class RollbackProbe : RuntimeException()

    private fun page(document: DocumentEntity, text: String) = VaultPageEvidence(
        document.id, document.revisionId, 1, text, null, ExtractionMethod.UTF8_TEXT,
        "utf8-strict-v1", VaultPageState.READY,
        listOf(com.privateassistant.platform.documents.SourceTextSpan(0, text.length, null,
            com.privateassistant.platform.documents.SourceSpanKind.BLOCK)),
    )

    private fun document() = DocumentEntity(UUID.randomUUID().toString(), "PRIVATE_MIGRATION_TITLE.txt",
        "text/plain", 79, 1, "a".repeat(64), UUID.randomUUID().toString())

    private fun database(context: Context, path: File, key: ByteArray): VaultDatabase =
        Room.databaseBuilder(context, VaultDatabase::class.java, path.absolutePath)
            .openHelperFactory(SupportOpenHelperFactory(key.copyOf()))
            .addMigrations(VaultDatabase.MIGRATION_1_2)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .addCallback(VaultDatabaseSecurity)
            .allowMainThreadQueries().build()

    private fun assertEncrypted(directory: File, canary: String) {
        directory.listFiles().orEmpty().filter(File::isFile).forEach { file ->
            assertFalse("Private index leaked in ${file.name}", file.readBytes().toString(Charsets.ISO_8859_1).contains(canary))
            assertFalse("SQLite plaintext header in ${file.name}", file.readBytes().take(16).toByteArray()
                .contentEquals("SQLite format 3\u0000".toByteArray()))
        }
    }

    private fun withStorage(block: (Context, File, File, ByteArray) -> Unit) {
        System.loadLibrary("sqlcipher")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "evidence-test-${UUID.randomUUID()}").apply { mkdirs() }
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        try {
            block(context, directory, File(directory, "metadata.db"), key)
        } finally {
            key.fill(0)
            directory.deleteRecursively()
        }
    }
}

package com.privateassistant.platform.vault

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SqlCipherInstrumentedTest {
    @Test fun productionOpenPolicyEnablesAllSecuritySettings() = withSyntheticDatabase { db ->
        val connection = db.openHelper.writableDatabase
        for ((pragma, expected) in listOf("secure_delete" to 1, "temp_store" to 2, "synchronous" to 2)) {
            connection.query("PRAGMA $pragma").use { result ->
                assertTrue("Missing setting result", result.moveToFirst())
                assertEquals(expected, result.getInt(0))
            }
        }
        connection.query("PRAGMA cipher_memory_security").use { result ->
            assertTrue(result.moveToFirst())
            assertEquals("1", result.getString(0))
        }
        assertEquals(0, db.documents().count())
    }

    @Test fun execSqlRejectsResultReturningSecurityPragma() = withSyntheticDatabase { db ->
        try {
            db.openHelper.writableDatabase.execSQL("PRAGMA secure_delete = ON")
            fail("The old API must reproduce SQLCipher's result-row rejection")
        } catch (failure: SQLiteException) {
            assertTrue(failure.message.orEmpty().contains("Queries can be performed"))
        }
        // Its failure must not make the encrypted database unusable.
        assertEquals(0, db.documents().count())
    }

    @Test fun metadataReopensWithSecretAndIsNotPlaintextOnDisk() {
        System.loadLibrary("sqlcipher")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "cipher-test-${UUID.randomUUID()}").apply { mkdirs() }
        val path = File(directory, "metadata.db")
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val canary = "PRIVATE_CANARY_NAME_91f5c6"
        try {
            withDatabase(context, path, key) { db ->
                db.documents().insert(DocumentEntity(UUID.randomUUID().toString(), canary, "text/plain", 13, 1, "a".repeat(64)))
                assertEquals(canary, db.documents().all().single().displayName)
            }
            directory.listFiles().orEmpty().filter { it.isFile }.forEach { file ->
                val bytes = file.readBytes()
                assertFalse("SQLite plaintext header in ${file.name}", bytes.take(16).toByteArray().contentEquals("SQLite format 3\u0000".toByteArray()))
                assertFalse("Private title leaked in ${file.name}", bytes.toString(Charsets.ISO_8859_1).contains(canary))
            }
            withDatabase(context, path, key) { db ->
                assertEquals(canary, db.documents().all().single().displayName)
            }
            val wrong = ByteArray(32).also(SecureRandom()::nextBytes)
            try {
                try {
                    withDatabase(context, path, wrong) { it.documents().all() }
                } catch (_: Exception) {
                    return
                }
                fail("Wrong SQLCipher key must reject the database")
            } finally {
                wrong.fill(0)
            }
        } finally {
            key.fill(0)
            directory.deleteRecursively()
        }
    }

    private fun database(context: Context, file: File, key: ByteArray): VaultDatabase =
        Room.databaseBuilder(context, VaultDatabase::class.java, file.absolutePath)
            .openHelperFactory(SupportOpenHelperFactory(key.copyOf()))
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .addCallback(VaultDatabaseSecurity)
            .allowMainThreadQueries()
            .build()

    private fun withSyntheticDatabase(block: (VaultDatabase) -> Unit) {
        System.loadLibrary("sqlcipher")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "open-test-${UUID.randomUUID()}")
        check(directory.mkdir())
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        try {
            withDatabase(context, File(directory, "metadata.db"), key, block)
        } finally {
            key.fill(0)
            directory.deleteRecursively()
        }
    }

    private fun <T> withDatabase(context: Context, file: File, key: ByteArray, block: (VaultDatabase) -> T): T {
        val database = database(context, file, key)
        return try {
            block(database)
        } finally {
            database.close()
        }
    }
}

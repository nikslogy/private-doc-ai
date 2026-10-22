package com.privateassistant.platform.vault

import android.content.Context
import android.net.Uri
import android.system.Os
import androidx.room.Room
import com.google.crypto.tink.StreamingAead
import com.privateassistant.core.DocumentMimeType
import com.privateassistant.core.DocumentId
import com.privateassistant.core.ImportRequest
import com.privateassistant.core.ImportValidator
import com.privateassistant.core.PageText
import com.privateassistant.core.RevisionId
import com.privateassistant.core.RevisionRef
import com.privateassistant.core.RevisionText
import com.privateassistant.core.search.GroundedLexicalSearch
import com.privateassistant.core.search.LiteralSearchQuery
import com.privateassistant.platform.documents.DocumentParseException
import com.privateassistant.platform.documents.MemoryDocumentParser
import com.privateassistant.platform.documents.OfflineDocumentIndexer
import com.privateassistant.platform.documents.OcrScript
import com.privateassistant.platform.documents.ReviewedOcrModels
import com.privateassistant.platform.documents.TesseractOcrEngine
import com.privateassistant.platform.documents.ParseFailure
import com.privateassistant.platform.documents.ParsedDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * Owns one local vault. Call [unlock] only after successful device authentication. Call [lock]
 * when the UI leaves its permitted foreground session, and never cache returned metadata on disk.
 */
class VaultController(context: Context) {
    private val applicationContext = context.applicationContext
    private val ocrModels = ReviewedOcrModels(applicationContext)
    /** Public model assets are bundled; recognition still verifies every installed byte. */
    val ocrAvailable: Boolean get() = ocrModels.available
    private val directory = File(applicationContext.noBackupFilesDir, "private-vault-v1")
    private val objectDirectory = File(directory, "objects")
    private val stagingDirectory = File(directory, "staging")
    private val sessionMonitor = Any()
    private var epoch = 0L
    @Volatile private var session: Session? = null
    private val mutableState = MutableStateFlow<VaultState>(VaultState.Locked)
    val state: StateFlow<VaultState> = mutableState.asStateFlow()

    private class Session(
        val database: VaultDatabase,
        val streamingAead: StreamingAead,
        val vaultId: String,
    ) {
        val revoked = AtomicBoolean(false)
    }

    suspend fun unlock() = withContext(Dispatchers.IO) {
        operations.withLock {
            synchronized(sessionMonitor) { session }?.let { return@withLock }
            val openingEpoch = synchronized(sessionMonitor) { epoch }
            var openedDatabase: VaultDatabase? = null
            try {
                val secrets = VaultCrypto(applicationContext, directory).open()
                try {
                    System.loadLibrary("sqlcipher")
                    net.zetetic.database.Logger.setTarget(net.zetetic.database.NoopTarget())
                    val factory = SupportOpenHelperFactory(secrets.databaseKey)
                    val database = Room.databaseBuilder(
                        applicationContext,
                        VaultDatabase::class.java,
                        File(directory, "metadata.db").absolutePath,
                    )
                        .openHelperFactory(factory)
                        .addMigrations(VaultDatabase.MIGRATION_1_2)
                        .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
                        .addCallback(VaultDatabaseSecurity)
                        .build()
                    openedDatabase = database
                    // Force SQLCipher to consume the passphrase before wiping our byte array.
                    database.openHelper.writableDatabase
                    val newSession = Session(database, secrets.streamingAead, secrets.vaultId)
                    recoverCiphertext(newSession)
                    database.documents().recoverInterruptedIndexing()
                    val count = database.documents().count()
                    currentCoroutineContext().ensureActive()
                    synchronized(sessionMonitor) {
                        if (epoch != openingEpoch) throw VaultLockedException()
                        session = newSession
                        mutableState.value = VaultState.Unlocked(count)
                        openedDatabase = null
                    }
                } finally {
                    secrets.databaseKey.fill(0)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: VaultLockedException) {
                throw failure
            } catch (failure: LinkageError) {
                val safe = VaultException("The encrypted database is unavailable on this device.", failure)
                synchronized(sessionMonitor) {
                    if (epoch == openingEpoch) mutableState.value = VaultState.Error(safe.message.orEmpty())
                }
                throw safe
            } catch (failure: Exception) {
                val safe = if (failure is VaultException) failure else VaultIntegrityException(failure)
                synchronized(sessionMonitor) {
                    if (epoch == openingEpoch) {
                        mutableState.value = VaultState.Error(
                            safe.message ?: "The vault could not be unlocked.",
                            safe is VaultAuthenticationRequiredException,
                        )
                    }
                }
                throw safe
            } finally {
                openedDatabase?.close()
            }
        }
    }

    /** Immediately revokes this controller's session. In-flight copies stop at the next buffer. */
    fun lock() {
        val previous = synchronized(sessionMonitor) {
            epoch += 1
            val old = session
            old?.revoked?.set(true)
            session = null
            mutableState.value = VaultState.Locked
            old
        }
        previous?.database?.close()
    }

    suspend fun documents(): List<VaultDocument> = withContext(Dispatchers.IO) {
        operations.withLock {
            val active = requireSession()
            val documents = active.database.documents().all().map(DocumentEntity::toDocument)
            ensureSession(active)
            documents
        }
    }

    suspend fun pageEvidence(documentId: String): List<VaultPageEvidence> = withContext(Dispatchers.IO) {
        operations.withLock {
            val active = requireSession()
            val document = active.database.documents().find(validId(documentId))
                ?: throw VaultIndexException()
            val pages = VaultEvidenceStore.read(active.database, document)
            currentCoroutineContext().ensureActive()
            ensureSession(active)
            pages
        }
    }

    /** Literal, local, bounded lexical matches. The returned quote always comes from stored text. */
    suspend fun searchDocuments(query: String): List<VaultSearchResult> = withContext(Dispatchers.IO) {
        operations.withLock {
            val active = requireSession()
            val parsed = try {
                LiteralSearchQuery.parse(query)
            } catch (failure: IllegalArgumentException) {
                throw VaultSearchException(failure)
            }
            val match = parsed.ftsMatch ?: return@withLock emptyList()
            val result = ArrayList<VaultSearchResult>()
            // Fetch IDs first: never materialize hundreds of complete private pages at once.
            val candidates = active.database.evidence().searchIds(match, SEARCH_CANDIDATE_LIMIT)
            for (rowId in candidates) {
                currentCoroutineContext().ensureActive()
                ensureSession(active)
                val page = active.database.evidence().findPage(rowId) ?: throw VaultIntegrityException()
                val document = active.database.documents().find(page.documentId) ?: throw VaultIntegrityException()
                if (page.revisionId != document.revisionId) throw VaultIntegrityException()
                val ref = RevisionRef(DocumentId(document.id), RevisionId(document.revisionId))
                val source = RevisionText(ref, listOf(PageText(page.pageNumber, page.text)))
                val hit = GroundedLexicalSearch.findPage(parsed, source, page.pageNumber) ?: continue
                result += VaultSearchResult(document.toDocument(), hit.pageNumber, hit.startOffset, hit.endOffset, hit.quote)
                if (result.size == SEARCH_RESULT_LIMIT) break
            }
            ensureSession(active)
            result.toList()
        }
    }

    /**
     * Called only from an explicit current user indexing action. A null script refreshes native
     * extraction without OCR; a chosen script requests local recognition. Originals never change.
     */
    suspend fun indexDocument(documentId: String, script: OcrScript? = null) = withContext(Dispatchers.IO) {
        operations.withLock {
            val active = requireSession()
            val document = active.database.documents().find(validId(documentId)) ?: throw VaultIndexException()
            val previous = VaultEvidenceStore.read(active.database, document)
            var plaintext: ByteArray? = null
            var completed = false
            var failureState: String? = null
            try {
                commit(active, currentCoroutineContext()) {
                    active.database.documents().updateIndex(document.id, document.pageCount, VaultIndexState.INDEXING.name)
                }
                val bytes = readOriginal(active, document)
                plaintext = bytes
                val indexingContext = currentCoroutineContext()
                // Supply only a revocation token; never capture the keyed Session in the engine.
                val revoked = active.revoked
                val engine = if (script == null) null else TesseractOcrEngine(
                    modelDirectory = ocrModels::directory,
                    cancellationRequested = { !indexingContext.isActive || revoked.get() },
                )
                val result = OfflineDocumentIndexer(ocrEngine = engine).index(document.mimeType, bytes, script)
                currentCoroutineContext().ensureActive()
                ensureSession(active)
                val pages = VaultEvidenceStore.indexed(document, result, previous)
                val terms = VaultEvidenceStore.prepareTerms(pages)
                commit(active, currentCoroutineContext()) {
                    VaultEvidenceStore.replace(active.database, document, pages, terms)
                }
                completed = true
                refreshState(active)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: VaultLockedException) {
                throw failure
            } catch (failure: VaultIntegrityException) {
                failureState = if (previous.any { it.text.isNotBlank() }) VaultIndexState.PARTIAL.name else VaultIndexState.FAILED.name
                throw failure
            } catch (failure: Exception) {
                if (session !== active) throw VaultLockedException()
                failureState = if (previous.any { it.text.isNotBlank() }) VaultIndexState.PARTIAL.name else VaultIndexState.FAILED.name
                throw VaultIndexException(failure)
            } catch (failure: LinkageError) {
                if (session !== active) throw VaultLockedException()
                failureState = if (previous.any { it.text.isNotBlank() }) VaultIndexState.PARTIAL.name else VaultIndexState.FAILED.name
                throw VaultIndexException(failure)
            } finally {
                plaintext?.fill(0)
                // Cancellation/failure cannot replace earlier evidence. Do not touch a new session.
                if (!completed) synchronized(sessionMonitor) {
                    if (session === active) {
                        active.database.documents().updateIndex(document.id, document.pageCount, failureState ?: document.indexState)
                    }
                }
            }
        }
    }

    /**
     * Streams directly from the provider into authenticated ciphertext. The original URI is neither
     * persisted nor granted durable permission. Same-content imports return the existing document.
     */
    suspend fun importDocument(uri: Uri, mimeType: String?, displayName: String): VaultDocument =
        withContext(Dispatchers.IO) {
            operations.withLock {
                val active = requireSession()
                val normalizedMime = DocumentMimeType.fromWireValue(mimeType)
                    ?: throw VaultImportException("Choose a PDF, JPEG, PNG, or UTF-8 text document.", reason = VaultImportFailure.UNSUPPORTED_TYPE)
                val safeName = sanitizeName(displayName)
                val documentId = UUID.randomUUID().toString()
                val revisionId = UUID.randomUUID().toString()
                val staging = File(stagingDirectory, "$revisionId.part")
                val destination = objectFile(revisionId)
                var published = false
                var committed = false
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    val buffer = ByteArray(BUFFER_SIZE)
                    try {
                        val input = applicationContext.contentResolver.openInputStream(uri)
                            ?: throw VaultImportException("The selected document could not be opened.", reason = VaultImportFailure.READ_FAILED)
                        input.use { source ->
                            FileOutputStream(staging).use { file ->
                                val keepOpen = object : FilterOutputStream(file) {
                                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                                        file.write(bytes, offset, length)
                                    }
                                    override fun close() { flush() }
                                }
                                active.streamingAead.newEncryptingStream(
                                    keepOpen,
                                    VaultCrypto.objectAad(active.vaultId, documentId, revisionId),
                                ).use { encrypted ->
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        ensureSession(active)
                                        val count = source.read(buffer)
                                        if (count == -1) break
                                        if (count == 0) continue
                                        size += count
                                        if (size > MAX_DOCUMENT_BYTES) {
                                            throw VaultImportException("Choose a document no larger than 25 MiB.", reason = VaultImportFailure.TOO_LARGE)
                                        }
                                        digest.update(buffer, 0, count)
                                        encrypted.write(buffer, 0, count)
                                    }
                                }
                                file.fd.sync()
                            }
                        }
                    } finally {
                        buffer.fill(0)
                    }
                    ensureSession(active)
                    val validation = ImportValidator().validate(ImportRequest(safeName, normalizedMime.wireValue, size))
                    if (!validation.isValid) throw VaultImportException(validation.issues.first().message, reason = VaultImportFailure.INVALID_OR_CORRUPT)
                    val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                    active.database.documents().findDigest(sha256)?.let { duplicate ->
                        ensureSession(active)
                        return@withLock duplicate.toDocument()
                    }
                    val parsed = validateStagedOriginal(active, staging, documentId, revisionId, normalizedMime.wireValue, size)
                    val entity = DocumentEntity(
                        id = documentId,
                        displayName = safeName,
                        mimeType = normalizedMime.wireValue,
                        sizeBytes = size,
                        importedAtEpochMillis = System.currentTimeMillis(),
                        sha256 = sha256,
                        revisionId = revisionId,
                        pageCount = parsed.pageCount,
                    )
                    val pages = VaultEvidenceStore.parsed(entity, parsed)
                    val terms = VaultEvidenceStore.prepareTerms(pages)
                    val indexedEntity = entity.copy(indexState = VaultEvidenceStore.indexState(pages).name)
                    // Durable ciphertext must precede the metadata commit. A crash here leaves an
                    // encrypted orphan, never a visible row pointing at an incomplete document.
                    Os.rename(staging.absolutePath, destination.absolutePath)
                    published = true
                    DurableFiles.syncDirectory(objectDirectory)
                    ensureSession(active)
                    commit(active, currentCoroutineContext()) {
                        active.database.documents().insert(indexedEntity)
                        VaultEvidenceStore.replace(active.database, indexedEntity, pages, terms)
                    }
                    committed = true
                    refreshState(active)
                    indexedEntity.toDocument()
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: VaultException) {
                    throw failure
                } catch (failure: Exception) {
                    if (session !== active) throw VaultLockedException()
                    throw VaultImportException("The document could not be imported. Please try again.", failure, VaultImportFailure.READ_FAILED)
                } finally {
                    staging.delete()
                    // Do not remove a committed object if lock/cancellation follows the transaction.
                    if (published && !committed) destination.delete()
                }
            }
        }

    /**
     * Explicit export only. The caller owns and closes [destination]. Complete authentication is
     * checked at EOF. A failed/tampered export must be deleted by the caller, since earlier segments
     * may already have been written to its external destination.
     */
    suspend fun copyOriginalTo(id: String, destination: OutputStream) = withContext(Dispatchers.IO) {
        operations.withLock {
            val active = requireSession()
            val entity = active.database.documents().find(validId(id))
                ?: throw VaultImportException("This document no longer exists.")
            val buffer = ByteArray(BUFFER_SIZE)
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            try {
                objectFile(entity.revisionId).inputStream().use { source ->
                    active.streamingAead.newDecryptingStream(
                        source,
                        VaultCrypto.objectAad(active.vaultId, entity.id, entity.revisionId),
                    ).use { decrypted ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            ensureSession(active)
                            val count = decrypted.read(buffer)
                            if (count == -1) break
                            if (count == 0) continue
                            size += count
                            if (size > entity.sizeBytes) throw VaultIntegrityException()
                            digest.update(buffer, 0, count)
                            try {
                                destination.write(buffer, 0, count)
                            } catch (failure: IOException) {
                                throw VaultExportException(failure)
                            }
                        }
                    }
                }
                ensureSession(active)
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (size != entity.sizeBytes || actualHash != entity.sha256) throw VaultIntegrityException()
                try {
                    destination.flush()
                } catch (failure: IOException) {
                    throw VaultExportException(failure)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: VaultException) {
                throw failure
            } catch (failure: IOException) {
                if (session !== active) throw VaultLockedException()
                throw VaultIntegrityException(failure)
            } catch (failure: GeneralSecurityException) {
                if (session !== active) throw VaultLockedException()
                throw VaultIntegrityException(failure)
            } finally {
                buffer.fill(0)
            }
        }
    }

    suspend fun deleteDocument(id: String) = withContext(Dispatchers.IO) {
        operations.withLock {
            val active = requireSession()
            val validatedId = validId(id)
            val entity = active.database.documents().find(validatedId) ?: return@withLock
            val original = objectFile(entity.revisionId)
            ensureSession(active)
            // Commit the removal before unlinking ciphertext. Recovery cleans an interrupted unlink.
            commit(active, currentCoroutineContext()) {
                active.database.evidence().deleteSearch(validatedId)
                // Foreign-key cascades remove page evidence and source geometry in this transaction.
                active.database.documents().delete(validatedId)
            }
            original.delete()
            DurableFiles.syncDirectory(objectDirectory)
            refreshState(active)
        }
    }

    private fun requireSession(): Session = session ?: throw VaultLockedException()

    /** Authenticate EOF, byte count and digest before handing an original to any parser. */
    private suspend fun readOriginal(active: Session, document: DocumentEntity): ByteArray {
        if (document.sizeBytes !in 1..MAX_DOCUMENT_BYTES) throw VaultIntegrityException()
        val plaintext = ByteArray(document.sizeBytes.toInt())
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            objectFile(document.revisionId).inputStream().use { source ->
                active.streamingAead.newDecryptingStream(
                    source, VaultCrypto.objectAad(active.vaultId, document.id, document.revisionId),
                ).use { clear ->
                    var offset = 0
                    while (offset < plaintext.size) {
                        currentCoroutineContext().ensureActive()
                        ensureSession(active)
                        val count = clear.read(plaintext, offset, minOf(BUFFER_SIZE, plaintext.size - offset))
                        if (count == -1) throw VaultIntegrityException()
                        if (count == 0) continue
                        digest.update(plaintext, offset, count)
                        offset += count
                    }
                    if (clear.read() != -1) throw VaultIntegrityException()
                }
            }
            currentCoroutineContext().ensureActive()
            ensureSession(active)
            if (digest.digest().joinToString("") { "%02x".format(it) } != document.sha256) throw VaultIntegrityException()
            return plaintext
        } catch (failure: Throwable) {
            plaintext.fill(0)
            throw failure
        }
    }

    private fun ensureSession(expected: Session) {
        if (session !== expected) throw VaultLockedException()
    }

    /** Lock cannot close the database or advance the session inside a publication transaction. */
    private fun commit(active: Session, context: CoroutineContext, block: () -> Unit) {
        synchronized(sessionMonitor) {
            ensureSession(active)
            active.database.runInTransaction {
                context.ensureActive()
                block()
                context.ensureActive()
                ensureSession(active)
            }
        }
    }

    private fun refreshState(active: Session) {
        val count = active.database.documents().count()
        synchronized(sessionMonitor) {
            if (session === active) mutableState.value = VaultState.Unlocked(count)
        }
    }

    private fun recoverCiphertext(active: Session) {
        check(objectDirectory.exists() || objectDirectory.mkdirs())
        check(stagingDirectory.exists() || stagingDirectory.mkdirs())
        DurableFiles.syncDirectory(directory)
        // Authenticate and validate the complete index before deciding which ciphertext is orphaned.
        val expected = active.database.documents().all().map {
            validId(it.id)
            "${validId(it.revisionId)}.blob"
        }.toSet()
        if (expected.any { !File(objectDirectory, it).isFile }) throw VaultIntegrityException()
        stagingDirectory.listFiles().orEmpty().forEach { file ->
            if (STAGING_NAME.matches(file.name) && file.isFile) file.delete()
        }
        objectDirectory.listFiles().orEmpty().forEach { file ->
            if (OBJECT_NAME.matches(file.name) && file.name !in expected && file.isFile) file.delete()
        }
        directory.listFiles().orEmpty().forEach { file ->
            if (file.name.startsWith(".atomic-") && file.isFile) file.delete()
        }
    }

    private suspend fun validateStagedOriginal(
        active: Session,
        staging: File,
        documentId: String,
        revisionId: String,
        mimeType: String,
        size: Long,
    ): ParsedDocument {
        require(size in 1..MAX_DOCUMENT_BYTES)
        val plaintext = ByteArray(size.toInt())
        try {
            staging.inputStream().use { source ->
                active.streamingAead.newDecryptingStream(
                    source,
                    VaultCrypto.objectAad(active.vaultId, documentId, revisionId),
                ).use { clear ->
                    var offset = 0
                    while (offset < plaintext.size) {
                        currentCoroutineContext().ensureActive()
                        ensureSession(active)
                        val count = clear.read(plaintext, offset, minOf(BUFFER_SIZE, plaintext.size - offset))
                        if (count == -1) throw VaultIntegrityException()
                        offset += count
                    }
                    if (clear.read() != -1) throw VaultIntegrityException()
                }
            }
            currentCoroutineContext().ensureActive()
            ensureSession(active)
            // Enforces real PDF/page and image/pixel bounds without a plaintext parser file.
            val parsed = MemoryDocumentParser().parse(mimeType, plaintext)
            currentCoroutineContext().ensureActive()
            ensureSession(active)
            return parsed
        } catch (failure: DocumentParseException) {
            val (message, reason) = when (failure.failure) {
                ParseFailure.PAGE_LIMIT -> "Choose a PDF with no more than 50 pages." to VaultImportFailure.TOO_MANY_PAGES
                ParseFailure.PIXEL_LIMIT -> "Choose an image no larger than 20 megapixels." to VaultImportFailure.IMAGE_TOO_LARGE
                ParseFailure.BYTE_LIMIT -> "Choose a document no larger than 25 MiB." to VaultImportFailure.TOO_LARGE
                ParseFailure.TEXT_LIMIT -> "The document contains more text than this version can safely process." to VaultImportFailure.TEXT_TOO_LARGE
                ParseFailure.TIME_LIMIT -> "The document took too long to validate." to VaultImportFailure.TIMEOUT
                ParseFailure.UNSUPPORTED_TYPE -> "Choose a PDF, JPEG, PNG, or UTF-8 text document." to VaultImportFailure.UNSUPPORTED_TYPE
                ParseFailure.INVALID_INPUT -> "The document is damaged or does not match its file type." to VaultImportFailure.INVALID_OR_CORRUPT
                ParseFailure.PASSWORD_REQUIRED -> "Password-protected PDFs are not supported by this version." to VaultImportFailure.PASSWORD_REQUIRED
                ParseFailure.CORRUPT_PDF -> "The PDF is damaged and could not be opened." to VaultImportFailure.INVALID_OR_CORRUPT
            }
            throw VaultImportException(message, failure, reason)
        } catch (failure: LinkageError) {
            throw VaultImportException("The PDF validator is unavailable on this device.", failure)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun objectFile(id: String) = File(objectDirectory, "${validId(id)}.blob")

    private fun validId(id: String): String {
        if (!UUID_NAME.matches(id)) throw VaultIntegrityException()
        return id
    }

    private fun sanitizeName(name: String): String = name
        .filterNot { it.isISOControl() || it == '/' || it == '\\' }
        .trim()
        .take(240)
        .ifBlank { "Untitled document" }

    companion object {
        // A second activity/controller must not recover files while another is publishing them.
        // All application components currently run in one Android process.
        private val operations = Mutex()
        const val MAX_DOCUMENT_BYTES = 25L * 1024L * 1024L
        private const val BUFFER_SIZE = 64 * 1024
        /** Deterministic limits, not a claim of exhaustive retrieval or semantic relevance. */
        private const val SEARCH_CANDIDATE_LIMIT = 200
        private const val SEARCH_RESULT_LIMIT = 50
        private val UUID_NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val OBJECT_NAME = Regex("${UUID_NAME.pattern}\\.blob")
        private val STAGING_NAME = Regex("${UUID_NAME.pattern}\\.part")
    }
}

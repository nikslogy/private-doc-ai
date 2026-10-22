package com.privateassistant.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import com.privateassistant.app.R
import com.privateassistant.platform.documents.ExtractionMethod
import com.privateassistant.platform.documents.OcrScript
import com.privateassistant.platform.vault.VaultAuthenticationRequiredException
import com.privateassistant.platform.vault.VaultController
import com.privateassistant.platform.vault.VaultDocument
import com.privateassistant.platform.vault.VaultExportException
import com.privateassistant.platform.vault.VaultImportException
import com.privateassistant.platform.vault.VaultImportFailure
import com.privateassistant.platform.vault.VaultIntegrityException
import com.privateassistant.platform.vault.VaultIndexException
import com.privateassistant.platform.vault.VaultIndexState
import com.privateassistant.platform.vault.VaultLockedException
import com.privateassistant.platform.vault.VaultPageState
import com.privateassistant.platform.vault.VaultState
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

enum class AppPage { DOCUMENTS, ASSISTANT, PRIVACY }

data class VaultUiState(
    val unlocked: Boolean = false,
    val authenticating: Boolean = false,
    val busy: Boolean = false,
    val requiresDeviceSecurity: Boolean = false,
    val documents: List<VaultDocument> = emptyList(),
    val query: String = "",
    val searching: Boolean = false,
    val searchFailed: Boolean = false,
    val visibleDocumentIds: List<String>? = null,
    val searchMatches: List<SearchMatchUi> = emptyList(),
    val selectedDocument: VaultDocument? = null,
    val selectedPage: Int? = null,
    val evidence: DocumentEvidenceUi? = null,
    val evidenceLoading: Boolean = false,
    val evidenceFailed: Boolean = false,
    val indexing: Boolean = false,
    val ocrAvailable: Boolean = false,
    val page: AppPage = AppPage.DOCUMENTS,
    @StringRes val message: Int? = null,
    val importCount: Int? = null,
)

/** Keeps document metadata in memory only while this activity has an unlocked vault. */
@MainThread
class VaultUiModel(
    private val context: Context,
    private val vault: VaultController,
    private val scope: CoroutineScope,
) {
    // Bundled capability is fixed for this installed APK. Never do asset I/O while locking.
    private val ocrAvailable = vault.ocrAvailable
    private val mutableState = MutableStateFlow(VaultUiState(ocrAvailable = ocrAvailable))
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var searchJob: Job? = null
    private var evidenceJob: Job? = null
    private var searchGeneration = 0L
    private var evidenceGeneration = 0L
    private var session = 0L
    private var pendingImports: List<Uri> = emptyList()
    private var pendingExport: VaultDocument? = null

    fun beginAuthentication(): Boolean {
        if (mutableState.value.busy || mutableState.value.authenticating) return false
        mutableState.update { it.copy(authenticating = true, requiresDeviceSecurity = false) }
        return true
    }

    fun authenticationFinished() {
        mutableState.update { it.copy(authenticating = false) }
    }

    fun requireDeviceSecurity() {
        mutableState.update { it.copy(requiresDeviceSecurity = true) }
    }

    fun unlockAfterAuthentication() {
        // A queued system callback must not reopen a session after lock, stop, or cancellation.
        if (!mutableState.value.authenticating) return
        runOperation { expectedSession ->
            mutableState.update { it.copy(authenticating = false) }
            vault.unlock()
            requireSession(expectedSession)
            val documents = vault.documents()
            publish(expectedSession) { it.copy(unlocked = true, documents = documents, requiresDeviceSecurity = false) }
            if (pendingImports.isNotEmpty()) importPending(expectedSession)
        }
    }

    fun lock() {
        session++
        operation?.cancel()
        operation = null
        searchGeneration++
        evidenceGeneration++
        searchJob?.cancel()
        searchJob = null
        evidenceJob?.cancel()
        evidenceJob = null
        pendingExport = null
        // SAF URIs may be retained briefly for an import requested before the background timeout.
        // No document contents or metadata are retained in the locked UI.
        // Clear metadata/evidence before a database close can wait for an in-flight native call.
        mutableState.value = VaultUiState(ocrAvailable = ocrAvailable)
        vault.lock()
    }

    fun receiveImports(uris: List<Uri>) {
        if (uris.isEmpty()) return
        pendingImports = uris
        if (mutableState.value.unlocked) runOperation { importPending(it) }
        else showMessage(R.string.unlock_to_finish_import)
    }

    private suspend fun importPending(expectedSession: Long) {
        requireSession(expectedSession)
        val uris = pendingImports
        pendingImports = emptyList()
        var completed = 0
        val failures = mutableSetOf<VaultImportFailure>()
        for (uri in uris) {
            try {
                val (name, mime) = withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                        ?.take(240)?.takeIf { it.isNotBlank() } ?: context.getString(R.string.untitled_document)
                    name to context.contentResolver.getType(uri)
                }
                requireSession(expectedSession)
                vault.importDocument(uri, mime, name)
                requireSession(expectedSession)
                completed++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (locked: VaultLockedException) {
                throw locked
            } catch (authentication: VaultAuthenticationRequiredException) {
                throw authentication
            } catch (integrity: VaultIntegrityException) {
                throw integrity
            } catch (failure: VaultImportException) {
                failures += failure.reason
            } catch (_: Exception) {
                failures += VaultImportFailure.READ_FAILED
            }
        }
        val documents = vault.documents()
        publish(expectedSession) { it.copy(
            documents = documents,
            importCount = completed.takeIf { count -> count > 0 },
            message = when (failures.size) {
                0 -> null
                1 -> importFailureMessage(failures.single())
                else -> R.string.import_some_failed
            },
        ) }
    }

    fun prepareExport(document: VaultDocument): Boolean {
        if (!isCurrentDocument(document) || mutableState.value.busy || vault.state.value !is VaultState.Unlocked) return false
        pendingExport = document
        return true
    }

    fun cancelExport() { pendingExport = null }

    fun receiveExport(uri: Uri?) {
        val document = pendingExport
        pendingExport = null
        if (uri == null) return
        if (document == null || !mutableState.value.unlocked) {
            scope.launch {
                if (removeExternalOutput(uri)) showMessage(R.string.export_session_expired)
            }
            return
        }
        runOperation { expectedSession ->
            try {
                requireSession(expectedSession)
                withContext(Dispatchers.IO) {
                    val destination = context.contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Unable to open export destination")
                    destination.use { vault.copyOriginalTo(document.id, it) }
                }
                publish(expectedSession) { it.copy(message = R.string.export_complete) }
            } catch (failure: Exception) {
                if (!removeExternalOutput(uri)) {
                    if (failure is CancellationException) throw failure
                } else throw failure
            }
        }
    }

    fun deleteDocument(document: VaultDocument) {
        if (!isCurrentDocument(document)) return
        runOperation { expectedSession ->
            requireSession(expectedSession)
            vault.deleteDocument(document.id)
            val documents = vault.documents()
            closeDocument()
            publish(expectedSession) { it.copy(documents = documents, message = R.string.document_deleted) }
        }
    }

    fun setQuery(query: String) {
        if (!mutableState.value.unlocked) return
        mutableState.update { it.copy(query = query.take(MAX_QUERY_CHARS)) }
        refreshSearch()
    }

    fun setPage(page: AppPage) {
        if (!mutableState.value.unlocked) return
        closeDocument()
        mutableState.update { it.copy(page = page) }
    }

    fun openDocument(document: VaultDocument, pageNumber: Int? = null) {
        if (!isCurrentDocument(document) || mutableState.value.busy) return
        mutableState.update { it.copy(selectedDocument = document, selectedPage = pageNumber,
            evidence = null, evidenceFailed = false) }
        loadEvidence(document)
    }

    fun closeDocument() {
        evidenceGeneration++
        evidenceJob?.cancel()
        evidenceJob = null
        mutableState.update { it.copy(selectedDocument = null, selectedPage = null,
            evidence = null, evidenceLoading = false, evidenceFailed = false) }
    }

    /** An OCR script is supplied only by a current user language selection. */
    fun readDocument(document: VaultDocument, script: OcrScript? = null) {
        if (!isCurrentDocument(document)) return
        if (script == null && document.mimeType !in setOf("application/pdf", "text/plain")) return
        if (script != null && (!mutableState.value.ocrAvailable ||
            document.mimeType !in setOf("application/pdf", "image/jpeg", "image/png"))) return
        runOperation { expectedSession ->
            publish(expectedSession) { it.copy(indexing = true) }
            try {
                vault.indexDocument(document.id, script)
                publish(expectedSession) { it.copy(message = R.string.indexing_finished) }
            } finally {
                // Failure can publish a failed/partial status while retaining earlier source text.
                if (currentCoroutineContext().isActive && session == expectedSession) {
                    val documents = vault.documents()
                    val updated = documents.firstOrNull { it.id == document.id }
                    publish(expectedSession) { it.copy(documents = documents,
                        selectedDocument = if (it.selectedDocument?.id == document.id) updated else it.selectedDocument) }
                    if (updated != null && mutableState.value.selectedDocument?.id == updated.id) loadEvidence(updated)
                }
            }
        }
    }

    fun cancelReading() {
        if (!mutableState.value.indexing) return
        operation?.cancel()
        showMessage(R.string.indexing_cancel_requested)
    }

    private fun isCurrentDocument(document: VaultDocument): Boolean = mutableState.value.let { state ->
        state.unlocked && state.documents.any { it.id == document.id && it.revisionId == document.revisionId }
    }

    /** A new query invalidates both delayed and already-running database results. */
    private fun refreshSearch() {
        searchGeneration++
        searchJob?.cancel()
        searchJob = null
        val currentSession = session
        val generation = searchGeneration
        val query = mutableState.value.query.trim()
        val metadataMatches = mutableState.value.documents.filter { document ->
            document.displayName.contains(query, ignoreCase = true) ||
                document.mimeType.contains(query, ignoreCase = true)
        }.map { it.id }
        mutableState.update { it.copy(searching = query.isNotEmpty(), searchFailed = false,
            visibleDocumentIds = if (query.isEmpty()) null else metadataMatches, searchMatches = emptyList()) }
        if (query.isEmpty() || !mutableState.value.unlocked) return
        searchJob = scope.launch {
            try {
                delay(SEARCH_DEBOUNCE_MILLIS)
                requireSession(currentSession)
                val results = vault.searchDocuments(query)
                if (generation != searchGeneration) return@launch
                publish(currentSession) { current ->
                    current.copy(searching = false,
                        visibleDocumentIds = (metadataMatches + results.map { it.document.id }).distinct(),
                        searchMatches = results.map { SearchMatchUi(it.document.id, it.pageNumber, it.quote) })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (session == currentSession && generation == searchGeneration) {
                    if (!handleSessionFailure(currentSession, failure)) mutableState.update {
                        it.copy(searching = false, searchFailed = true, searchMatches = emptyList())
                    }
                }
            }
        }
    }

    private fun loadEvidence(document: VaultDocument) {
        evidenceGeneration++
        evidenceJob?.cancel()
        val currentSession = session
        val generation = evidenceGeneration
        mutableState.update { it.copy(evidenceLoading = true, evidenceFailed = false) }
        evidenceJob = scope.launch {
            try {
                val pages = vault.pageEvidence(document.id)
                if (generation != evidenceGeneration) return@launch
                val summary = document.indexSummary(pages.count { it.state == VaultPageState.READY && it.text.isNotBlank() })
                val evidence = DocumentEvidenceUi(document.revisionId, summary, pages.map { page ->
                    EvidencePageUi(page.pageNumber, page.text, when (page.method) {
                        ExtractionMethod.UTF8_TEXT -> EvidenceMethodUi.UTF8_TEXT
                        ExtractionMethod.PDF_NATIVE -> EvidenceMethodUi.PDF_NATIVE
                        ExtractionMethod.OCR_LATIN -> EvidenceMethodUi.OCR_LATIN
                        ExtractionMethod.OCR_DEVANAGARI -> EvidenceMethodUi.OCR_DEVANAGARI
                        null -> null
                    }, when (page.state) {
                        VaultPageState.READY -> EvidencePageStatusUi.READY
                        VaultPageState.EMPTY -> EvidencePageStatusUi.EMPTY
                        VaultPageState.NEEDS_OCR -> EvidencePageStatusUi.NEEDS_OCR
                        VaultPageState.FAILED -> EvidencePageStatusUi.FAILED
                    })
                })
                publish(currentSession) { it.copy(evidence = evidence, evidenceLoading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (session == currentSession && generation == evidenceGeneration) {
                    if (!handleSessionFailure(currentSession, failure)) mutableState.update {
                        it.copy(evidence = null, evidenceLoading = false, evidenceFailed = true)
                    }
                }
            }
        }
    }

    private fun handleSessionFailure(expectedSession: Long, failure: Exception): Boolean {
        if (session != expectedSession) return true
        val message = when (failure) {
            is VaultAuthenticationRequiredException, is VaultLockedException -> R.string.session_expired
            is VaultIntegrityException -> R.string.vault_integrity_failed
            else -> return false
        }
        lock()
        showMessage(message)
        return true
    }
    fun showMessage(@StringRes message: Int) { mutableState.update { it.copy(message = message) } }
    fun clearMessage() { mutableState.update { it.copy(message = null, importCount = null) } }

    /** Authenticated streaming may already have written earlier segments before an EOF failure. */
    private suspend fun removeExternalOutput(uri: Uri): Boolean = withContext(NonCancellable) {
        val deleted = withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false) ||
                runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)
        }
        // Publish cleanup failure before returning to a potentially cancelled calling context.
        if (!deleted) showMessage(R.string.export_cleanup_failed)
        deleted
    }

    private fun requireSession(expectedSession: Long) {
        if (session != expectedSession || vault.state.value !is VaultState.Unlocked) throw VaultLockedException()
    }

    private fun publish(expectedSession: Long, update: (VaultUiState) -> VaultUiState) {
        requireSession(expectedSession)
        mutableState.update(update)
    }

    private fun runOperation(block: suspend (Long) -> Unit) {
        if (mutableState.value.busy) return
        val currentSession = session
        mutableState.update { it.copy(busy = true) }
        operation = scope.launch {
            try {
                block(currentSession)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: VaultAuthenticationRequiredException) {
                if (session == currentSession) {
                    lock()
                    showMessage(R.string.session_expired)
                }
            } catch (_: VaultLockedException) {
                if (session == currentSession) {
                    lock()
                    showMessage(R.string.session_expired)
                }
            } catch (_: VaultIntegrityException) {
                if (session == currentSession) {
                    lock()
                    showMessage(R.string.vault_integrity_failed)
                }
            } catch (_: VaultExportException) {
                if (session == currentSession) showMessage(R.string.export_failed)
            } catch (_: VaultIndexException) {
                if (session == currentSession) showMessage(R.string.indexing_failed)
            } catch (_: Exception) {
                if (session == currentSession) showMessage(R.string.vault_operation_failed)
            } finally {
                if (session == currentSession) {
                    mutableState.update { it.copy(busy = false, authenticating = false, indexing = false) }
                    if (mutableState.value.unlocked) refreshSearch()
                }
            }
        }
    }

    companion object {
        private const val MAX_QUERY_CHARS = 256
        private const val SEARCH_DEBOUNCE_MILLIS = 250L
    }
}

internal fun VaultDocument.indexSummary(searchablePages: Int = 0) = IndexSummaryUi(
    status = when (indexState) {
        VaultIndexState.NOT_INDEXED -> IndexStatusUi.NOT_INDEXED
        VaultIndexState.NEEDS_OCR -> IndexStatusUi.NEEDS_OCR
        VaultIndexState.INDEXING -> IndexStatusUi.INDEXING
        VaultIndexState.INDEXED -> IndexStatusUi.READY
        VaultIndexState.PARTIAL -> IndexStatusUi.PARTIAL
        VaultIndexState.FAILED -> IndexStatusUi.FAILED
    },
    pageCount = pageCount,
    searchablePages = searchablePages,
)

/** Failure codes are safe to present; exception messages and causes never become UI text. */
@StringRes
internal fun importFailureMessage(reason: VaultImportFailure): Int = when (reason) {
    VaultImportFailure.UNSUPPORTED_TYPE -> R.string.import_unsupported_type
    VaultImportFailure.TYPE_MISMATCH -> R.string.import_type_mismatch
    VaultImportFailure.TOO_LARGE -> R.string.import_too_large
    VaultImportFailure.TOO_MANY_PAGES -> R.string.import_too_many_pages
    VaultImportFailure.IMAGE_TOO_LARGE -> R.string.import_image_too_large
    VaultImportFailure.INVALID_OR_CORRUPT -> R.string.import_invalid_document
    VaultImportFailure.PASSWORD_REQUIRED -> R.string.import_password_required
    VaultImportFailure.READ_FAILED -> R.string.import_read_failed
    VaultImportFailure.TIMEOUT -> R.string.import_timeout
    VaultImportFailure.TEXT_TOO_LARGE -> R.string.import_text_too_large
    VaultImportFailure.UNKNOWN -> R.string.import_some_failed
}

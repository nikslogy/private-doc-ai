package com.privateassistant.platform.vault

import com.privateassistant.platform.documents.ExtractionMethod
import com.privateassistant.platform.documents.PageGeometry
import com.privateassistant.platform.documents.SourceTextSpan

/** Metadata is available only while the vault is unlocked. Never persist these objects as plaintext. */
data class VaultDocument(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val importedAtEpochMillis: Long,
    val sha256: String,
    /** This foundation stores one immutable original revision per document. */
    val revisionId: String = id,
    /** Zero means an existing revision has not yet been explicitly indexed. */
    val pageCount: Int = 0,
    val indexState: VaultIndexState = VaultIndexState.NOT_INDEXED,
)

enum class VaultIndexState { NOT_INDEXED, NEEDS_OCR, INDEXING, INDEXED, PARTIAL, FAILED }
enum class VaultPageState { READY, EMPTY, NEEDS_OCR, FAILED }

/** Raw source text and UTF-16 spans are transient outside the encrypted database. */
data class VaultPageEvidence(
    val documentId: String,
    val revisionId: String,
    val pageNumber: Int,
    val text: String,
    val geometry: PageGeometry?,
    val method: ExtractionMethod?,
    val extractorVersion: String,
    val state: VaultPageState,
    val spans: List<SourceTextSpan>,
) {
    val requiresOcr: Boolean get() = state == VaultPageState.NEEDS_OCR || state == VaultPageState.FAILED
}

/** A bounded exact quote from one immutable source page, never an inferred answer. */
data class VaultSearchResult(
    val document: VaultDocument,
    val pageNumber: Int,
    val startOffset: Int,
    val endOffset: Int,
    val quote: String,
)

sealed interface VaultState {
    data object Locked : VaultState
    data class Unlocked(val documentCount: Int) : VaultState
    data class Error(val message: String, val needsAuthentication: Boolean = false) : VaultState
}

open class VaultException(message: String, cause: Throwable? = null) : Exception(message, cause)
class VaultLockedException : VaultException("Unlock your vault to continue.")
class VaultAuthenticationRequiredException(cause: Throwable? = null) :
    VaultException("Authenticate with your device credential or strong biometric, then try again.", cause)
class VaultIntegrityException(cause: Throwable? = null) :
    VaultException("The encrypted vault could not be verified. Your existing files have been preserved.", cause)
enum class VaultImportFailure {
    UNSUPPORTED_TYPE, TYPE_MISMATCH, TOO_LARGE, TOO_MANY_PAGES, IMAGE_TOO_LARGE,
    INVALID_OR_CORRUPT, PASSWORD_REQUIRED, READ_FAILED, TIMEOUT, TEXT_TOO_LARGE, UNKNOWN,
}
class VaultImportException(
    message: String,
    cause: Throwable? = null,
    val reason: VaultImportFailure = VaultImportFailure.UNKNOWN,
) : VaultException(message, cause)
class VaultExportException(cause: Throwable? = null) :
    VaultException("The document could not be saved. Check the selected destination.", cause)
class VaultIndexException(cause: Throwable? = null) :
    VaultException("The document could not be indexed. Its original and previous evidence have been preserved.", cause)
class VaultSearchException(cause: Throwable? = null) :
    VaultException("Enter up to 512 characters and 24 search terms.", cause)
class VaultSourceUnavailableException :
    VaultException("The selected source revision is unavailable. Select a document again.")

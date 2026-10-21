package com.privateassistant.core

import java.time.Instant
import java.util.Collections
import java.util.UUID

@JvmInline
value class DocumentId(val value: String) {
    init {
        require(SAFE_IDENTIFIER.matches(value)) { "Invalid document identifier" }
    }

    companion object {
        fun random(): DocumentId = DocumentId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class RevisionId(val value: String) {
    init {
        require(SAFE_IDENTIFIER.matches(value)) { "Invalid revision identifier" }
    }

    companion object {
        fun random(): RevisionId = RevisionId(UUID.randomUUID().toString())
    }
}

/** A source is always a particular immutable revision, never a moving "latest" alias. */
data class RevisionRef(val documentId: DocumentId, val revisionId: RevisionId)

@JvmInline
value class Sha256Digest(val hex: String) {
    init {
        require(Regex("[0-9a-f]{64}").matches(hex)) { "A SHA-256 digest must be 64 lowercase hexadecimal characters" }
    }
}

data class Document(
    val id: DocumentId,
    val displayName: String,
    val currentRevision: RevisionRef,
    val createdAt: Instant,
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= 255)
        require(currentRevision.documentId == id)
    }
}

enum class DocumentMimeType(val wireValue: String) {
    PDF("application/pdf"),
    JPEG("image/jpeg"),
    PNG("image/png"),
    PLAIN_TEXT("text/plain");

    companion object {
        fun fromWireValue(value: String?): DocumentMimeType? =
            entries.firstOrNull { it.wireValue == value?.substringBefore(';')?.trim()?.lowercase() }
    }
}

enum class ExtractionState { PENDING, READY, FAILED }

class DocumentRevision(
    val ref: RevisionRef,
    val displayName: String,
    val mimeType: DocumentMimeType,
    val byteCount: Long,
    val pageCount: Int,
    val importedAt: Instant,
    val digest: Sha256Digest,
    val extractionState: ExtractionState = ExtractionState.PENDING,
    parents: List<RevisionRef> = emptyList(),
) {
    val parents: List<RevisionRef> = frozenList(parents)

    init {
        require(displayName.isNotBlank() && displayName.length <= 255)
        require(byteCount > 0)
        require(pageCount > 0)
        require(this.parents.distinct().size == this.parents.size && ref !in this.parents)
    }
}

/** Page numbers are one based. Offsets used by evidence are Kotlin UTF-16 offsets. */
data class PageText(val pageNumber: Int, val text: String) {
    init {
        require(pageNumber > 0)
    }
}

data class PixelSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Pixel dimensions must be positive" }
    }

    val pixelCount: Long get() = width.toLong() * height.toLong()
}

class RevisionText(val ref: RevisionRef, pages: List<PageText>) {
    val pages: List<PageText> = frozenList(pages)

    init {
        require(this.pages.isNotEmpty()) { "A revision must contain at least one page" }
        require(this.pages.map { it.pageNumber }.distinct().size == this.pages.size) { "Duplicate page numbers" }
    }

    fun page(pageNumber: Int): PageText? = pages.firstOrNull { it.pageNumber == pageNumber }
}

internal fun <T> frozenList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private val SAFE_IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")

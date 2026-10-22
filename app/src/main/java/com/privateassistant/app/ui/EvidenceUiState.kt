package com.privateassistant.app.ui

import com.privateassistant.core.facts.FieldKey

/** Ephemeral view data. These values must be cleared with the unlocked session. */
enum class IndexStatusUi { NOT_INDEXED, NEEDS_OCR, PARTIAL, READY, FAILED, INDEXING }

data class IndexSummaryUi(
    val status: IndexStatusUi,
    val pageCount: Int = 0,
    val searchablePages: Int = 0,
)

enum class EvidenceMethodUi { UTF8_TEXT, PDF_NATIVE, OCR_LATIN, OCR_DEVANAGARI }

enum class EvidencePageStatusUi { READY, EMPTY, NEEDS_OCR, FAILED }

data class EvidencePageUi(
    val pageNumber: Int,
    val text: String,
    val method: EvidenceMethodUi?,
    val status: EvidencePageStatusUi,
)

data class DocumentEvidenceUi(
    val revisionId: String,
    val summary: IndexSummaryUi,
    val pages: List<EvidencePageUi>,
)

data class SearchMatchUi(
    val documentId: String,
    val pageNumber: Int,
    val excerpt: String,
)

/** Label matches, not generated answers. Kept only in the authorized session's memory. */
data class FieldCandidateUi(val rawValue: String, val pageNumber: Int, val quote: String)

data class FieldGroupUi(
    val key: FieldKey,
    val candidates: List<FieldCandidateUi>,
    val conflicting: Boolean,
)

data class DocumentFactsUi(
    val revisionId: String,
    val groups: List<FieldGroupUi>,
    val truncated: Boolean,
    val incomplete: Boolean,
)

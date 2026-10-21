package com.privateassistant.platform.vault

import com.privateassistant.core.search.LiteralSearchQuery
import com.privateassistant.platform.documents.DocumentIndexResult
import com.privateassistant.platform.documents.EvidenceBox
import com.privateassistant.platform.documents.ExtractionMethod
import com.privateassistant.platform.documents.MemoryDocumentParser
import com.privateassistant.platform.documents.PageGeometry
import com.privateassistant.platform.documents.PageIndexState
import com.privateassistant.platform.documents.ParsedDocument
import com.privateassistant.platform.documents.SourceSpanKind
import com.privateassistant.platform.documents.SourceTextSpan

/** These functions never write files. Their only durable sink is the already-open SQLCipher DB. */
internal object VaultEvidenceStore {
    fun parsed(document: DocumentEntity, parsed: ParsedDocument): List<VaultPageEvidence> = parsed.pages.map { page ->
        val pending = page.pageNumber in parsed.pagesRequiringOcr
        val method = when (document.mimeType) {
            "text/plain" -> ExtractionMethod.UTF8_TEXT
            "application/pdf" -> ExtractionMethod.PDF_NATIVE
            else -> null
        }
        VaultPageEvidence(
            document.id, document.revisionId, page.pageNumber, page.text,
            parsed.geometry.firstOrNull { it.pageNumber == page.pageNumber }, method,
            when (method) {
                ExtractionMethod.UTF8_TEXT -> "utf8-strict-v1"
                ExtractionMethod.PDF_NATIVE -> "pdfiumandroid-1.0.35/native-v1"
                else -> "not-extracted-v1"
            },
            when {
                pending -> VaultPageState.NEEDS_OCR
                page.text.isBlank() -> VaultPageState.EMPTY
                else -> VaultPageState.READY
            },
            if (page.text.isEmpty()) emptyList() else listOf(SourceTextSpan(0, page.text.length, null, SourceSpanKind.BLOCK)),
        )
    }

    /** Retain already-extracted evidence if an explicit OCR retry fails on that page. */
    fun indexed(
        document: DocumentEntity,
        result: DocumentIndexResult,
        previous: List<VaultPageEvidence>,
    ): List<VaultPageEvidence> = result.pages.map { page ->
        val old = previous.firstOrNull { it.pageNumber == page.pageNumber }
        val pending = page.state == PageIndexState.NEEDS_OCR || page.errorCode == "OCR_UNAVAILABLE"
        if (pending && old != null && old.text.isNotBlank()) {
            // A native-only refresh cannot erase valid OCR for this immutable original.
            old
        } else if (page.state == PageIndexState.FAILED && !pending && old != null && old.text.isNotBlank()) {
            old.copy(state = VaultPageState.FAILED)
        } else {
            VaultPageEvidence(
                document.id, document.revisionId, page.pageNumber, page.text, page.geometry,
                page.method, page.extractorVersion,
                when {
                    pending -> VaultPageState.NEEDS_OCR
                    page.state == PageIndexState.READY -> VaultPageState.READY
                    page.state == PageIndexState.EMPTY -> VaultPageState.EMPTY
                    else -> VaultPageState.FAILED
                },
                page.spans.toList(),
            )
        }
    }

    fun indexState(pages: List<VaultPageEvidence>): VaultIndexState = when {
        pages.any { it.state == VaultPageState.FAILED } ->
            if (pages.any { it.text.isNotBlank() }) VaultIndexState.PARTIAL else VaultIndexState.FAILED
        pages.any { it.state == VaultPageState.NEEDS_OCR } -> VaultIndexState.NEEDS_OCR
        else -> VaultIndexState.INDEXED
    }

    /** Tokenization is CPU work and must happen before taking the short publication monitor. */
    fun prepareTerms(pages: List<VaultPageEvidence>): List<String> = pages.map { LiteralSearchQuery.indexTerms(it.text) }

    /** Caller surrounds this with the document mutation in one session-checked transaction. */
    fun replace(
        database: VaultDatabase,
        document: DocumentEntity,
        pages: List<VaultPageEvidence>,
        encodedTerms: List<String> = prepareTerms(pages),
    ) {
        validate(document, pages)
        if (encodedTerms.size != pages.size) throw VaultIntegrityException()
        val dao = database.evidence()
        dao.deleteSearch(document.id)
        dao.deletePages(document.id)
        pages.forEachIndexed { index, page ->
            val pageId = dao.insertPage(PageEvidenceEntity(
                documentId = page.documentId, revisionId = page.revisionId, pageNumber = page.pageNumber,
                text = page.text, width = page.geometry?.width, height = page.geometry?.height,
                method = page.method?.name, extractorVersion = page.extractorVersion, state = page.state.name,
            ))
            dao.insertSearch(PageSearchEntity(pageId, encodedTerms[index]))
            if (page.spans.isNotEmpty()) dao.insertSpans(page.spans.mapIndexed { ordinal, span ->
                PageSpanEntity(pageId, ordinal, span.startUtf16, span.endUtf16, span.kind.name,
                    span.box?.left, span.box?.top, span.box?.right, span.box?.bottom)
            })
        }
        database.documents().updateIndex(document.id, pages.size, indexState(pages).name)
    }

    fun read(database: VaultDatabase, document: DocumentEntity): List<VaultPageEvidence> {
        val pages = database.evidence().pages(document.id).map { row ->
            if (row.revisionId != document.revisionId) throw VaultIntegrityException()
            val geometry = if (row.width == null && row.height == null) null else {
                PageGeometry(row.pageNumber, row.width ?: throw VaultIntegrityException(), row.height ?: throw VaultIntegrityException())
            }
            VaultPageEvidence(
                row.documentId, row.revisionId, row.pageNumber, row.text, geometry,
                row.method?.let { enumValueOf<ExtractionMethod>(it) }, row.extractorVersion,
                enumValueOf(row.state), database.evidence().spans(row.rowId).map { span ->
                    val box = if (span.left == null && span.top == null && span.right == null && span.bottom == null) null else {
                        EvidenceBox(span.left ?: throw VaultIntegrityException(), span.top ?: throw VaultIntegrityException(),
                            span.right ?: throw VaultIntegrityException(), span.bottom ?: throw VaultIntegrityException())
                    }
                    SourceTextSpan(span.startUtf16, span.endUtf16, box, enumValueOf(span.kind))
                },
            )
        }
        if (pages.size != document.pageCount) throw VaultIntegrityException()
        if (pages.isNotEmpty()) validate(document, pages)
        return pages
    }

    private fun validate(document: DocumentEntity, pages: List<VaultPageEvidence>) {
        if (pages.size !in 1..MemoryDocumentParser.MAX_PDF_PAGES ||
            pages.sumOf { it.text.length.toLong() } > MemoryDocumentParser.MAX_TEXT_CHARS ||
            pages.sumOf { it.spans.size.toLong() } > MAX_SPANS_PER_DOCUMENT ||
            pages.withIndex().any { (index, page) -> page.pageNumber != index + 1 }
        ) throw VaultIntegrityException()
        pages.forEach { page ->
            if (page.documentId != document.id || page.revisionId != document.revisionId ||
                page.spans.size > MAX_SPANS_PER_PAGE || page.extractorVersion.length !in 1..128 ||
                (page.state == VaultPageState.READY && page.text.isBlank()) ||
                (page.state == VaultPageState.EMPTY && page.text.isNotBlank()) ||
                (page.geometry != null && (page.geometry.pageNumber != page.pageNumber || page.geometry.width <= 0 || page.geometry.height <= 0))
            ) throw VaultIntegrityException()
            page.spans.forEach { span ->
                if (span.startUtf16 < 0 || span.endUtf16 <= span.startUtf16 || span.endUtf16 > page.text.length) throw VaultIntegrityException()
                span.box?.let { box ->
                    val geometry = page.geometry ?: throw VaultIntegrityException()
                    if (!box.left.isFinite() || !box.top.isFinite() || !box.right.isFinite() || !box.bottom.isFinite() ||
                        box.left < 0 || box.top < 0 || box.right <= box.left || box.bottom <= box.top ||
                        box.right > geometry.width || box.bottom > geometry.height
                    ) throw VaultIntegrityException()
                }
            }
        }
    }

    private const val MAX_SPANS_PER_PAGE = 20_000
    private const val MAX_SPANS_PER_DOCUMENT = 50_000L
}

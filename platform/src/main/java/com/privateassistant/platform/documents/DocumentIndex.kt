package com.privateassistant.platform.documents

/** Recognition script requested by the user; engine availability is separate. */
enum class OcrScript { LATIN, DEVANAGARI }

enum class ExtractionMethod { UTF8_TEXT, PDF_NATIVE, OCR_LATIN, OCR_DEVANAGARI }
enum class PageIndexState { READY, EMPTY, NEEDS_OCR, FAILED }
enum class SourceSpanKind { BLOCK, LINE }

/** Coordinates use the oriented source page's [PageGeometry] units. */
data class EvidenceBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Offsets index the unnormalised [IndexedPage.text] as JVM UTF-16 code units. */
data class SourceTextSpan(
    val startUtf16: Int,
    val endUtf16: Int,
    val box: EvidenceBox?,
    val kind: SourceSpanKind,
)

data class IndexedPage(
    val pageNumber: Int,
    val text: String,
    val geometry: PageGeometry?,
    val method: ExtractionMethod?,
    val extractorVersion: String,
    val spans: List<SourceTextSpan> = emptyList(),
    val state: PageIndexState,
    /** A fixed application code, never a recognizer exception or document text. */
    val errorCode: String? = null,
)

data class DocumentIndexResult(val pages: List<IndexedPage>) {
    val pageCount: Int get() = pages.size
    val complete: Boolean get() = pages.all { it.state == PageIndexState.READY || it.state == PageIndexState.EMPTY }
}

/** Real engines may be supplied to development probes; production has none approved. */
interface OcrEngine {
    /** Synchronous: may return only once the engine has finished consuming the bitmap. */
    fun recognize(bitmap: android.graphics.Bitmap, pageNumber: Int, geometry: PageGeometry, script: OcrScript): IndexedPage
}

internal fun OcrScript.extractionMethod(): ExtractionMethod = when (this) {
    OcrScript.LATIN -> ExtractionMethod.OCR_LATIN
    OcrScript.DEVANAGARI -> ExtractionMethod.OCR_DEVANAGARI
}


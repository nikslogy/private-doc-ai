package com.privateassistant.platform.documents

/**
 * The recognizer does not choose page identity, script, geometry or persistence
 * limits. Reject malformed output at the page boundary so another page's native
 * evidence and the immutable original remain usable.
 */
internal object OcrResultValidator {
    private val version = Regex("[A-Za-z0-9][A-Za-z0-9._/+:-]{0,127}")

    fun validated(
        result: IndexedPage,
        pageNumber: Int,
        geometry: PageGeometry,
        script: OcrScript,
    ): IndexedPage? {
        if (result.pageNumber != pageNumber || geometry.pageNumber != pageNumber ||
            geometry.width <= 0 || geometry.height <= 0 || result.geometry != geometry ||
            result.method != script.extractionMethod() || !version.matches(result.extractorVersion) ||
            result.errorCode != null || result.text.length > MemoryDocumentParser.MAX_PAGE_TEXT_CHARS ||
            result.spans.size > DocumentIndexLimits.MAX_SPANS_PER_PAGE
        ) return null
        when (result.state) {
            PageIndexState.READY -> if (result.text.isBlank()) return null
            PageIndexState.EMPTY -> if (result.text.isNotBlank() || result.spans.isNotEmpty()) return null
            else -> return null
        }
        for (span in result.spans) {
            if (span.startUtf16 < 0 || span.endUtf16 <= span.startUtf16 || span.endUtf16 > result.text.length) return null
            span.box?.let { box ->
                if (!box.left.isFinite() || !box.top.isFinite() || !box.right.isFinite() || !box.bottom.isFinite() ||
                    box.left < 0 || box.top < 0 || box.right <= box.left || box.bottom <= box.top ||
                    box.right > geometry.width || box.bottom > geometry.height
                ) return null
            }
        }
        // The engine may retain its mutable list; never publish that same list.
        return result.copy(spans = result.spans.toList())
    }
}

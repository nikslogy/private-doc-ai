package com.privateassistant.platform.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OcrResultValidatorTest {
    private val geometry = PageGeometry(2, 600, 400)
    private val span = SourceTextSpan(0, 12, EvidenceBox(0f, 0f, 600f, 400f), SourceSpanKind.LINE)
    private val page = IndexedPage(2, "Policy 00042", geometry, ExtractionMethod.OCR_LATIN,
        "local-ocr-1.0/spans-v1", listOf(span), PageIndexState.READY)

    @Test
    fun validPagePreservesRawTextAndUtf16Evidence() {
        val text = "विमा e\u0301 😀 00042"
        val start = text.indexOf("😀")
        val unicode = page.copy(text = text, spans = listOf(span.copy(startUtf16 = start, endUtf16 = start + 2)))
        val result = requireNotNull(validate(unicode))
        assertEquals(text, result.text)
        assertEquals("😀", result.text.substring(result.spans.single().startUtf16, result.spans.single().endUtf16))
        assertEquals(unicode, result)
    }

    @Test
    fun recognizerCannotChangeRequestedPageScriptOrSourceGeometry() {
        rejected(
            page.copy(pageNumber = 1),
            page.copy(geometry = null),
            page.copy(geometry = geometry.copy(pageNumber = 1)),
            page.copy(geometry = geometry.copy(width = 601)),
            page.copy(method = ExtractionMethod.PDF_NATIVE),
            page.copy(method = ExtractionMethod.OCR_DEVANAGARI),
            page.copy(method = null),
        )
        assertNotNull(OcrResultValidator.validated(page.copy(method = ExtractionMethod.OCR_DEVANAGARI), 2, geometry, OcrScript.DEVANAGARI))
        assertNull(OcrResultValidator.validated(page, 2, geometry.copy(width = 0), OcrScript.LATIN))
    }

    @Test
    fun onlyConsistentSuccessfulStatesCanEnterEvidence() {
        rejected(
            page.copy(text = " "),
            page.copy(state = PageIndexState.EMPTY),
            page.copy(state = PageIndexState.NEEDS_OCR),
            page.copy(state = PageIndexState.FAILED),
            page.copy(errorCode = "private recognizer exception"),
        )
        assertNotNull(validate(page.copy(text = "", spans = emptyList(), state = PageIndexState.EMPTY)))
        assertNotNull(validate(page.copy(text = " \n", spans = emptyList(), state = PageIndexState.EMPTY)))
        assertNull(validate(page.copy(text = " ", spans = listOf(span.copy(endUtf16 = 1)), state = PageIndexState.EMPTY)))
    }

    @Test
    fun pageTextAndSpanCountsAreBoundedBeforePersistence() {
        val textLimit = MemoryDocumentParser.MAX_PAGE_TEXT_CHARS
        assertNotNull(validate(page.copy(text = "x".repeat(textLimit))))
        assertNull(validate(page.copy(text = "x".repeat(textLimit + 1))))
        assertNotNull(validate(page.copy(spans = List(DocumentIndexLimits.MAX_SPANS_PER_PAGE) { span })))
        assertNull(validate(page.copy(spans = List(DocumentIndexLimits.MAX_SPANS_PER_PAGE + 1) { span })))
    }

    @Test
    fun offsetsMustIdentifyNonemptySubstringsOfThisPage() {
        for ((start, end) in listOf(-1 to 1, 0 to 0, 2 to 1, 0 to 13, 12 to 13)) {
            assertNull("Accepted invalid offsets $start..$end", validate(page.copy(spans = listOf(span.copy(startUtf16 = start, endUtf16 = end)))))
        }
        assertNotNull(validate(page.copy(spans = listOf(span.copy(startUtf16 = 11, endUtf16 = 12)))))
    }

    @Test
    fun boxesMustBeFiniteNonemptyAndInsideOrientedPage() {
        val invalid = listOf(
            EvidenceBox(Float.NaN, 0f, 1f, 1f), EvidenceBox(0f, Float.NEGATIVE_INFINITY, 1f, 1f),
            EvidenceBox(0f, 0f, Float.POSITIVE_INFINITY, 1f), EvidenceBox(0f, 0f, 1f, Float.NaN),
            EvidenceBox(-1f, 0f, 1f, 1f), EvidenceBox(0f, -1f, 1f, 1f),
            EvidenceBox(1f, 0f, 1f, 1f), EvidenceBox(0f, 1f, 1f, 1f),
            EvidenceBox(0f, 0f, 601f, 400f), EvidenceBox(0f, 0f, 600f, 401f),
        )
        invalid.forEach { assertNull("Accepted invalid box $it", validate(page.copy(spans = listOf(span.copy(box = it))))) }
        assertNotNull(validate(page.copy(spans = listOf(span.copy(box = null)))))
    }

    @Test
    fun extractorIdentityIsBoundedAndCannotContainDocumentTextOrNewlines() {
        for (value in listOf("", " ", "private text", "engine\nprivate", "a".repeat(129))) {
            assertNull(validate(page.copy(extractorVersion = value)))
        }
        assertNotNull(validate(page.copy(extractorVersion = "a".repeat(128))))
    }

    @Test
    fun validatedSpansDoNotAliasRecognizerOwnedList() {
        val mutable = mutableListOf(span)
        val result = requireNotNull(validate(page.copy(spans = mutable)))
        mutable.clear()
        assertFalse(result.spans.isEmpty())
        assertEquals(listOf(span), result.spans)
    }

    private fun validate(candidate: IndexedPage): IndexedPage? =
        OcrResultValidator.validated(candidate, 2, geometry, OcrScript.LATIN)

    private fun rejected(vararg candidates: IndexedPage) {
        candidates.forEach { assertNull(validate(it)) }
    }
}

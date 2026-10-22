package com.privateassistant.platform.documents

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic recognizer contract tests; no OCR SDK, model or network is used. */
@RunWith(AndroidJUnit4::class)
class OcrBoundaryInstrumentedTest {
    @Test
    fun invalidRecognitionFailsOnlyRasterPageAndKeepsNativeEvidenceAndOriginal() = runBlocking {
        val bytes = pdfBytes(nativeFirstPage = true, blankPages = 1)
        val before = MessageDigest.getInstance("SHA-256").digest(bytes)
        var recognitionCalls = 0
        val engine = object : OcrEngine {
            override fun recognize(bitmap: Bitmap, pageNumber: Int, geometry: PageGeometry, script: OcrScript): IndexedPage {
                recognitionCalls++
                // The boundary must reject an engine substituting a different page.
                return recognized(pageNumber, geometry, script).copy(pageNumber = 1)
            }
        }
        try {
            val result = OfflineDocumentIndexer(ocrEngine = engine).index("application/pdf", bytes, OcrScript.LATIN)
            assertEquals(1, recognitionCalls)
            assertFalse(result.complete)
            assertEquals(2, result.pageCount)
            assertEquals(PageIndexState.READY, result.pages[0].state)
            assertEquals(ExtractionMethod.PDF_NATIVE, result.pages[0].method)
            assertTrue(result.pages[0].text.contains("Native policy 00042"))
            assertEquals(2, result.pages[1].pageNumber)
            assertEquals(PageIndexState.FAILED, result.pages[1].state)
            assertEquals("OCR_INVALID_RESULT", result.pages[1].errorCode)
            assertTrue(result.pages[1].text.isEmpty())
            assertTrue(result.pages[1].spans.isEmpty())
            assertTrue(before.contentEquals(MessageDigest.getInstance("SHA-256").digest(bytes)))
        } finally { bytes.fill(0) }
    }

    @Test
    fun documentSpanLimitRejectsOnlyPageThatExceedsRemainingBudget() = runBlocking {
        val bytes = pdfBytes(nativeFirstPage = false, blankPages = 3)
        val engine = object : OcrEngine {
            override fun recognize(bitmap: Bitmap, pageNumber: Int, geometry: PageGeometry, script: OcrScript): IndexedPage {
                val page = recognized(pageNumber, geometry, script)
                return page.copy(spans = List(DocumentIndexLimits.MAX_SPANS_PER_PAGE) { page.spans.single() })
            }
        }
        try {
            val pages = OfflineDocumentIndexer(ocrEngine = engine).index("application/pdf", bytes, OcrScript.LATIN).pages
            assertEquals(listOf(PageIndexState.READY, PageIndexState.READY, PageIndexState.FAILED), pages.map { it.state })
            assertEquals(40_000, pages.sumOf { it.spans.size })
            assertEquals("INDEX_SPAN_LIMIT", pages[2].errorCode)
            assertTrue(pages[2].text.isEmpty())
            assertTrue(pages[2].spans.isEmpty())
        } finally { bytes.fill(0) }
    }

    @Test
    fun cancellationIsPropagatedAfterConsumedBitmapIsRecycled() = runBlocking {
        val bytes = pdfBytes(nativeFirstPage = false, blankPages = 1)
        val expected = CancellationException("Synthetic cancellation")
        var consumedBitmap: Bitmap? = null
        val engine = object : OcrEngine {
            override fun recognize(bitmap: Bitmap, pageNumber: Int, geometry: PageGeometry, script: OcrScript): IndexedPage {
                consumedBitmap = bitmap
                throw expected
            }
        }
        try {
            try {
                OfflineDocumentIndexer(ocrEngine = engine).index("application/pdf", bytes, OcrScript.LATIN)
                fail("Recognition cancellation must not become a successful failed-page result")
            } catch (actual: CancellationException) {
                assertEquals(expected.message, actual.message)
            }
            assertTrue(requireNotNull(consumedBitmap).isRecycled)
        } finally { bytes.fill(0) }
    }

    private fun recognized(pageNumber: Int, geometry: PageGeometry, script: OcrScript) = IndexedPage(
        pageNumber, "SYNTHETIC 00042", geometry, script.extractionMethod(), "synthetic-contract-test-v1",
        listOf(SourceTextSpan(0, 15, EvidenceBox(0f, 0f, geometry.width.toFloat(), geometry.height.toFloat()), SourceSpanKind.LINE)),
        PageIndexState.READY,
    )

    private fun pdfBytes(nativeFirstPage: Boolean, blankPages: Int): ByteArray {
        val document = PdfDocument()
        val output = ByteArrayOutputStream()
        try {
            if (nativeFirstPage) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 400, 1).create())
                page.canvas.drawText("Native policy 00042", 30f, 100f, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f })
                document.finishPage(page)
            }
            repeat(blankPages) { index ->
                val number = index + 1 + if (nativeFirstPage) 1 else 0
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 400, number).create())
                page.canvas.drawColor(Color.WHITE)
                document.finishPage(page)
            }
            document.writeTo(output)
            return output.toByteArray()
        } finally { document.close() }
    }
}

package com.privateassistant.platform.documents

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Extracts only the supplied original in memory. Caller owns/wipes [bytes]; no
 * plaintext file, credentials, whole vault, downloads or remote processing.
 * Cancellation is checked between native tasks. An in-flight PDF/OCR task settles
 * before bitmap recycling; a hung native task still requires future isolation.
 */
class OfflineDocumentIndexer(
    private val parser: MemoryDocumentParser = MemoryDocumentParser(),
    private val ocrEngine: OcrEngine? = null,
) {
    suspend fun index(mimeType: String, bytes: ByteArray, script: OcrScript? = null): DocumentIndexResult = withContext(Dispatchers.IO) {
        ensureActive()
        val type = mimeType.substringBefore(';').trim().lowercase()
        val parsed = parser.parse(type, bytes)
        ensureActive()
        val pages = mutableListOf<IndexedPage>()
        var totalChars = 0
        var totalSpans = 0
        for (page in parsed.pages) {
            ensureActive()
            val geometry = parsed.geometry.firstOrNull { it.pageNumber == page.pageNumber }
            val indexed = if (page.pageNumber !in parsed.pagesRequiringOcr) {
                val method = if (type == "text/plain") ExtractionMethod.UTF8_TEXT else ExtractionMethod.PDF_NATIVE
                IndexedPage(page.pageNumber, page.text, geometry, method,
                    if (method == ExtractionMethod.UTF8_TEXT) "utf8-strict-v1" else "pdfiumandroid-1.0.35/native-v1",
                    if (page.text.isEmpty()) emptyList() else listOf(SourceTextSpan(0, page.text.length, null, SourceSpanKind.BLOCK)),
                    if (page.text.isBlank()) PageIndexState.EMPTY else PageIndexState.READY)
            } else if (script == null) {
                IndexedPage(page.pageNumber, "", geometry, null, "not-extracted-v1", state = PageIndexState.NEEDS_OCR)
            } else if (ocrEngine == null) {
                failedPage(page.pageNumber, geometry, script, "OCR_UNAVAILABLE")
            } else {
                try {
                    val recognized = when (type) {
                        "application/pdf" -> parser.withRenderedPdfPage(bytes, page.pageNumber) { bitmap ->
                            ocrEngine.recognize(bitmap, page.pageNumber, requireNotNull(geometry), script)
                        }
                        "image/jpeg", "image/png" -> parser.withDecodedImage(bytes, type) { bitmap, orientedGeometry ->
                            ocrEngine.recognize(bitmap, page.pageNumber, orientedGeometry, script)
                        }
                        else -> error("Unsupported OCR source")
                    }
                    OcrResultValidator.validated(recognized, page.pageNumber, requireNotNull(geometry), script)
                        ?: failedPage(page.pageNumber, geometry, script, "OCR_INVALID_RESULT")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: DocumentParseException) {
                    failedPage(page.pageNumber, geometry, script, "OCR_${failure.failure.name}")
                } catch (_: OcrRecognitionException) {
                    failedPage(page.pageNumber, geometry, script, "OCR_FAILED")
                } catch (_: RuntimeException) {
                    failedPage(page.pageNumber, geometry, script, "OCR_FAILED")
                }
            }
            ensureActive()
            val limitFailure = when {
                indexed.text.length > MemoryDocumentParser.MAX_TEXT_CHARS - totalChars -> "INDEX_TEXT_LIMIT"
                indexed.spans.size > DocumentIndexLimits.MAX_SPANS_PER_DOCUMENT - totalSpans -> "INDEX_SPAN_LIMIT"
                else -> null
            }
            if (limitFailure != null) {
                pages += IndexedPage(page.pageNumber, "", geometry, indexed.method, indexed.extractorVersion,
                    state = PageIndexState.FAILED, errorCode = limitFailure)
            } else {
                totalChars += indexed.text.length
                totalSpans += indexed.spans.size
                pages += indexed
            }
        }
        DocumentIndexResult(pages.toList())
    }

    private fun failedPage(pageNumber: Int, geometry: PageGeometry?, script: OcrScript, code: String) =
        IndexedPage(pageNumber, "", geometry, if (ocrEngine == null) null else script.extractionMethod(), "not-extracted-v1",
            state = PageIndexState.FAILED, errorCode = code)
}

class OcrRecognitionException : Exception("OCR failed")

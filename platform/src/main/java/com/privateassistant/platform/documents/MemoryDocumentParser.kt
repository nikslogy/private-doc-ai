package com.privateassistant.platform.documents

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import com.privateassistant.core.PageText
import io.legere.pdfiumandroid.LoggerInterface
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPasswordException
import io.legere.pdfiumandroid.PdfiumCore
import io.legere.pdfiumandroid.util.Config
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Semaphore
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

data class PageGeometry(val pageNumber: Int, val width: Int, val height: Int)

data class ParsedDocument(
    val pageCount: Int,
    val pages: List<PageText>,
    val geometry: List<PageGeometry> = emptyList(),
    /** OCR has not been performed; an empty page must never be reported as indexed. */
    val pagesRequiringOcr: Set<Int> = emptySet(),
)

enum class ParseFailure {
    UNSUPPORTED_TYPE, INVALID_INPUT, PASSWORD_REQUIRED, CORRUPT_PDF,
    BYTE_LIMIT, PAGE_LIMIT, TEXT_LIMIT, PIXEL_LIMIT, TIME_LIMIT,
}

class DocumentParseException(val failure: ParseFailure) : Exception("Document parsing failed: ${failure.name}")

/**
 * Serializes PDF, image decoding, OCR and model work. Acquire only from a worker.
 * Blocks must not suspend or recursively acquire this gate. OCR waits for the actual
 * native task completion inside the block, even after cooperative cancellation.
 */
internal object HeavyWorkGate {
    private val semaphore = Semaphore(1, true)

    fun <T> run(block: () -> T): T {
        semaphore.acquire()
        try {
            return block()
        } finally {
            semaphore.release()
        }
    }
}

/**
 * Bounded, synchronous M0 parser. All original plaintext stays in the supplied memory buffer;
 * this adapter creates no temporary file and retains no original buffer after returning.
 * The caller owns the buffer and should clear it in finally after this call.
 * Run off the main thread. Native calls cannot be forcibly interrupted in this process.
 */
class MemoryDocumentParser {
    fun parse(mimeType: String, bytes: ByteArray): ParsedDocument {
        requireWorkerThread()
        checkBytes(bytes)
        return when (mimeType.substringBefore(';').trim().lowercase()) {
            "text/plain" -> parseText(bytes)
            "application/pdf" -> HeavyWorkGate.run { parsePdf(bytes) }
            "image/jpeg", "image/png" -> HeavyWorkGate.run {
                parseImage(bytes, mimeType.substringBefore(';').trim().lowercase())
            }
            else -> fail(ParseFailure.UNSUPPORTED_TYPE)
        }
    }

    /** The bitmap is oriented, bounded to 4 MP, and recycled after synchronous consumption. */
    fun <T> withDecodedImage(bytes: ByteArray, mimeType: String, consume: (Bitmap, PageGeometry) -> T): T {
        requireWorkerThread()
        checkBytes(bytes)
        val type = mimeType.substringBefore(';').trim().lowercase()
        return HeavyWorkGate.run { MemoryImageDecoder.withDecodedImage(bytes, type, consume) }
    }

    /** The callback may consume the bitmap synchronously; it is recycled before return. */
    fun <T> withRenderedPdfPage(
        bytes: ByteArray,
        pageNumber: Int,
        longestEdge: Int = 2048,
        consume: (Bitmap) -> T,
    ): T {
        requireWorkerThread()
        checkBytes(bytes)
        checkPdfSignature(bytes)
        if (pageNumber < 1 || longestEdge !in 1..4096) fail(ParseFailure.INVALID_INPUT)
        return HeavyWorkGate.run {
            val started = System.nanoTime()
            checkWorkBudget(started)
            openPdf(bytes).use { document ->
                checkWorkBudget(started)
                val pageCount = document.getPageCount()
                if (pageCount !in 1..MAX_PDF_PAGES) fail(ParseFailure.PAGE_LIMIT)
                if (pageNumber > pageCount) fail(ParseFailure.INVALID_INPUT)
                document.openPage(pageNumber - 1).use { page ->
                    checkWorkBudget(started)
                    val sourceWidth = page.getPageWidthPoint()
                    val sourceHeight = page.getPageHeightPoint()
                    checkWorkBudget(started)
                    if (sourceWidth <= 0 || sourceHeight <= 0) fail(ParseFailure.INVALID_INPUT)
                    val scale = min(
                        longestEdge.toDouble() / maxOf(sourceWidth, sourceHeight),
                        sqrt(MAX_RENDER_PIXELS.toDouble() / (sourceWidth.toLong() * sourceHeight)),
                    )
                    val width = floor(sourceWidth * scale).toInt().coerceAtLeast(1)
                    val height = floor(sourceHeight * scale).toInt().coerceAtLeast(1)
                    if (width.toLong() * height > MAX_RENDER_PIXELS) fail(ParseFailure.PIXEL_LIMIT)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.renderPageBitmap(bitmap, 0, 0, width, height, false)
                        checkWorkBudget(started)
                        consume(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun parseText(bytes: ByteArray): ParsedDocument {
        val decoded = try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val characters = CharBuffer.allocate(MAX_TEXT_CHARS + 2)
            val result = decoder.decode(ByteBuffer.wrap(bytes), characters, true)
            if (result.isOverflow) fail(ParseFailure.TEXT_LIMIT)
            if (result.isError) result.throwException()
            val flushed = decoder.flush(characters)
            if (flushed.isOverflow) fail(ParseFailure.TEXT_LIMIT)
            if (flushed.isError) flushed.throwException()
            characters.flip()
            characters.toString().removePrefix("\uFEFF")
        } catch (_: java.nio.charset.CharacterCodingException) {
            fail(ParseFailure.INVALID_INPUT)
        }
        if (decoded.length > MAX_TEXT_CHARS) fail(ParseFailure.TEXT_LIMIT)
        if ('\u0000' in decoded) fail(ParseFailure.INVALID_INPUT)
        return ParsedDocument(1, listOf(PageText(1, decoded)))
    }

    private fun parseImage(bytes: ByteArray, mimeType: String): ParsedDocument {
        return MemoryImageDecoder.withDecodedImage(bytes, mimeType) { _, geometry ->
            ParsedDocument(1, listOf(PageText(1, "")), listOf(geometry), setOf(1))
        }
    }

    private fun parsePdf(bytes: ByteArray): ParsedDocument {
        return try {
            parseNativePdf(bytes)
        } catch (_: PdfPasswordException) {
            fail(ParseFailure.PASSWORD_REQUIRED)
        } catch (_: IOException) {
            fail(ParseFailure.CORRUPT_PDF)
        }
    }

    private fun parseNativePdf(bytes: ByteArray): ParsedDocument {
        checkPdfSignature(bytes)
        val started = System.nanoTime()
        val pages = mutableListOf<PageText>()
        val geometry = mutableListOf<PageGeometry>()
        val needsOcr = mutableSetOf<Int>()
        var totalChars = 0
        checkWorkBudget(started)
        val parsed = openPdf(bytes).use { document ->
            checkWorkBudget(started)
            val count = document.getPageCount()
            if (count !in 1..MAX_PDF_PAGES) fail(ParseFailure.PAGE_LIMIT)
            for (index in 0 until count) {
                checkWorkBudget(started)
                document.openPage(index).use { page ->
                    checkWorkBudget(started)
                    val width = page.getPageWidthPoint()
                    val height = page.getPageHeightPoint()
                    if (width <= 0 || height <= 0) fail(ParseFailure.INVALID_INPUT)
                    geometry += PageGeometry(index + 1, width, height)
                    page.openTextPage().use { textPage ->
                        checkWorkBudget(started)
                        val countChars = textPage.textPageCountChars()
                        if (countChars < 0) fail(ParseFailure.INVALID_INPUT)
                        if (countChars > MAX_PAGE_TEXT_CHARS || countChars > MAX_TEXT_CHARS - totalChars) fail(ParseFailure.TEXT_LIMIT)
                        val text = if (countChars == 0) "" else textPage.textPageGetText(0, countChars)
                            ?: fail(ParseFailure.INVALID_INPUT)
                        checkWorkBudget(started)
                        if (text.length > MAX_PAGE_TEXT_CHARS || text.length > MAX_TEXT_CHARS - totalChars) fail(ParseFailure.TEXT_LIMIT)
                        totalChars += text.length
                        pages += PageText(index + 1, text)
                        if (text.isBlank()) needsOcr += index + 1
                    }
                }
                checkWorkBudget(started)
            }
            checkWorkBudget(started)
            ParsedDocument(count, pages.toList(), geometry.toList(), needsOcr.toSet())
        }
        checkWorkBudget(started)
        return parsed
    }

    private fun openPdf(bytes: ByteArray): PdfDocument = try {
        nativeCore().newDocument(bytes)
    } catch (_: PdfPasswordException) {
        fail(ParseFailure.PASSWORD_REQUIRED)
    } catch (_: IOException) {
        fail(ParseFailure.CORRUPT_PDF)
    }

    private fun nativeCore(): PdfiumCore {
        // This version's lazy init can wait indefinitely after a missing-library error.
        // Fail on the caller's worker thread before constructing the native wrapper.
        System.loadLibrary("pdfium")
        System.loadLibrary("pdfiumandroid")
        return PdfiumCore(config = Config(logger = SilentPdfLogger))
    }

    private fun checkBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) fail(ParseFailure.INVALID_INPUT)
        if (bytes.size > MAX_BYTES) fail(ParseFailure.BYTE_LIMIT)
    }

    private fun checkWorkBudget(started: Long) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        if (System.nanoTime() - started > PARSE_BUDGET_NANOS) fail(ParseFailure.TIME_LIMIT)
    }

    private fun checkPdfSignature(bytes: ByteArray) {
        if (bytes.size < 5 || !bytes.copyOfRange(0, 5).contentEquals(PDF_SIGNATURE)) fail(ParseFailure.INVALID_INPUT)
    }

    private fun requireWorkerThread() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Parsing must run on a background thread" }
    }

    private fun fail(reason: ParseFailure): Nothing = throw DocumentParseException(reason)

    companion object {
        const val MAX_BYTES = 25 * 1024 * 1024
        const val MAX_PDF_PAGES = 50
        const val MAX_SOURCE_PIXELS = 20_000_000L
        const val MAX_RENDER_PIXELS = 4_000_000L
        const val MAX_TEXT_CHARS = 1_000_000
        const val MAX_PAGE_TEXT_CHARS = 100_000
        private const val PARSE_BUDGET_NANOS = 15_000_000_000L
        private val PDF_SIGNATURE = "%PDF-".toByteArray(Charsets.US_ASCII)
    }
}

private object SilentPdfLogger : LoggerInterface {
    override fun d(tag: String, message: String?) = Unit
    override fun e(tag: String, t: Throwable?, message: String?) = Unit
}

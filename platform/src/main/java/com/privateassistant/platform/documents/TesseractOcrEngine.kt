package com.privateassistant.platform.documents

import android.graphics.Bitmap
import android.os.Looper
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Locally supplied, verified public language data and memory-only page recognition.
 * The caller owns the bitmap and serializes heavy work through the parser gate.
 * A new native engine is destroyed for every page; no adaptive state crosses pages.
 *
 * stop() is cooperative and supported by this binding only during getHOCRText().
 * The watchdog requests it on cancellation/deadline and is joined before native
 * cleanup. Initialization/segmentation and a hung native call cannot be forcibly
 * terminated safely in this process; a hard deadline still requires isolation.
 */
class TesseractOcrEngine(
    private val modelDirectory: () -> File,
    private val cancellationRequested: () -> Boolean = { false },
) : OcrEngine {
    override fun recognize(bitmap: Bitmap, pageNumber: Int, geometry: PageGeometry, script: OcrScript): IndexedPage {
        check(Looper.myLooper() != Looper.getMainLooper()) { "OCR requires a worker thread" }
        if (pageNumber < 1 || geometry.pageNumber != pageNumber || geometry.width <= 0 || geometry.height <= 0 ||
            bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            throw DocumentParseException(ParseFailure.INVALID_INPUT)
        }
        if (bitmap.width.toLong() * bitmap.height > MemoryDocumentParser.MAX_RENDER_PIXELS) {
            throw DocumentParseException(ParseFailure.PIXEL_LIMIT)
        }
        val started = System.nanoTime()
        checkWork(started)
        // This callback can install/hash only public traineddata. It receives no pixels/text.
        val directory = modelDirectory()
        checkWork(started)
        val api = TessBaseAPI()
        try {
            api.setDebug(false)
            val language = when (script) {
                OcrScript.LATIN -> "eng"
                OcrScript.DEVANAGARI -> "hin+mar+eng"
            }
            if (!api.init(directory.absolutePath, language, TessBaseAPI.OEM_LSTM_ONLY, PRIVATE_OPTIONS)) {
                throw OcrRecognitionException()
            }
            // Unknown/ignored options fail closed before any private image reaches native code.
            if (PRIVATE_OPTIONS.any { (name, expected) -> api.getVariable(name) != expected }) {
                throw OcrRecognitionException()
            }
            // The legacy classifier is absent from some LSTM-only builds. When compiled
            // in, explicitly forbid saving its adaptive templates during native cleanup.
            if (api.getVariable("classify_save_adapted_templates") != null &&
                (!api.setVariable("classify_save_adapted_templates", "0") ||
                    api.getVariable("classify_save_adapted_templates") != "0")) {
                throw OcrRecognitionException()
            }
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            checkWork(started)
            api.setImage(bitmap)
            checkWork(started)
            recognizeWithCancellation(api, pageNumber, started)
            checkWork(started)
            return collectLines(api, bitmap, pageNumber, geometry, script, started)
        } finally {
            // The recognition method has settled and its watcher has joined before recycle.
            api.recycle()
        }
    }

    private fun recognizeWithCancellation(api: TessBaseAPI, pageNumber: Int, started: Long) {
        val finished = AtomicBoolean(false)
        val stopRequested = AtomicBoolean(false)
        val stopFailed = AtomicBoolean(false)
        val owner = Thread.currentThread()
        val watcher = Thread({
            while (!finished.get()) {
                if (owner.isInterrupted || cancelled() || expired(started)) {
                    stopRequested.set(true)
                    // Native initialization at recognition start resets its stop flag. Repeat
                    // while active so cancellation immediately before that reset is not lost.
                    try {
                        api.stop()
                    } catch (_: RuntimeException) {
                        stopFailed.set(true)
                        return@Thread
                    }
                }
                try {
                    Thread.sleep(STOP_POLL_MILLIS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "local-ocr-cancellation").apply { isDaemon = true }
        watcher.start()
        try {
            // The binding's supported interruptible entry point. Markup stays transient;
            // evidence below is built from exact iterator lines, never from HTML offsets.
            val markup = api.getHOCRText(pageNumber - 1)
            checkWork(started)
            if (stopRequested.get() || stopFailed.get() || markup == null) throw OcrRecognitionException()
            if (markup.length > MAX_HOCR_CHARS) throw DocumentParseException(ParseFailure.TEXT_LIMIT)
        } finally {
            finished.set(true)
            watcher.interrupt()
            var interrupted = false
            while (watcher.isAlive) {
                try {
                    watcher.join()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) owner.interrupt()
        }
    }

    private fun collectLines(
        api: TessBaseAPI,
        bitmap: Bitmap,
        pageNumber: Int,
        geometry: PageGeometry,
        script: OcrScript,
        started: Long,
    ): IndexedPage {
        val text = StringBuilder()
        val spans = mutableListOf<SourceTextSpan>()
        val iterator = api.resultIterator
        if (iterator != null) {
            try {
                iterator.begin()
                var visited = 0
                do {
                    checkWork(started)
                    if (++visited > MAX_LINES) throw DocumentParseException(ParseFailure.TEXT_LIMIT)
                    val line = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)?.trimEnd('\r', '\n')
                        ?: ""
                    if (line.isBlank()) continue
                    val separator = if (text.isEmpty()) 0 else 1
                    if (line.length > MemoryDocumentParser.MAX_PAGE_TEXT_CHARS - text.length - separator) {
                        throw DocumentParseException(ParseFailure.TEXT_LIMIT)
                    }
                    if ('\u0000' in line) throw OcrRecognitionException()
                    val rectangle = iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                    if (rectangle == null || rectangle.size != 4 || rectangle[0] < 0 || rectangle[1] < 0 ||
                        rectangle[2] <= rectangle[0] || rectangle[3] <= rectangle[1] ||
                        rectangle[2] > bitmap.width || rectangle[3] > bitmap.height) {
                        throw OcrRecognitionException()
                    }
                    if (separator != 0) text.append('\n')
                    val start = text.length
                    text.append(line)
                    spans += SourceTextSpan(start, text.length, EvidenceBox(
                        sourceX(rectangle[0], bitmap, geometry), sourceY(rectangle[1], bitmap, geometry),
                        sourceX(rectangle[2], bitmap, geometry), sourceY(rectangle[3], bitmap, geometry),
                    ), SourceSpanKind.LINE)
                } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
            } finally {
                iterator.delete()
            }
        }
        checkWork(started)
        return IndexedPage(pageNumber, text.toString(), geometry, script.extractionMethod(),
            extractorVersion(script), spans.toList(), if (text.isEmpty()) PageIndexState.EMPTY else PageIndexState.READY)
    }

    private fun checkWork(started: Long) {
        if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("OCR cancelled")
        if (expired(started)) throw DocumentParseException(ParseFailure.TIME_LIMIT)
    }

    private fun cancelled(): Boolean = try { cancellationRequested() } catch (_: RuntimeException) { true }
    private fun expired(started: Long): Boolean = System.nanoTime() - started >= OCR_BUDGET_NANOS

    private fun extractorVersion(script: OcrScript): String =
        "tesseract4android-4.9.0/tessdata-fast-eng-hin-mar-v1/lines-v1/" + when (script) {
            OcrScript.LATIN -> "eng"
            OcrScript.DEVANAGARI -> "hin+mar+eng"
        }

    private fun sourceX(pixel: Int, bitmap: Bitmap, geometry: PageGeometry): Float =
        (pixel.toDouble() * geometry.width / bitmap.width).toFloat().coerceIn(0f, geometry.width.toFloat())

    private fun sourceY(pixel: Int, bitmap: Bitmap, geometry: PageGeometry): Float =
        (pixel.toDouble() * geometry.height / bitmap.height).toFloat().coerceIn(0f, geometry.height.toFloat())

    companion object {
        private const val OCR_BUDGET_NANOS = 30_000_000_000L
        private const val STOP_POLL_MILLIS = 25L
        private const val MAX_LINES = 10_000
        private const val MAX_HOCR_CHARS = 2_000_000
        // Supported Tesseract 5.5.1 initialization parameters, then verified by name.
        // No output renderer, config file, private path or input/output filename is used.
        private val PRIVATE_OPTIONS = mapOf(
            "debug_file" to "/dev/null",
            "tessedit_write_params_to_file" to "",
            "tessedit_write_images" to "0",
            "tessedit_dump_pageseg_images" to "0",
            "interactive_display_mode" to "0",
            "tessedit_resegment_from_boxes" to "0",
            "tessedit_resegment_from_line_boxes" to "0",
            "tessedit_train_from_boxes" to "0",
            "tessedit_train_line_recognizer" to "0",
            "tessedit_ambigs_training" to "0",
            "tessedit_create_txt" to "0",
            "tessedit_create_hocr" to "0",
            "tessedit_create_alto" to "0",
            "tessedit_create_page_xml" to "0",
            "tessedit_create_lstmbox" to "0",
            "tessedit_create_tsv" to "0",
            "tessedit_create_wordstrbox" to "0",
            "tessedit_create_pdf" to "0",
            "tessedit_create_boxfile" to "0",
        )
    }
}

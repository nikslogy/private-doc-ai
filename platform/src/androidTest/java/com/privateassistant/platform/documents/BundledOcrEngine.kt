package com.privateassistant.platform.documents

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Rect
import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.common.MlKit
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutionException

/** Development-only real SDK probe. Diagnostics prohibit production inclusion. */
internal class BundledOcrEngine(context: Context) : OcrEngine {
    init {
        check(context.checkSelfPermission(Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
            "Development OCR probe requires a process without INTERNET permission"
        }
        MlKit.initialize(context)
    }
    /** Caller holds HeavyWorkGate and owns the bitmap until this method settles. */
    override fun recognize(bitmap: Bitmap, pageNumber: Int, geometry: PageGeometry, script: OcrScript): IndexedPage {
        val method = script.extractionMethod()
        val recognizer = when (script) {
            OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            OcrScript.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        }
        return try {
            val started = System.nanoTime()
            val result = SettledTaskAwaiter.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
            // A budget rejects late output; ML Kit has no supported hard cancellation here.
            if (System.nanoTime() - started > OCR_BUDGET_NANOS) throw DocumentParseException(ParseFailure.TIME_LIMIT)
            val text = result.text
            if (text.length > MemoryDocumentParser.MAX_PAGE_TEXT_CHARS) throw DocumentParseException(ParseFailure.TEXT_LIMIT)
            val spans = mutableListOf<SourceTextSpan>()
            var nextBlock = 0
            for (block in result.textBlocks) {
                if (block.text.isEmpty()) continue
                val start = text.indexOf(block.text, nextBlock)
                // Never manufacture offsets if a recognizer's aggregate string differs.
                if (start < 0) continue
                val end = start + block.text.length
                spans += SourceTextSpan(start, end, box(block.boundingBox, bitmap, geometry), SourceSpanKind.BLOCK)
                var nextLine = start
                for (line in block.lines) {
                    if (line.text.isEmpty()) continue
                    val lineStart = text.indexOf(line.text, nextLine)
                    if (lineStart < start || lineStart + line.text.length > end) continue
                    spans += SourceTextSpan(lineStart, lineStart + line.text.length, box(line.boundingBox, bitmap, geometry), SourceSpanKind.LINE)
                    nextLine = lineStart + line.text.length
                }
                nextBlock = end
            }
            IndexedPage(pageNumber, text, geometry, method, script.extractorVersion(), spans.toList(),
                if (text.isBlank()) PageIndexState.EMPTY else PageIndexState.READY)
        } finally {
            recognizer.close()
        }
    }

    private fun box(rect: Rect?, bitmap: Bitmap, geometry: PageGeometry): EvidenceBox? {
        rect ?: return null
        val sx = geometry.width.toFloat() / bitmap.width
        val sy = geometry.height.toFloat() / bitmap.height
        return EvidenceBox(
            rect.left.coerceIn(0, bitmap.width) * sx,
            rect.top.coerceIn(0, bitmap.height) * sy,
            rect.right.coerceIn(0, bitmap.width) * sx,
            rect.bottom.coerceIn(0, bitmap.height) * sy,
        )
    }

    companion object { private const val OCR_BUDGET_NANOS = 30_000_000_000L }
}

/**
 * Coroutine cancellation must not recycle an image still being read by ML Kit.
 * Tasks.await blocks only an IO worker; interrupted waits continue to completion
 * before restoring the interrupt flag. No key/database/session is retained here.
 */
internal object SettledTaskAwaiter {
    fun <T> await(task: Task<T>): T {
        var interrupted = false
        try {
            while (true) {
                try {
                    return Tasks.await(task)
                } catch (_: InterruptedException) {
                    interrupted = true
                } catch (_: ExecutionException) {
                    throw OcrRecognitionException()
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

}

private fun OcrScript.extractorVersion(): String = when (this) {
    OcrScript.LATIN -> "mlkit-latin-16.0.1/spans-v1"
    OcrScript.DEVANAGARI -> "mlkit-devanagari-16.0.1/spans-v1"
}


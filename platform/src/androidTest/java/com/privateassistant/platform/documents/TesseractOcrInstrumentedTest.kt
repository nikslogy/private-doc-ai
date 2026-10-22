package com.privateassistant.platform.documents

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Actual local engine and pinned public models; every document is drawn synthetically. */
@RunWith(AndroidJUnit4::class)
class TesseractOcrInstrumentedTest {
    @Test
    fun englishRecognitionReturnsExactLineOffsetsAndOrientedSourceBoxes() = runBlocking {
        withContext(Dispatchers.IO) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            @Suppress("DEPRECATION")
            val startup = context.packageManager.resolveContentProvider(
                "${context.packageName}.androidx-startup", PackageManager.GET_META_DATA,
            )
            assertFalse("The installed synthetic test manifest must disable delayed profile writes",
                startup?.metaData?.containsKey("androidx.profileinstaller.ProfileInstallerInitializer") == true)
            val store = models()
            store.directory()
            val canary = "VAULTCANARY729451".toByteArray(Charsets.US_ASCII)
            val before = privateFiles(context, canary)
            val bitmap = image(listOf("POLICY 00042", "Premium 450.00", "VAULTCANARY729451"))
            try {
                val geometry = PageGeometry(1, 2400, 1000)
                val page = TesseractOcrEngine(modelDirectory = store::directory).recognize(bitmap, 1, geometry, OcrScript.LATIN)
                assertEquals(PageIndexState.READY, page.state)
                assertEquals(ExtractionMethod.OCR_LATIN, page.method)
                assertEquals(geometry, page.geometry)
                assertTrue("Actual OCR must read the synthetic policy identifier", "00042" in page.text)
                assertTrue("Actual OCR must read the synthetic premium", "450.00" in page.text)
                assertTrue("Actual OCR must read the private-file probe canary", "VAULTCANARY729451" in page.text)
                assertTrue(page.spans.isNotEmpty())
                page.spans.forEach { span ->
                    assertEquals(SourceSpanKind.LINE, span.kind)
                    assertTrue(page.text.substring(span.startUtf16, span.endUtf16).isNotBlank())
                    val box = requireNotNull(span.box)
                    assertTrue(box.left >= 0 && box.top >= 0 && box.right <= geometry.width && box.bottom <= geometry.height)
                    assertTrue(box.right > box.left && box.bottom > box.top)
                }
                assertFalse("The engine does not own the caller's bitmap", bitmap.isRecycled)
                assertEquals("Local OCR must not write document files or alter installed models", before, privateFiles(context, canary))
            } finally { bitmap.recycle() }
        }
    }

    @Test
    fun devanagariRecognitionReturnsActualSyntheticWordAndLineEvidence() = runBlocking {
        withContext(Dispatchers.IO) {
            val bitmap = image(listOf("विमा", "हप्ता १२३"))
            try {
                val page = engine().recognize(bitmap, 1, PageGeometry(1, 1200, 500), OcrScript.DEVANAGARI)
                assertEquals(PageIndexState.READY, page.state)
                assertEquals(ExtractionMethod.OCR_DEVANAGARI, page.method)
                assertTrue("Actual local OCR must recognize a synthetic Devanagari word",
                    "विमा" in page.text || "हप्ता" in page.text)
                assertTrue(page.spans.any { it.kind == SourceSpanKind.LINE && it.box != null })
                page.spans.forEach { span ->
                    assertTrue(span.startUtf16 >= 0 && span.endUtf16 <= page.text.length && span.endUtf16 > span.startUtf16)
                }
            } finally { bitmap.recycle() }
        }
    }

    @Test
    fun blankPageIsEmptyAndFreshRecognitionDoesNotRetainPreviousText() = runBlocking {
        withContext(Dispatchers.IO) {
            val recognizer = engine()
            val written = image(listOf("SYNTHETIC CANARY 00042"))
            val blank = image(emptyList())
            try {
                assertTrue("00042" in recognizer.recognize(written, 1, PageGeometry(1, 1200, 500), OcrScript.LATIN).text)
                val page = recognizer.recognize(blank, 1, PageGeometry(1, 1200, 500), OcrScript.LATIN)
                assertEquals(PageIndexState.EMPTY, page.state)
                assertEquals("", page.text)
                assertTrue(page.spans.isEmpty())
            } finally { written.recycle(); blank.recycle() }
        }
    }

    @Test
    fun mixedPdfKeepsNativeTextAndUsesActualOcrOnlyForRasterPage() = runBlocking {
        val pdf = PdfDocument()
        val raster = image(listOf("RASTER POLICY 00042"))
        val output = ByteArrayOutputStream()
        try {
            val native = pdf.startPage(PdfDocument.PageInfo.Builder(600, 400, 1).create())
            native.canvas.drawText("Native premium 450.00", 30f, 100f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f })
            pdf.finishPage(native)
            val scanned = pdf.startPage(PdfDocument.PageInfo.Builder(1200, 500, 2).create())
            scanned.canvas.drawBitmap(raster, 0f, 0f, null)
            pdf.finishPage(scanned)
            pdf.writeTo(output)
        } finally { raster.recycle(); pdf.close() }
        val bytes = output.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        try {
            val pages = OfflineDocumentIndexer(ocrEngine = engine()).index("application/pdf", bytes, OcrScript.LATIN).pages
            assertEquals(2, pages.size)
            assertEquals(PageIndexState.READY, pages[0].state)
            assertEquals(ExtractionMethod.PDF_NATIVE, pages[0].method)
            assertTrue("450.00" in pages[0].text)
            assertEquals(PageIndexState.READY, pages[1].state)
            assertEquals(ExtractionMethod.OCR_LATIN, pages[1].method)
            assertTrue("00042" in pages[1].text)
            assertTrue(pages[1].spans.any { it.box != null })
            assertTrue(digest.contentEquals(MessageDigest.getInstance("SHA-256").digest(bytes)))
        } finally { bytes.fill(0) }
    }

    @Test
    fun cancellationBeforeRecognitionDoesNotLoadModelsOrConsumeBitmap() = runBlocking {
        withContext(Dispatchers.IO) {
            val bitmap = image(listOf("SYNTHETIC 00042"))
            var modelRequested = false
            try {
                val recognizer = TesseractOcrEngine(modelDirectory = {
                    modelRequested = true
                    error("Cancelled recognition must not load models")
                }, cancellationRequested = { true })
                try {
                    recognizer.recognize(bitmap, 1, PageGeometry(1, 1200, 500), OcrScript.LATIN)
                    fail("Expected cancellation")
                } catch (_: CancellationException) {
                    assertFalse(modelRequested)
                    assertFalse(bitmap.isRecycled)
                }
            } finally { bitmap.recycle() }
        }
    }

    @Test
    fun installedPublicModelTamperingIsRejectedBeforeNativeRecognition() {
        val store = models()
        assertTrue("Pinned model catalogue must be bundled; absence is not a skipped pass", store.available)
        val directory = store.directory()
        val model = java.io.File(directory, "tessdata/eng.traineddata")
        val originalByte = RandomAccessFile(model, "r").use { it.readUnsignedByte() }
        try {
            RandomAccessFile(model, "rw").use { file ->
                file.writeByte(originalByte xor 1)
                file.fd.sync()
            }
            try {
                store.directory()
                fail("Expected model digest rejection")
            } catch (_: OcrRecognitionException) {
                // Rejection is required; never silently load altered native inputs.
            }
        } finally {
            RandomAccessFile(model, "rw").use { file ->
                file.writeByte(originalByte)
                file.fd.sync()
            }
            assertEquals(directory, store.directory())
        }
    }

    private fun engine(): TesseractOcrEngine = TesseractOcrEngine(modelDirectory = models()::directory)

    private fun models(): ReviewedOcrModels = ReviewedOcrModels(
        InstrumentationRegistry.getInstrumentation().targetContext,
    )

    /** Narrow app-file canary check; not a full filesystem/log/traffic privacy proof. */
    private fun privateFiles(context: Context, canary: ByteArray): Map<String, String> {
        val snapshot = sortedMapOf<String, String>()
        var totalBytes = 0L
        for ((kind, root) in listOf("files" to context.filesDir, "cache" to context.cacheDir,
            "noBackup" to context.noBackupFilesDir)) {
            val visitedDirectories = mutableSetOf<String>()
            val tree = root.walkTopDown().onEnter { directory ->
                val canonical = directory.canonicalPath
                assertTrue("Private-file probe must not follow unexpected symbolic directories",
                    canonical == root.canonicalPath || canonical.startsWith(root.canonicalPath + File.separator))
                assertTrue("Private-file probe encountered a repeated directory", visitedDirectories.add(canonical))
                assertTrue("Private-file probe directory bound exceeded", visitedDirectories.size <= 128)
                true
            }
            for (file in tree.filter { it.isFile }) {
                assertTrue("Private-file probe must not follow unexpected symbolic paths",
                    file.canonicalPath.startsWith(root.canonicalPath + File.separator))
                assertTrue("Private-file probe size bound exceeded", file.length() <= 64L * 1024 * 1024)
                totalBytes += file.length()
                assertTrue("Private-file probe total bound exceeded", totalBytes <= 256L * 1024 * 1024)
                assertTrue("Private-file probe entry bound exceeded", snapshot.size < 512)
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024 + canary.size)
                var carried = 0
                var bytesRead = 0L
                file.inputStream().use { input ->
                    while (true) {
                        val count = input.read(buffer, carried, buffer.size - carried)
                        if (count == -1) break
                        bytesRead += count
                        assertTrue("Private-file probe read bound exceeded", bytesRead <= 64L * 1024 * 1024)
                        digest.update(buffer, carried, count)
                        val size = carried + count
                        for (start in 0..(size - canary.size)) {
                            if (buffer[start] != canary[0]) continue
                            var matched = 1
                            while (matched < canary.size && buffer[start + matched] == canary[matched]) matched++
                            assertFalse("Synthetic OCR canary was persisted in app-private files", matched == canary.size)
                        }
                        carried = minOf(canary.size - 1, size)
                        buffer.copyInto(buffer, 0, size - carried, size)
                    }
                }
                snapshot["$kind/${file.relativeTo(root).invariantSeparatorsPath}"] =
                    digest.digest().joinToString("") { "%02x".format(it) }
            }
        }
        return snapshot
    }

    private fun image(lines: List<String>): Bitmap = Bitmap.createBitmap(1200, 500, Bitmap.Config.ARGB_8888).also { bitmap ->
        bitmap.eraseColor(Color.WHITE)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 80f }
        lines.forEachIndexed { index, line -> canvas.drawText(line, 50f, 140f + index * 150f, paint) }
    }
}

package com.privateassistant.platform.documents

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic on-device probes; no network, private fixture or external model. */
@RunWith(AndroidJUnit4::class)
class OfflineIndexInstrumentedTest {
    @Test
    fun nativeAndUtf8TextKeepExactOffsetsAndOriginalDigest() = runBlocking {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("northstar-policy.pdf").use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        try {
            val result = OfflineDocumentIndexer().index("application/pdf", bytes, OcrScript.LATIN)
            assertEquals(2, result.pageCount)
            assertTrue(result.complete)
            assertTrue(result.pages.all { it.method == ExtractionMethod.PDF_NATIVE })
            result.pages.forEach(::assertEvidence)
            assertTrue(digest.contentEquals(MessageDigest.getInstance("SHA-256").digest(bytes)))
        } finally { bytes.fill(0) }
        val source = "Policy 00042\nहप्ता ₹१२३.४५\nविमा e\u0301 😀"
        val textBytes = source.toByteArray()
        try {
            val indexed = OfflineDocumentIndexer().index("text/plain", textBytes, OcrScript.DEVANAGARI).pages.single()
            assertEquals(source, indexed.text)
            assertEquals(ExtractionMethod.UTF8_TEXT, indexed.method)
            assertEquals(source.length, indexed.spans.single().endUtf16)
            assertEvidence(indexed)
        } finally { textBytes.fill(0) }
    }

    @Test
    fun bundledLatinAndDevanagariRecognizersProduceRealSourceEvidence() = runBlocking {
        for ((script, lines, expected) in listOf(
            Triple(OcrScript.LATIN, listOf("POLICY 00042", "Premium 450.00"), "00042"),
            Triple(OcrScript.DEVANAGARI, listOf("विमा", "हप्ता १२३"), "विमा"),
        )) {
            val bytes = imageBytes(lines)
            try {
                val page = probeIndexer().index("image/png", bytes, script).pages.single()
                assertEquals(PageIndexState.READY, page.state)
                assertEquals(script.extractionMethod(), page.method)
                assertTrue("Expected the synthetic script text from actual bundled OCR", expected in page.text)
                assertTrue(page.spans.any { it.kind == SourceSpanKind.BLOCK && it.box != null })
                assertTrue(page.spans.any { it.kind == SourceSpanKind.LINE && it.box != null })
                assertEvidence(page)
            } finally { bytes.fill(0) }
        }
    }

    @Test
    fun pdfNativeTextIsPreferredAndOnlyRasterPageUsesOcr() = runBlocking {
        val document = PdfDocument()
        val output = ByteArrayOutputStream()
        val scan = imageBitmap(listOf("RASTER POLICY 00042"))
        try {
            val native = document.startPage(PdfDocument.PageInfo.Builder(600, 400, 1).create())
            native.canvas.drawText("Native premium 450.00", 30f, 100f, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f })
            document.finishPage(native)
            val raster = document.startPage(PdfDocument.PageInfo.Builder(1200, 500, 2).create())
            raster.canvas.drawBitmap(scan, 0f, 0f, null)
            document.finishPage(raster)
            document.writeTo(output)
        } finally { scan.recycle(); document.close() }
        val bytes = output.toByteArray()
        try {
            val nativeOnly = OfflineDocumentIndexer().index("application/pdf", bytes).pages
            assertEquals(PageIndexState.READY, nativeOnly[0].state)
            assertEquals(PageIndexState.NEEDS_OCR, nativeOnly[1].state)
            val noApprovedOcr = OfflineDocumentIndexer().index("application/pdf", bytes, OcrScript.LATIN).pages
            assertEquals(PageIndexState.READY, noApprovedOcr[0].state)
            assertEquals(PageIndexState.FAILED, noApprovedOcr[1].state)
            assertEquals("OCR_UNAVAILABLE", noApprovedOcr[1].errorCode)
            val pages = probeIndexer().index("application/pdf", bytes, OcrScript.LATIN).pages
            assertEquals(2, pages.size)
            assertEquals(ExtractionMethod.PDF_NATIVE, pages[0].method)
            assertTrue("450.00" in pages[0].text)
            assertEquals(ExtractionMethod.OCR_LATIN, pages[1].method)
            assertTrue("00042" in pages[1].text)
            pages.forEach(::assertEvidence)
        } finally { bytes.fill(0) }
    }

    @Test
    fun emptyImageIsExplicitlyEmptyRatherThanFabricatedText() = runBlocking {
        val bytes = imageBytes(emptyList())
        try {
            val page = probeIndexer().index("image/png", bytes, OcrScript.LATIN).pages.single()
            assertEquals(PageIndexState.EMPTY, page.state)
            assertTrue(page.text.isBlank())
            assertTrue(page.spans.isEmpty())
        } finally { bytes.fill(0) }
    }

    @Test
    fun fullDecodeRejectsTruncatedOrCrcDamagedImagesAndKeepsOriginal() {
        val parser = MemoryDocumentParser()
        val bitmap = imageBitmap(listOf("SYNTHETIC"))
        try {
            for ((format, type) in listOf(Bitmap.CompressFormat.PNG to "image/png", Bitmap.CompressFormat.JPEG to "image/jpeg")) {
                val bytes = ByteArrayOutputStream().also { bitmap.compress(format, 90, it) }.toByteArray()
                try {
                    assertEquals(setOf(1), parser.parse(type, bytes).pagesRequiringOcr)
                    expectInvalid { parser.parse(type, bytes.copyOf(bytes.size - 2)) }
                    expectInvalid { parser.parse(type, bytes + byteArrayOf(0)) }
                    if (type == "image/png") {
                        val damaged = bytes.copyOf().apply { this[29] = (this[29].toInt() xor 1).toByte() }
                        expectInvalid { parser.parse(type, damaged) }
                        damaged.fill(0)
                    }
                } finally { bytes.fill(0) }
            }
        } finally { bitmap.recycle() }
    }

    @Test
    fun allEightExifOrientationsUseCorrectOrientedCoordinatesAndRecyclePixels() {
        val source = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            val paint = Paint()
            paint.color = Color.RED; drawRect(0f, 0f, 40f, 20f, paint)
            paint.color = Color.BLUE; drawRect(40f, 0f, 80f, 20f, paint)
            paint.color = Color.GREEN; drawRect(0f, 20f, 40f, 40f, paint)
            paint.color = Color.YELLOW; drawRect(40f, 20f, 80f, 40f, paint)
        }
        val jpeg = ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.JPEG, 100, it) }.toByteArray()
        source.recycle()
        val corners = listOf(
            listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW),
            listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
            listOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED),
            listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE),
            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
            listOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE),
            listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
            listOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN),
        )
        try {
            for (orientation in 1..8) {
                val bytes = withExifOrientation(jpeg, orientation)
                var consumed: Bitmap? = null
                try {
                    MemoryDocumentParser().withDecodedImage(bytes, "image/jpeg") { bitmap, geometry ->
                        consumed = bitmap
                        assertEquals(if (orientation >= 5) 40 else 80, geometry.width)
                        assertEquals(if (orientation >= 5) 80 else 40, geometry.height)
                        val observed = listOf(bitmap.getPixel(bitmap.width / 4, bitmap.height / 4),
                            bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 4),
                            bitmap.getPixel(bitmap.width / 4, bitmap.height * 3 / 4),
                            bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height * 3 / 4))
                        observed.zip(corners[orientation - 1]).forEach { (actual, expected) -> assertColorNear(expected, actual) }
                    }
                    assertTrue(requireNotNull(consumed).isRecycled)
                } finally { bytes.fill(0) }
            }
        } finally { jpeg.fill(0) }
    }

    private fun assertEvidence(page: IndexedPage) {
        assertEquals(PageIndexState.READY, page.state)
        assertFalse(page.text.isBlank())
        for (span in page.spans) {
            assertTrue(span.startUtf16 >= 0 && span.endUtf16 > span.startUtf16 && span.endUtf16 <= page.text.length)
            span.box?.let { box ->
                val geometry = requireNotNull(page.geometry)
                assertTrue(box.left >= 0 && box.top >= 0 && box.right <= geometry.width && box.bottom <= geometry.height)
                assertTrue(box.right >= box.left && box.bottom >= box.top)
            }
        }
    }

    private fun imageBitmap(lines: List<String>): Bitmap = Bitmap.createBitmap(1200, 500, Bitmap.Config.ARGB_8888).also { bitmap ->
        bitmap.eraseColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 80f }
        val canvas = Canvas(bitmap)
        lines.forEachIndexed { index, line -> canvas.drawText(line, 50f, 140f + index * 150, paint) }
    }

    private fun imageBytes(lines: List<String>): ByteArray {
        val bitmap = imageBitmap(lines)
        return try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
        finally { bitmap.recycle() }
    }

    private fun probeIndexer(): OfflineDocumentIndexer {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Real SDK probe requires a target process without INTERNET permission",
            instrumentation.targetContext.checkSelfPermission(Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED)
        // SDK discovery/resources belong to the test APK, never the production APK.
        return OfflineDocumentIndexer(ocrEngine = BundledOcrEngine(instrumentation.context))
    }

    private fun withExifOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val payload = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
            .put("Exif\u0000\u0000".toByteArray(Charsets.US_ASCII))
            .put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
            .putShort(1).putShort(0x0112).putShort(3).putInt(1).putShort(orientation.toShort()).putShort(0).putInt(0).array()
        return jpeg.copyOfRange(0, 2) + byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0, 34) + payload + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun assertColorNear(expected: Int, actual: Int) {
        assertTrue(kotlin.math.abs(Color.red(expected) - Color.red(actual)) < 40)
        assertTrue(kotlin.math.abs(Color.green(expected) - Color.green(actual)) < 40)
        assertTrue(kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) < 40)
    }

    private fun expectInvalid(action: () -> Unit) {
        try { action(); fail("Expected invalid complete image payload") }
        catch (failure: DocumentParseException) { assertEquals(ParseFailure.INVALID_INPUT, failure.failure) }
    }
}

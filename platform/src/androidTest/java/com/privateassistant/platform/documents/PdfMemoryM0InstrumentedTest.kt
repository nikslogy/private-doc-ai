package com.privateassistant.platform.documents

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.streamingaead.StreamingAeadKeyTemplates
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfMemoryM0InstrumentedTest {
    @Test
    fun encryptedPdfExtractsAndRendersFromBoundedMemory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val original = instrumentation.context.assets.open("northstar-policy.pdf").use { it.readBytes() }
        assertTrue(original.size <= MemoryDocumentParser.MAX_BYTES)
        val originalDigest = MessageDigest.getInstance("SHA-256").digest(original)
        StreamingAeadConfig.register()
        val primitive = KeysetHandle.generateNew(StreamingAeadKeyTemplates.AES256_GCM_HKDF_1MB)
            .getPrimitive(StreamingAead::class.java)
        val directory = File(instrumentation.targetContext.noBackupFilesDir, "m0-pdf-probe")
        check(directory.mkdirs() || directory.isDirectory)
        val ciphertext = File.createTempFile("object-", ".enc", directory)
        val aad = "synthetic-m0/revision-1/original/v1".toByteArray()
        var decrypted: ByteArray? = null
        try {
            primitive.newEncryptingStream(ciphertext.outputStream(), aad).use { it.write(original) }
            assertFalse(ciphertext.readBytes().toString(Charsets.ISO_8859_1).contains("NORTHSTAR POLICY"))
            decrypted = primitive.newDecryptingStream(ciphertext.inputStream(), aad).use { input ->
                val output = ByteArray(original.size)
                var total = 0
                while (total < output.size) {
                    val read = input.read(output, total, output.size - total)
                    check(read > 0)
                    total += read
                    check(total <= MemoryDocumentParser.MAX_BYTES)
                }
                check(input.read() == -1)
                output
            }
            assertTrue(originalDigest.contentEquals(MessageDigest.getInstance("SHA-256").digest(decrypted)))
            val parser = MemoryDocumentParser()
            val parsed = parser.parse("application/pdf", decrypted)
            assertEquals(2, parsed.pageCount)
            assertTrue(parsed.pages[0].text.contains("24 days"))
            assertTrue(parsed.pages[1].text.contains("450.00"))
            assertTrue(parsed.pagesRequiringOcr.isEmpty())
            assertEquals(listOf(1, 2), parsed.geometry.map { it.pageNumber })
            var rendered: Bitmap? = null
            parser.withRenderedPdfPage(decrypted, pageNumber = 1) { bitmap ->
                rendered = bitmap
                assertTrue(bitmap.width.toLong() * bitmap.height <= MemoryDocumentParser.MAX_RENDER_PIXELS)
                assertTrue(bitmap.height > bitmap.width)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue(pixels.any { it != Color.WHITE })
            }
            assertTrue(requireNotNull(rendered).isRecycled)
            assertEquals(listOf(ciphertext.name), directory.listFiles().orEmpty().map { it.name })
            assertTrue(originalDigest.contentEquals(MessageDigest.getInstance("SHA-256").digest(original)))
        } finally {
            decrypted?.fill(0)
            original.fill(0)
            ciphertext.delete()
            directory.delete()
        }
    }

    @Test
    fun malformedUtf8AndOversizedBytesAreRejected() {
        val parser = MemoryDocumentParser()
        expectFailure(ParseFailure.INVALID_INPUT) { parser.parse("text/plain", byteArrayOf(0xc3.toByte(), 0x28)) }
        expectFailure(ParseFailure.BYTE_LIMIT) { parser.parse("text/plain", ByteArray(MemoryDocumentParser.MAX_BYTES + 1)) }
        expectFailure(ParseFailure.INVALID_INPUT) { parser.parse("application/pdf", "plain text".toByteArray()) }
        val corrupt = InstrumentationRegistry.getInstrumentation().context.assets.open("corrupt.pdf").use { it.readBytes() }
        try {
            expectFailure(ParseFailure.CORRUPT_PDF) { parser.parse("application/pdf", corrupt) }
        } finally {
            corrupt.fill(0)
        }
    }

    @Test
    fun utf8TextPreservesEvidenceOffsets() {
        val text = "Policy 00042\nहप्ता ₹१२३.४५\nMarathi: विमा"
        assertEquals(text, MemoryDocumentParser().parse("text/plain", text.toByteArray()).pages.single().text)
    }

    private fun expectFailure(expected: ParseFailure, action: () -> Unit) {
        try {
            action()
            fail("Expected a bounded parsing failure")
        } catch (failure: DocumentParseException) {
            assertEquals(expected, failure.failure)
        }
    }
}

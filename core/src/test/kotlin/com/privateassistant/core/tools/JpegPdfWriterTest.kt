package com.privateassistant.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegPdfWriterTest {
    /** Synthetic frame-only JPEG: SOI, APP0 stub, SOF0 (w x h, n components), EOI. Not a decodable image. */
    private fun jpeg(width: Int, height: Int, components: Int = 3, sof: Int = 0xC0): ByteArray {
        val sofLength = 8 + 3 * components
        val bytes = mutableListOf(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x04, 0x41, 0x42,
            0xFF, sof, sofLength shr 8, sofLength and 0xFF, 8, height shr 8, height and 0xFF, width shr 8, width and 0xFF, components)
        repeat(components) { bytes += listOf(it + 1, 0x11, 0) }
        bytes += listOf(0xFF, 0xD9)
        return bytes.map { it.toByte() }.toByteArray()
    }

    @Test fun `jpeg frame facts are read and unsupported input is rejected`() {
        assertEquals(JpegInfo(640, 480, 3), JpegInfo.read(jpeg(640, 480)))
        assertEquals(JpegInfo(10, 20, 1), JpegInfo.read(jpeg(10, 20, components = 1, sof = 0xC2)))
        assertNull(JpegInfo.read(jpeg(10, 20, components = 4)))
        assertNull(JpegInfo.read("%PDF-1.4".toByteArray()))
        assertNull(JpegInfo.read(jpeg(10, 20).copyOf(12)))
    }

    @Test fun `pdf embeds each jpeg unchanged with a consistent xref`() {
        val first = jpeg(800, 600)
        val second = jpeg(300, 900, components = 1)
        val pdf = JpegPdfWriter.write(listOf(JpegPdfWriter.a4Fit(first, 800, 600), JpegPdfWriter.a4Fit(second, 300, 900, 18f)))
        val text = String(pdf, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4\n"))
        assertTrue(text.endsWith("%%EOF\n"))
        assertTrue(text.contains("/Count 2"))
        assertTrue(text.contains("/Filter /DCTDecode /Length ${first.size}"))
        assertTrue(text.contains("/ColorSpace /DeviceGray"))
        // Landscape image gets a landscape A4 page.
        assertTrue(text.contains("/MediaBox [0 0 841.89 595.28]"))
        assertTrue(indexOf(pdf, first) > 0 && indexOf(pdf, second) > 0)
        val startXref = text.substringAfterLast("startxref\n").substringBefore("\n").toInt()
        assertTrue(text.startsWith("xref\n0 9\n", startXref))
        val entries = text.substring(startXref).lines().drop(3).take(8)
        entries.forEachIndexed { index, line ->
            val offset = line.substring(0, 10).toInt()
            assertTrue("object ${index + 1}", text.startsWith("${index + 1} 0 obj\n", offset))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non jpeg pages are refused`() {
        JpegPdfWriter.write(listOf(JpegPdfWriter.Page(byteArrayOf(1, 2, 3), 100f, 100f)))
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int =
        (0..haystack.size - needle.size).firstOrNull { start -> needle.indices.all { haystack[start + it] == needle[it] } } ?: -1
}

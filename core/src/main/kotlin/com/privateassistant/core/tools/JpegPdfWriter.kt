package com.privateassistant.core.tools

import java.io.ByteArrayOutputStream
import java.util.Locale

/** Baseline/progressive JPEG frame header facts needed to embed the stream unchanged. */
data class JpegInfo(val width: Int, val height: Int, val components: Int) {
    companion object {
        /** Returns null for anything that is not a well-formed JPEG with a supported frame. */
        fun read(bytes: ByteArray): JpegInfo? {
            if (bytes.size < 4 || u(bytes[0]) != 0xFF || u(bytes[1]) != 0xD8) return null
            var offset = 2
            while (offset + 4 <= bytes.size) {
                if (u(bytes[offset]) != 0xFF) return null
                val marker = u(bytes[offset + 1])
                if (marker == 0xFF) { offset++; continue }
                if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { offset += 2; continue }
                if (marker == 0xD9 || marker == 0xDA) return null
                val length = u(bytes[offset + 2]) * 256 + u(bytes[offset + 3])
                if (length < 2 || offset + 2 + length > bytes.size) return null
                // SOF0..SOF15 except DHT(C4), JPG(C8) and DAC(CC).
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    if (length < 8) return null
                    val height = u(bytes[offset + 5]) * 256 + u(bytes[offset + 6])
                    val width = u(bytes[offset + 7]) * 256 + u(bytes[offset + 8])
                    val components = u(bytes[offset + 9])
                    if (width <= 0 || height <= 0 || components !in setOf(1, 3)) return null
                    return JpegInfo(width, height, components)
                }
                offset += 2 + length
            }
            return null
        }

        private fun u(value: Byte) = value.toInt() and 0xFF
    }
}

/**
 * Writes a minimal PDF 1.4 whose pages each show one JPEG image (DCTDecode passthrough).
 * Every output byte is produced here; no document text, metadata or producer string is added.
 */
object JpegPdfWriter {
    /** Placement is in PDF points (1/72 inch) with the origin at the page's lower-left corner. */
    class Page(
        val jpeg: ByteArray,
        val pageWidthPt: Float,
        val pageHeightPt: Float,
        val drawX: Float = 0f,
        val drawY: Float = 0f,
        val drawWidth: Float = pageWidthPt,
        val drawHeight: Float = pageHeightPt,
    ) {
        init {
            require(pageWidthPt > 0f && pageHeightPt > 0f && pageWidthPt <= 14_400f && pageHeightPt <= 14_400f)
            require(drawWidth > 0f && drawHeight > 0f)
        }
    }

    const val A4_WIDTH_PT = 595.28f
    const val A4_HEIGHT_PT = 841.89f

    /** Fits an image of the given pixel aspect onto an A4 page (portrait or landscape), centred. */
    fun a4Fit(jpeg: ByteArray, pixelWidth: Int, pixelHeight: Int, marginPt: Float = 0f): Page {
        require(pixelWidth > 0 && pixelHeight > 0)
        val landscape = pixelWidth > pixelHeight
        val pageW = if (landscape) A4_HEIGHT_PT else A4_WIDTH_PT
        val pageH = if (landscape) A4_WIDTH_PT else A4_HEIGHT_PT
        val scale = minOf((pageW - 2 * marginPt) / pixelWidth, (pageH - 2 * marginPt) / pixelHeight)
        val w = pixelWidth * scale
        val h = pixelHeight * scale
        return Page(jpeg, pageW, pageH, (pageW - w) / 2f, (pageH - h) / 2f, w, h)
    }

    fun write(pages: List<Page>): ByteArray {
        require(pages.isNotEmpty() && pages.size <= MAX_PAGES)
        val infos = pages.map { requireNotNull(JpegInfo.read(it.jpeg)) { "Each page must be a supported JPEG" } }
        val out = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        fun begin(number: Int) { check(offsets.size == number - 1); offsets += out.size(); ascii("$number 0 obj\n") }

        ascii("%PDF-1.4\n%")
        // A comment of high bytes marks the file as binary for transfer tools.
        out.write(byteArrayOf(0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
        val pageObjects = pages.indices.map { 3 + it * 3 }
        begin(1); ascii("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        begin(2); ascii("<< /Type /Pages /Count ${pages.size} /Kids [${pageObjects.joinToString(" ") { "$it 0 R" }}] >>\nendobj\n")
        pages.forEachIndexed { index, page ->
            val pageNumber = 3 + index * 3
            val info = infos[index]
            begin(pageNumber)
            ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(page.pageWidthPt)} ${n(page.pageHeightPt)}] " +
                "/Resources << /XObject << /Im0 ${pageNumber + 2} 0 R >> >> /Contents ${pageNumber + 1} 0 R >>\nendobj\n")
            val content = "q ${n(page.drawWidth)} 0 0 ${n(page.drawHeight)} ${n(page.drawX)} ${n(page.drawY)} cm /Im0 Do Q\n"
                .toByteArray(Charsets.US_ASCII)
            begin(pageNumber + 1)
            ascii("<< /Length ${content.size} >>\nstream\n"); out.write(content); ascii("endstream\nendobj\n")
            begin(pageNumber + 2)
            val colorSpace = if (info.components == 1) "/DeviceGray" else "/DeviceRGB"
            ascii("<< /Type /XObject /Subtype /Image /Width ${info.width} /Height ${info.height} " +
                "/ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode /Length ${page.jpeg.size} >>\nstream\n")
            out.write(page.jpeg); ascii("\nendstream\nendobj\n")
        }
        val xref = out.size()
        ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { ascii(String.format(Locale.ROOT, "%010d 00000 n \n", it)) }
        ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    /** Fixed overhead per page in [write], used when splitting a byte budget across pages. */
    const val OVERHEAD_PER_PAGE_BYTES = 520
    const val OVERHEAD_FIXED_BYTES = 200
    const val MAX_PAGES = 50

    private fun n(value: Float): String = String.format(Locale.ROOT, "%.2f", value)
}

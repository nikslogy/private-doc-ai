package com.privateassistant.platform.documents

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.CRC32

/** Validates framing and decodes bounded pixels without a plaintext temporary file. */
internal object MemoryImageDecoder {
    fun <T> withDecodedImage(bytes: ByteArray, mimeType: String, consume: (Bitmap, PageGeometry) -> T): T {
        val started = System.nanoTime()
        checkBudget(started)
        validateFraming(bytes, mimeType)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) fail(ParseFailure.INVALID_INPUT)
        if (bounds.outWidth.toLong() * bounds.outHeight > MemoryDocumentParser.MAX_SOURCE_PIXELS) fail(ParseFailure.PIXEL_LIMIT)
        val orientation = try {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: IOException) {
            fail(ParseFailure.INVALID_INPUT)
        }
        if (orientation !in 0..8) fail(ParseFailure.INVALID_INPUT)
        checkBudget(started)
        val swapped = orientation in 5..8
        val geometry = PageGeometry(1, if (swapped) bounds.outHeight else bounds.outWidth, if (swapped) bounds.outWidth else bounds.outHeight)
        var sampleSize = 1
        while (ceilDiv(bounds.outWidth, sampleSize).toLong() * ceilDiv(bounds.outHeight, sampleSize) > MemoryDocumentParser.MAX_RENDER_PIXELS) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: fail(ParseFailure.INVALID_INPUT)
        var oriented: Bitmap? = null
        try {
            if (decoded.width.toLong() * decoded.height > MemoryDocumentParser.MAX_RENDER_PIXELS) fail(ParseFailure.PIXEL_LIMIT)
            oriented = orient(decoded, orientation)
            checkBudget(started)
            return consume(oriented, geometry)
        } finally {
            if (oriented !== decoded) oriented?.recycle()
            decoded.recycle()
        }
    }

    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val values = when (orientation) {
            2 -> floatArrayOf(-1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            3 -> floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f)
            4 -> floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f)
            5 -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            6 -> floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            7 -> floatArrayOf(0f, -1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
            8 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
            else -> return bitmap
        }
        val matrix = Matrix().apply { setValues(values) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

    private fun validateFraming(bytes: ByteArray, mimeType: String) {
        when (mimeType) {
            "image/png" -> validatePng(bytes)
            "image/jpeg" -> validateJpeg(bytes)
            else -> fail(ParseFailure.UNSUPPORTED_TYPE)
        }
    }

    private fun validatePng(bytes: ByteArray) {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        if (bytes.size < 8 || !bytes.copyOfRange(0, 8).contentEquals(signature)) fail(ParseFailure.INVALID_INPUT)
        var offset = 8
        var sawHeader = false
        var sawImage = false
        while (offset <= bytes.size - 12) {
            val length = unsignedInt(bytes, offset)
            if (length > bytes.size - offset - 12) fail(ParseFailure.INVALID_INPUT)
            val size = length.toInt()
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            if (!sawHeader && (type != "IHDR" || size != 13)) fail(ParseFailure.INVALID_INPUT)
            if (type == "IHDR" && sawHeader) fail(ParseFailure.INVALID_INPUT)
            val crc = CRC32().apply { update(bytes, offset + 4, size + 4) }.value
            if (crc != unsignedInt(bytes, offset + 8 + size)) fail(ParseFailure.INVALID_INPUT)
            if (type == "IHDR") sawHeader = true
            if (type == "IDAT") sawImage = true
            offset += size + 12
            if (type == "IEND") {
                if (size != 0 || !sawImage || offset != bytes.size) fail(ParseFailure.INVALID_INPUT)
                return
            }
        }
        fail(ParseFailure.INVALID_INPUT)
    }

    /** Rejects truncated marker/scan framing; successful decoding is also required. */
    private fun validateJpeg(bytes: ByteArray) {
        if (bytes.size < 4 || u(bytes[0]) != 0xff || u(bytes[1]) != 0xd8) fail(ParseFailure.INVALID_INPUT)
        var offset = 2
        var inScan = false
        var sawScan = false
        while (offset < bytes.size) {
            if (inScan) {
                while (offset < bytes.size && u(bytes[offset]) != 0xff) offset++
            }
            if (offset >= bytes.size || u(bytes[offset++]) != 0xff) fail(ParseFailure.INVALID_INPUT)
            while (offset < bytes.size && u(bytes[offset]) == 0xff) offset++
            if (offset >= bytes.size) fail(ParseFailure.INVALID_INPUT)
            val marker = u(bytes[offset++])
            if (inScan && (marker == 0x00 || marker in 0xd0..0xd7)) continue
            inScan = false
            if (marker == 0xd9) {
                if (!sawScan || offset != bytes.size) fail(ParseFailure.INVALID_INPUT)
                return
            }
            if (marker == 0x00 || marker == 0xd8 || marker in 0xd0..0xd7 || offset > bytes.size - 2) fail(ParseFailure.INVALID_INPUT)
            if (marker == 0x01) continue
            val size = u(bytes[offset]) * 256 + u(bytes[offset + 1])
            if (size < 2 || size > bytes.size - offset) fail(ParseFailure.INVALID_INPUT)
            offset += size
            if (marker == 0xda) { inScan = true; sawScan = true }
        }
        fail(ParseFailure.INVALID_INPUT)
    }

    private fun unsignedInt(bytes: ByteArray, offset: Int): Long =
        (u(bytes[offset]).toLong() shl 24) or (u(bytes[offset + 1]).toLong() shl 16) or
            (u(bytes[offset + 2]).toLong() shl 8) or u(bytes[offset + 3]).toLong()

    private fun u(value: Byte): Int = value.toInt() and 0xff
    private fun checkBudget(started: Long) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        if (System.nanoTime() - started > DECODE_BUDGET_NANOS) fail(ParseFailure.TIME_LIMIT)
    }
    private fun fail(reason: ParseFailure): Nothing = throw DocumentParseException(reason)
    private const val DECODE_BUDGET_NANOS = 15_000_000_000L
}

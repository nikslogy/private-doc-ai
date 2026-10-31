package com.privateassistant.platform.tools

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.media.FaceDetector
import android.os.Looper
import com.privateassistant.core.assistant.PhotoSpec
import com.privateassistant.core.tools.FaceGeometry
import com.privateassistant.core.tools.JpegPdfWriter
import com.privateassistant.core.tools.PassportPhotoLayout
import com.privateassistant.core.tools.SizeFit
import com.privateassistant.core.tools.SizeTargetSearch
import com.privateassistant.platform.documents.MemoryDocumentParser
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One generated file. Plaintext stays in memory; the caller stores it encrypted and clears it. */
class ToolOutput(val bytes: ByteArray, val mimeType: String, val nameSuffix: String, val extension: String) {
    override fun toString(): String = "ToolOutput(mimeType=$mimeType, bytes=${bytes.size})"
}

sealed interface ToolResult {
    class Created(val outputs: List<ToolOutput>, val faceFound: Boolean? = null) : ToolResult
    /** The original already satisfies the request; nothing new is created. */
    data class AlreadyFits(val bytes: Long) : ToolResult
    /** The smallest measured output still exceeded the requested size. */
    data class Unreachable(val smallestBytes: Long) : ToolResult
}

class DocumentToolException(val reason: Reason) : Exception("Document tool failed: $reason") {
    enum class Reason { UNSUPPORTED, INVALID_INPUT, TOO_MANY_PAGES, CANCELLED }
}

/**
 * Local derivative tools. Inputs are authenticated originals supplied by the vault; outputs are new
 * files. Nothing is written to disk, sent anywhere, or executed from document/model text. Run off the
 * main thread; [cancelled] is polled between pages and encodes.
 */
class DocumentToolExecutor(private val cancelled: () -> Boolean = { false }) {
    private val parser = MemoryDocumentParser()

    fun compress(bytes: ByteArray, mimeType: String, targetBytes: Long): ToolResult {
        requireWorker()
        if (bytes.size <= targetBytes) return ToolResult.AlreadyFits(bytes.size.toLong())
        return when (mimeType) {
            "image/jpeg", "image/png" -> compressImage(bytes, mimeType, targetBytes)
            "application/pdf" -> compressPdf(bytes, targetBytes)
            else -> throw DocumentToolException(DocumentToolException.Reason.UNSUPPORTED)
        }
    }

    /** PDF pages become JPEG images (at most [maxPages]); an image becomes a high-quality JPEG. */
    fun toImages(bytes: ByteArray, mimeType: String, maxPages: Int = MAX_IMAGE_PAGES): ToolResult {
        requireWorker()
        return when (mimeType) {
            "image/jpeg", "image/png" -> parser.withDecodedImage(bytes, mimeType) { bitmap, _ ->
                ToolResult.Created(listOf(ToolOutput(jpeg(opaque(bitmap), IMAGE_QUALITY), "image/jpeg", "image", "jpg")))
            }
            "application/pdf" -> {
                val pageCount = parser.parse(mimeType, bytes).pageCount
                val pages = min(pageCount, maxPages)
                ToolResult.Created((1..pages).map { page ->
                    checkCancelled()
                    val jpeg = parser.withRenderedPdfPage(bytes, page, RENDER_EDGE) { bitmap -> jpeg(bitmap, IMAGE_QUALITY) }
                    ToolOutput(jpeg, "image/jpeg", if (pageCount == 1) "image" else "page $page", "jpg")
                })
            }
            else -> throw DocumentToolException(DocumentToolException.Reason.UNSUPPORTED)
        }
    }

    /** An image placed on an A4 page as a JPEG-backed PDF. */
    fun toPdf(bytes: ByteArray, mimeType: String): ToolResult {
        requireWorker()
        if (mimeType != "image/jpeg" && mimeType != "image/png") throw DocumentToolException(DocumentToolException.Reason.UNSUPPORTED)
        val page = parser.withDecodedImage(bytes, mimeType) { bitmap, _ ->
            JpegPdfWriter.a4Fit(jpeg(opaque(bitmap), IMAGE_QUALITY), bitmap.width, bitmap.height)
        }
        return ToolResult.Created(listOf(ToolOutput(JpegPdfWriter.write(listOf(page)), "application/pdf", "PDF", "pdf")))
    }

    /** Crops around the detected face to the photo spec and scales to its 300 DPI pixel size. */
    fun passportPhoto(bytes: ByteArray, mimeType: String, spec: PhotoSpec): ToolResult {
        requireWorker()
        if (mimeType != "image/jpeg" && mimeType != "image/png") throw DocumentToolException(DocumentToolException.Reason.UNSUPPORTED)
        return parser.withDecodedImage(bytes, mimeType) { source, _ ->
            val bitmap = opaque(source)
            try {
                val face = detectFace(bitmap)
                checkCancelled()
                val crop = PassportPhotoLayout.crop(bitmap.width, bitmap.height, face, spec)
                val output = Bitmap.createBitmap(spec.widthPx, spec.heightPx, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(output).apply {
                        drawColor(Color.WHITE)
                        drawBitmap(bitmap, Rect(crop.left, crop.top, crop.right, crop.bottom),
                            Rect(0, 0, spec.widthPx, spec.heightPx), Paint(Paint.FILTER_BITMAP_FLAG))
                    }
                    val suffix = if (spec == PhotoSpec.US_2X2) "passport photo 2x2 in" else "passport photo 35x45 mm"
                    ToolResult.Created(listOf(ToolOutput(jpeg(output, PHOTO_QUALITY), "image/jpeg", suffix, "jpg")),
                        faceFound = face != null)
                } finally {
                    output.recycle()
                }
            } finally {
                if (bitmap !== source) bitmap.recycle()
            }
        }
    }

    private fun compressImage(bytes: ByteArray, mimeType: String, targetBytes: Long): ToolResult =
        parser.withDecodedImage(bytes, mimeType) { source, _ ->
            val bitmap = opaque(source)
            try {
                when (val fit = fitBitmap(bitmap, targetBytes)) {
                    is SizeFit.Fitted -> ToolResult.Created(listOf(ToolOutput(fit.output, "image/jpeg",
                        "compressed", "jpg")))
                    is SizeFit.Unreachable -> ToolResult.Unreachable(fit.smallestBytes)
                }
            } finally {
                if (bitmap !== source) bitmap.recycle()
            }
        }

    /**
     * Rasterizes every page to JPEG and rebuilds a PDF within the byte budget. The caller must have
     * disclosed that text selection/search, links, forms and signatures are not preserved.
     */
    private fun compressPdf(bytes: ByteArray, targetBytes: Long): ToolResult {
        val parsed = parser.parse("application/pdf", bytes)
        val pageCount = parsed.pageCount
        if (pageCount > JpegPdfWriter.MAX_PAGES) throw DocumentToolException(DocumentToolException.Reason.TOO_MANY_PAGES)
        val available = targetBytes - JpegPdfWriter.OVERHEAD_FIXED_BYTES - pageCount.toLong() * JpegPdfWriter.OVERHEAD_PER_PAGE_BYTES
        val perPage = available / pageCount
        if (perPage < MIN_PAGE_BYTES) return ToolResult.Unreachable(MIN_PAGE_BYTES * pageCount)
        val geometry = parsed.geometry.associateBy { it.pageNumber }
        val pages = ArrayList<JpegPdfWriter.Page>()
        var smallestTotal = 0L
        var unreachable = false
        for (page in 1..pageCount) {
            checkCancelled()
            val fit = parser.withRenderedPdfPage(bytes, page, RENDER_EDGE) { bitmap -> fitBitmap(bitmap, perPage) }
            when (fit) {
                is SizeFit.Fitted -> {
                    val size = geometry[page]
                    val widthPt = size?.width?.toFloat() ?: JpegPdfWriter.A4_WIDTH_PT
                    val heightPt = size?.height?.toFloat() ?: JpegPdfWriter.A4_HEIGHT_PT
                    pages += JpegPdfWriter.Page(fit.output, widthPt, heightPt)
                    smallestTotal += fit.bytes
                }
                is SizeFit.Unreachable -> { unreachable = true; smallestTotal += fit.smallestBytes }
            }
        }
        if (unreachable) return ToolResult.Unreachable(smallestTotal)
        val pdf = JpegPdfWriter.write(pages)
        // The measured result, not the plan, decides success.
        if (pdf.size > targetBytes) return ToolResult.Unreachable(pdf.size.toLong())
        return ToolResult.Created(listOf(ToolOutput(pdf, "application/pdf", "compressed", "pdf")))
    }

    private fun fitBitmap(bitmap: Bitmap, targetBytes: Long): SizeFit<ByteArray> {
        val scaled = HashMap<Double, Bitmap>()
        try {
            return SizeTargetSearch.fit(targetBytes, { quality, scale ->
                checkCancelled()
                val input = if (scale >= 1.0) bitmap else scaled.getOrPut(scale) {
                    Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * scale).roundToInt()),
                        max(1, (bitmap.height * scale).roundToInt()), true)
                }
                jpeg(input, quality)
            }, { it.size.toLong() })
        } finally {
            scaled.values.forEach { if (it !== bitmap) it.recycle() }
        }
    }

    /** android.media.FaceDetector is an on-device framework API; no model download or network. */
    private fun detectFace(bitmap: Bitmap): FaceGeometry? {
        val scale = min(1f, DETECT_EDGE.toFloat() / max(bitmap.width, bitmap.height))
        var width = max(2, (bitmap.width * scale).roundToInt())
        if (width % 2 == 1) width -= 1
        val height = max(1, (bitmap.height * scale).roundToInt())
        val small = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val rgb565 = small.copy(Bitmap.Config.RGB_565, false)
        try {
            val faces = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
            val count = FaceDetector(width, height, MAX_FACES).findFaces(rgb565, faces)
            val best = faces.take(count).filterNotNull()
                .filter { it.confidence() >= MIN_FACE_CONFIDENCE }
                .maxByOrNull { it.eyesDistance() } ?: return null
            val mid = PointF().also(best::getMidPoint)
            val sx = bitmap.width.toFloat() / width
            val sy = bitmap.height.toFloat() / height
            return FaceGeometry(mid.x * sx, mid.y * sy, best.eyesDistance() * sx)
        } finally {
            if (rgb565 !== small) rgb565.recycle()
            if (small !== bitmap) small.recycle()
        }
    }

    /** JPEG has no alpha; transparent PNG regions become white instead of black. */
    private fun opaque(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val copy = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(copy).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
        return copy
    }

    private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out))
        return out.toByteArray()
    }

    private fun checkCancelled() {
        if (cancelled() || Thread.currentThread().isInterrupted) throw DocumentToolException(DocumentToolException.Reason.CANCELLED)
    }

    private fun requireWorker() = check(Looper.myLooper() != Looper.getMainLooper()) { "Run document tools off the main thread" }

    companion object {
        const val MAX_IMAGE_PAGES = 10
        private const val RENDER_EDGE = 2000
        private const val DETECT_EDGE = 1024
        private const val MAX_FACES = 3
        private const val MIN_FACE_CONFIDENCE = 0.3f
        private const val IMAGE_QUALITY = 90
        private const val PHOTO_QUALITY = 95
        private const val MIN_PAGE_BYTES = 4_000L
    }
}

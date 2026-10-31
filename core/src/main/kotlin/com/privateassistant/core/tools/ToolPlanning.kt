package com.privateassistant.core.tools

import com.privateassistant.core.CropBounds
import com.privateassistant.core.assistant.PhotoSpec
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Result of a bounded quality/scale search. Sizes are measured encoder outputs, never estimates. */
sealed interface SizeFit<out T> {
    data class Fitted<T>(val output: T, val bytes: Long, val quality: Int, val scale: Double) : SizeFit<T>
    /** The smallest attempted output still exceeded the target; it is reported, not silently used. */
    data class Unreachable(val smallestBytes: Long) : SizeFit<Nothing>
}

/**
 * Finds the highest JPEG quality (and largest scale) whose actual encoded size fits [targetBytes].
 * [encode] must be deterministic for the same inputs; at most ~45 encodes are attempted.
 */
object SizeTargetSearch {
    val SCALES = listOf(1.0, 0.85, 0.7, 0.55, 0.42, 0.32, 0.24)
    const val MIN_QUALITY = 25
    const val MAX_QUALITY = 92

    fun <T> fit(targetBytes: Long, encode: (quality: Int, scale: Double) -> T, size: (T) -> Long): SizeFit<T> {
        require(targetBytes > 0)
        var smallest = Long.MAX_VALUE
        for (scale in SCALES) {
            val floor = encode(MIN_QUALITY, scale)
            val floorBytes = size(floor)
            smallest = min(smallest, floorBytes)
            if (floorBytes > targetBytes) continue
            var low = MIN_QUALITY
            var best: T = floor
            var bestBytes = floorBytes
            var high = MAX_QUALITY
            while (low < high) {
                val mid = (low + high + 1) / 2
                val candidate = encode(mid, scale)
                val bytes = size(candidate)
                if (bytes <= targetBytes) {
                    low = mid; best = candidate; bestBytes = bytes
                } else {
                    high = mid - 1
                }
            }
            return SizeFit.Fitted(best, bestBytes, low, scale)
        }
        return SizeFit.Unreachable(smallest)
    }
}

/** Approximate face location in source pixels, e.g. from the platform face detector. */
data class FaceGeometry(val eyeCenterX: Float, val eyeCenterY: Float, val eyeDistance: Float) {
    init { require(eyeDistance > 0f && eyeCenterX.isFinite() && eyeCenterY.isFinite()) }
}

/**
 * ICAO-style composition: the head (chin to crown) fills ~74% of the frame height and the eye
 * line sits ~44% from the top. Without a face the crop is centred and biased toward the top.
 */
object PassportPhotoLayout {
    private const val HEAD_TO_EYE_DISTANCE = 3.6f
    private const val HEAD_FRACTION = 0.74f
    private const val EYE_LINE_FROM_TOP = 0.44f

    fun crop(imageWidth: Int, imageHeight: Int, face: FaceGeometry?, spec: PhotoSpec): CropBounds {
        require(imageWidth > 0 && imageHeight > 0)
        val aspect = spec.widthPx.toFloat() / spec.heightPx
        var height: Float
        var width: Float
        var centerX: Float
        var top: Float
        if (face != null) {
            height = face.eyeDistance * HEAD_TO_EYE_DISTANCE / HEAD_FRACTION
            width = height * aspect
            centerX = face.eyeCenterX
            top = face.eyeCenterY - height * EYE_LINE_FROM_TOP
        } else {
            width = min(imageWidth.toFloat(), imageHeight * aspect)
            height = width / aspect
            centerX = imageWidth / 2f
            top = (imageHeight - height) * 0.25f
        }
        // Shrink (keeping aspect) when the composition is larger than the source.
        val fit = min(1f, min(imageWidth / width, imageHeight / height))
        width *= fit
        height *= fit
        var left = centerX - width / 2f
        left = left.coerceIn(0f, imageWidth - width)
        top = top.coerceIn(0f, imageHeight - height)
        val l = left.roundToInt().coerceIn(0, imageWidth - 1)
        val t = top.roundToInt().coerceIn(0, imageHeight - 1)
        val r = max(l + 1, min(imageWidth, (left + width).roundToInt()))
        val b = max(t + 1, min(imageHeight, (top + height).roundToInt()))
        return CropBounds(l, t, r, b)
    }
}

/** Human-friendly byte formatting used in assistant messages (decimal units, matching targets). */
object ByteSizes {
    fun format(bytes: Long): String = when {
        bytes >= 1_000_000 -> "${bytes / 1_000_000}.${((bytes % 1_000_000) / 10_000).toString().padStart(2, '0')} MB"
        // Truncate rather than round so 199,960 bytes never reads as "200 KB" against a 200 KB limit.
        bytes >= 1_000 -> "${bytes / 1_000}.${(bytes % 1_000) / 100} KB"
        else -> "$bytes bytes"
    }
}

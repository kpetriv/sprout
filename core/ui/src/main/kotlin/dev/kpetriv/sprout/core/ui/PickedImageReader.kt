package dev.kpetriv.sprout.core.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Reads an image picked in the system photo picker (`PickVisualMedia`) into JPEG bytes: decodes the content URI with
 * [ImageDecoder] (which applies the EXIF rotation), scales it to at most [Limits.maxSide] px on the long side and
 * re-encodes it as JPEG, stepping the quality down from [Limits.quality] until it fits [Limits.targetBytes] or reaches
 * [Limits.minQuality]. Re-encoding drops every EXIF field, location included. Shared by chat photos (M4 §2) and
 * receipt scanning (M6 §3.6). Never logs the URI or the image.
 *
 * A source over [MAX_SOURCE_PIXELS] is refused in the header callback, before any pixel is decoded: a small,
 * highly compressible file can declare a huge image, and the decoder would inflate all of it on the calling thread
 * even with a target size (audit 2 N3, D-M6-53). The bound is far above any camera photo (50 to 200 MP).
 *
 * Blocking: call it from an IO dispatcher.
 */
class PickedImageReader(private val contentResolver: ContentResolver) {
    /** How large the result may be. */
    data class Limits(
        val maxSide: Int,
        val targetBytes: Int,
        val quality: Int = DEFAULT_QUALITY,
        val minQuality: Int = DEFAULT_MIN_QUALITY,
    ) {
        init {
            require(maxSide > 0 && targetBytes > 0) { "limits must be positive" }
            require(minQuality in 1..quality && quality <= MAX_QUALITY) { "quality must be in 1..100, min <= start" }
        }

        companion object {
            private const val DEFAULT_QUALITY = 80
            private const val DEFAULT_MIN_QUALITY = 50
            private const val MAX_QUALITY = 100

            /** A chat photo (M4 §2): 1280 px, 500 KB. */
            val CHAT: Limits = Limits(maxSide = 1_280, targetBytes = 500 * 1_024)

            /** A receipt for on-device text recognition (M6): small print needs more pixels than a chat photo. */
            val RECEIPT: Limits = Limits(maxSide = 2_048, targetBytes = 2 * 1_024 * 1_024, quality = 90)
        }
    }

    /** Reads the content [uri]; fails when it cannot be read or decoded. */
    @Suppress("TooGenericExceptionCaught") // Any decoder or provider failure means "could not read this image".
    fun read(uri: String, limits: Limits = Limits.CHAT): Result<ByteArray> = try {
        val source = ImageDecoder.createSource(contentResolver, Uri.parse(uri))
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val target = targetSize(info.size.width, info.size.height, limits.maxSide)
                ?: throw IOException("image too large or empty")
            if (target.first != info.size.width || target.second != info.size.height) {
                decoder.setTargetSize(target.first, target.second)
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            Result.success(encode(bitmap, limits))
        } finally {
            bitmap.recycle()
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun encode(bitmap: Bitmap, limits: Limits): ByteArray {
        var quality = limits.quality
        while (true) {
            val out = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "JPEG encoding failed" }
            if (out.size() <= limits.targetBytes || quality <= limits.minQuality) return out.toByteArray()
            quality = maxOf(quality - QUALITY_STEP, limits.minQuality)
        }
    }

    companion object {
        private const val QUALITY_STEP = 10

        /** Sources with more pixels than this are refused before decoding (250 MP; D-M6-53). */
        const val MAX_SOURCE_PIXELS: Long = 250_000_000

        /**
         * The size a [width] by [height] source is decoded at: unchanged when its long side is at most [maxSide],
         * otherwise scaled down to [maxSide] on the long side (at least 1 px on the short one). Null when a side is not
         * positive or the source has more than [MAX_SOURCE_PIXELS] pixels. Computed in `Long`, so it never overflows.
         */
        fun targetSize(width: Int, height: Int, maxSide: Int): Pair<Int, Int>? {
            val longSide = maxOf(width, height)
            fun scaled(side: Int) = (side.toLong() * maxSide / longSide).toInt().coerceAtLeast(1)
            return when {
                width <= 0 || height <= 0 || width.toLong() * height > MAX_SOURCE_PIXELS -> null
                longSide <= maxSide -> width to height
                else -> scaled(width) to scaled(height)
            }
        }
    }
}

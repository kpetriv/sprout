package dev.kpetriv.sprout.core.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Reads an image picked in the system photo picker (`PickVisualMedia`) into JPEG bytes: decodes the content URI with
 * [ImageDecoder] (which applies the EXIF rotation), scales it to at most [Limits.maxSide] px on the long side and
 * re-encodes it as JPEG, stepping the quality down from [Limits.quality] until it fits [Limits.targetBytes] or reaches
 * [Limits.minQuality]. Re-encoding drops every EXIF field, location included. Shared by chat photos (M4 §2) and
 * receipt scanning (M6 §3.6). Never logs the URI or the image.
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
            val width = info.size.width
            val height = info.size.height
            val longSide = maxOf(width, height)
            if (longSide > limits.maxSide) {
                decoder.setTargetSize(width * limits.maxSide / longSide, height * limits.maxSide / longSide)
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

    private companion object {
        const val QUALITY_STEP = 10
    }
}

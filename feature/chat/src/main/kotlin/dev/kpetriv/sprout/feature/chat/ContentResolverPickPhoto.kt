package dev.kpetriv.sprout.feature.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kpetriv.sprout.core.common.Dispatcher
import dev.kpetriv.sprout.core.common.SproutDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * The default [PickPhoto]: decodes the picked image with [ImageDecoder] (which applies the EXIF rotation), scales
 * it to at most 1280 px on the long side and re-encodes it as JPEG, stepping the quality down from 80 until it fits
 * 500 KB (M4 §2). Re-encoding drops every EXIF field, location included.
 */
class ContentResolverPickPhoto @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:Dispatcher(SproutDispatchers.IO) private val io: CoroutineDispatcher,
) : PickPhoto {
    @Suppress("TooGenericExceptionCaught") // Any decoder or provider failure means "could not read this photo".
    override suspend fun read(uri: String): Result<ByteArray> = withContext(io) {
        try {
            val source = ImageDecoder.createSource(context.contentResolver, uri.toUri())
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                val longSide = maxOf(width, height)
                if (longSide > MAX_SIDE) {
                    decoder.setTargetSize(width * MAX_SIDE / longSide, height * MAX_SIDE / longSide)
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            try {
                Result.success(encode(bitmap))
            } finally {
                bitmap.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        var quality = QUALITY
        while (true) {
            val out = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "JPEG encoding failed" }
            if (out.size() <= TARGET_BYTES || quality <= MIN_QUALITY) return out.toByteArray()
            quality -= QUALITY_STEP
        }
    }

    private companion object {
        const val MAX_SIDE = 1_280
        const val QUALITY = 80
        const val MIN_QUALITY = 50
        const val QUALITY_STEP = 10
        const val TARGET_BYTES = 500 * 1_024
    }
}

package dev.kpetriv.sprout.core.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * [ReceiptTextReader] on ML Kit's on-device Latin text recognition (`play-services-mlkit-text-recognition`, the model
 * Google Play services downloads at install; see the module manifest). The photo is decoded at most
 * [MAX_SIDE] pixels on its longer side; nothing leaves the device and nothing is kept.
 *
 * Known limit: EXIF rotation is not applied, so a sideways photo may read badly (the parent then types the amount).
 */
class MlKitReceiptTextReader(private val decodeDispatcher: CoroutineDispatcher) : ReceiptTextReader {
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // A failure only means "no prefill".
    override suspend fun read(jpeg: ByteArray): List<RecognizedLine>? = try {
        if (jpeg.isEmpty() || jpeg.size > MAX_BYTES) {
            null
        } else {
            withContext(decodeDispatcher) { decode(jpeg) }?.let { bitmap -> recognise(bitmap) }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        null
    }

    private suspend fun recognise(bitmap: Bitmap): List<RecognizedLine> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            return text.textBlocks.flatMap { it.lines }.mapIndexed { index, line ->
                val box = line.boundingBox
                RecognizedLine(
                    text = line.text,
                    top = box?.top ?: (index * LINE_HEIGHT_GUESS),
                    bottom = box?.bottom ?: (index * LINE_HEIGHT_GUESS + LINE_HEIGHT_GUESS - 1),
                    left = box?.left ?: 0,
                )
            }
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }

    private fun decode(jpeg: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
    }

    private companion object {
        /** Longest side the photo is decoded at; enough for receipt text. */
        const val MAX_SIDE = 2048

        /** Larger inputs are refused rather than decoded. */
        const val MAX_BYTES = 20 * 1024 * 1024

        /** Row height assumed when ML Kit gives a line no box. */
        const val LINE_HEIGHT_GUESS = 40
    }
}

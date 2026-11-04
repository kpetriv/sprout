package dev.kpetriv.sprout.core.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import kotlin.coroutines.cancellation.CancellationException

/**
 * [ReceiptTextReader] on ML Kit's on-device Latin text recognition (`play-services-mlkit-text-recognition`, the model
 * Google Play services downloads at install; see the module manifest). Recognition runs in Play services on the
 * device; nothing is kept.
 *
 * The photo's size is read first: one over [MAX_PIXELS] is refused before anything is decoded, and the rest are
 * decoded at most [TARGET_PIXELS] (a power-of-two sample size), enough for receipt text (audit L4). The bitmap and the
 * recognizer are released only when the recognition task completes, which it also does after a timeout cancels the
 * caller: Play services may still be reading them then (review L7).
 *
 * Known limit: EXIF rotation is not applied, so a sideways photo may read badly (the parent then types the amount).
 */
internal class MlKitReceiptTextReader(private val decodeDispatcher: CoroutineDispatcher) : ReceiptTextReader {
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // A failure only means "no prefill".
    override suspend fun read(jpeg: ByteArray): List<RecognizedLine>? = try {
        if (jpeg.isEmpty() || jpeg.size > MAX_BYTES) {
            null
        } else {
            withContext(decodeDispatcher) { decode(jpeg) }?.let { bitmap -> recognise(bitmap) }
        }
    } catch (cancelled: CancellationException) {
        // Rethrown only when the caller was cancelled; a task Play services cancelled is a failure (review M6).
        currentCoroutineContext().ensureActive()
        null
    } catch (error: Exception) {
        null
    }

    private suspend fun recognise(bitmap: Bitmap): List<RecognizedLine> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val release = {
            recognizer.close()
            bitmap.recycle()
        }
        var started = false
        val task = try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).also { started = true }
        } finally {
            if (!started) release()
        }
        task.addOnCompleteListener(DIRECT) { release() }
        val text = task.await()
        return text.textBlocks.flatMap { it.lines }.mapIndexed { index, line ->
            val box = line.boundingBox
            RecognizedLine(
                text = line.text,
                top = box?.top ?: (index * LINE_HEIGHT_GUESS),
                bottom = box?.bottom ?: (index * LINE_HEIGHT_GUESS + LINE_HEIGHT_GUESS - 1),
                left = box?.left ?: 0,
            )
        }
    }

    private fun decode(jpeg: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        return sampleSize(bounds.outWidth, bounds.outHeight)?.let { sample ->
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
        }
    }

    companion object {
        /** Larger inputs are refused rather than decoded. */
        private const val MAX_BYTES = 20 * 1024 * 1024

        /** Images with more pixels than this are refused before decoding (a decompression bomb). */
        const val MAX_PIXELS: Long = 20_000_000

        /** Images are decoded at most this many pixels. */
        const val TARGET_PIXELS: Long = 4_000_000

        /** Row height assumed when ML Kit gives a line no box. */
        private const val LINE_HEIGHT_GUESS = 40

        /** Runs the release listener on the thread that completes the task, not the main thread. */
        private val DIRECT = Executor { it.run() }

        /**
         * The power-of-two `inSampleSize` that decodes a [width] by [height] image at most [TARGET_PIXELS], or null
         * when the size is unknown or over [MAX_PIXELS].
         */
        fun sampleSize(width: Int, height: Int): Int? {
            val pixels = width.toLong() * height
            if (width <= 0 || height <= 0 || pixels > MAX_PIXELS) return null
            var sample = 1
            while (pixels / (sample.toLong() * sample) > TARGET_PIXELS) sample *= 2
            return sample
        }
    }
}

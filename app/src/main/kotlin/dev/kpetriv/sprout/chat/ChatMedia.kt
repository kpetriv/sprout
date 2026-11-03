package dev.kpetriv.sprout.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kpetriv.sprout.core.common.Dispatcher
import dev.kpetriv.sprout.core.common.SproutDispatchers
import dev.kpetriv.sprout.core.database.BlobExpectation
import dev.kpetriv.sprout.core.database.BlobTarget
import dev.kpetriv.sprout.core.database.BlobUnavailableException
import dev.kpetriv.sprout.core.database.ChatBlobs
import dev.kpetriv.sprout.core.domainledger.chat.ChatLimits
import dev.kpetriv.sprout.core.model.BlobId
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.SubmissionId
import dev.kpetriv.sprout.core.model.ThreadId
import dev.kpetriv.sprout.core.sync.SyncTransport
import dev.kpetriv.sprout.feature.chat.BlobLoader
import dev.kpetriv.sprout.feature.chat.VoiceClip
import dev.kpetriv.sprout.feature.chat.VoicePlayer
import dev.kpetriv.sprout.feature.chat.VoiceRecorder
import dev.kpetriv.sprout.feature.chores.RemotePhotoLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.resume
import dev.kpetriv.sprout.feature.chat.BlobExpectation as ChatBlobExpectation

/** The pixel size of a JPEG, read from its header only. */
internal data class JpegSize(val width: Int, val height: Int) {
    companion object {
        /**
         * [jpeg]'s size, clamped into the range a chat photo reference allows (the picker already downscaled it). An
         * unreadable header gives 1 x 1: the size is only a layout hint for the receiver.
         */
        @Suppress("SwallowedException") // Some decoders throw on a bad header instead of returning nothing.
        fun of(jpeg: ByteArray): JpegSize {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try {
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
            } catch (expected: RuntimeException) {
                return JpegSize(1, 1)
            }
            val max = ChatLimits.MAX_PHOTO_DIMENSION
            return JpegSize(options.outWidth.coerceIn(1, max), options.outHeight.coerceIn(1, max))
        }
    }
}

/**
 * [BlobLoader] (chat media) and [RemotePhotoLoader] (chore photos in the approvals inbox), M4 §2: the local cache
 * first ([ChatBlobs.open]), otherwise the chunks from [SyncTransport.fetchBlob], verified, cached and decrypted by
 * [ChatBlobs.ingest]. Every blob is checked against what refers to it (audit M5): a chat blob against its message's
 * thread, author device and photo hash; a chore photo against the submission on screen (its kid, its id and its
 * `photoRef.sha256`, audit A2-M1), never against whichever submission names the blob id. A failure is a failed
 * [Result] ([BlobUnavailableException] or the transport's `SyncException`); nothing is logged.
 */
class TransportBlobLoader @Inject constructor(private val blobs: ChatBlobs, private val transport: SyncTransport) :
    BlobLoader,
    RemotePhotoLoader {
    override suspend fun load(blobId: String, expected: ChatBlobExpectation): Result<ByteArray> = attempt {
        val target = BlobTarget.Thread(ThreadId.of(expected.threadId))
        val author = expected.authorDeviceId?.let(::DeviceId)
        load(BlobId(blobId), BlobExpectation(target, author, expected.sha256))
    }

    override suspend fun load(
        blobId: String,
        kidId: KidId,
        submissionId: SubmissionId,
        sha256: String,
    ): Result<ByteArray> = attempt {
        load(BlobId(blobId), BlobExpectation(BlobTarget.ChorePhoto(kidId, submissionId), plaintextSha256 = sha256))
    }

    private suspend fun load(id: BlobId, expected: BlobExpectation): ByteArray =
        blobs.open(id, expected) ?: blobs.ingest(id, transport.fetchBlob(id).getOrThrow(), expected)

    @Suppress("TooGenericExceptionCaught") // Every failure is a result: the screen shows "unavailable".
    private suspend fun attempt(block: suspend () -> ByteArray): Result<ByteArray> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        Result.failure(failure)
    }
}

/**
 * The temporary audio files of chat voice notes in `cacheDir` (audit A2-M2): the recorder's `voice*.m4a` (MediaRecorder
 * needs a file to write to) and, from builds before fix pass 3, the player's `play*.m4a`. [sweep] deletes what a
 * process death left behind; `AppInitializer` runs it at every start, and the wipe clears `cacheDir` as a whole.
 */
class VoiceTempFiles @Inject constructor(@param:ApplicationContext private val context: Context) {
    /** A new, empty recording file in `cacheDir`. */
    fun newRecording(): File = File.createTempFile(RECORDING_PREFIX, SUFFIX, context.cacheDir)

    /** Deletes every leftover voice temp file in `cacheDir`; returns how many were deleted. */
    fun sweep(): Int = sweep(context.cacheDir)

    companion object {
        internal const val RECORDING_PREFIX = "voice"
        internal const val PLAYBACK_PREFIX = "play"
        internal const val SUFFIX = ".m4a"

        /** Deletes the voice temp files ([RECORDING_PREFIX] or [PLAYBACK_PREFIX], [SUFFIX]) directly in [directory]. */
        internal fun sweep(directory: File): Int = directory.listFiles().orEmpty().count { file ->
            file.isFile && file.name.endsWith(SUFFIX) &&
                (file.name.startsWith(RECORDING_PREFIX) || file.name.startsWith(PLAYBACK_PREFIX)) && file.delete()
        }
    }
}

/**
 * [VoiceRecorder] over [MediaRecorder] (M4 §2): AAC-LC in an MPEG-4 container, mono 16 kHz at 32 kbps, stopping by
 * itself at [VoiceRecorder.MAX_DURATION_MS]. The recording goes to a temporary file in `cacheDir` ([VoiceTempFiles]),
 * which is read into memory on [stop] and deleted on every path: after [stop] (also when it fails), on [cancel], when
 * [start] fails or its caller is cancelled. A file a process death leaves behind is swept at the next start.
 * One recording at a time.
 */
class MediaVoiceRecorder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val tempFiles: VoiceTempFiles,
    @param:Dispatcher(SproutDispatchers.IO) private val dispatcher: CoroutineDispatcher,
) : VoiceRecorder {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt: Long = 0

    @Volatile
    private var reachedMax = false

    override suspend fun start(): Result<Unit> = try {
        withContext(dispatcher) { startRecording() }
    } catch (cancellation: CancellationException) {
        // Cancelled while (or right after) starting: nothing may keep recording or stay on disk.
        cancel()
        throw cancellation
    }

    @Suppress("TooGenericExceptionCaught") // MediaRecorder reports a busy or missing microphone as RuntimeException.
    private fun startRecording(): Result<Unit> {
        cancel()
        reachedMax = false
        return try {
            val target = tempFiles.newRecording()
            file = target
            val created = newRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(SAMPLING_RATE)
                setAudioEncodingBitRate(BIT_RATE)
                setMaxDuration(VoiceRecorder.MAX_DURATION_MS.toInt())
                setOutputFile(target.absolutePath)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) reachedMax = true
                }
                prepare()
                start()
            }
            recorder = created
            startedAt = SystemClock.elapsedRealtime()
            Result.success(Unit)
        } catch (failure: Exception) {
            cancel()
            Result.failure(failure)
        }
    }

    @Suppress("TooGenericExceptionCaught") // stop() throws RuntimeException when nothing was recorded.
    override suspend fun stop(): Result<VoiceClip> = withContext(dispatcher + NonCancellable) {
        val active = recorder ?: return@withContext Result.failure(IllegalStateException("not recording"))
        val target = file
        val duration = (SystemClock.elapsedRealtime() - startedAt).coerceAtMost(VoiceRecorder.MAX_DURATION_MS)
        recorder = null
        file = null
        try {
            // At the maximum duration the recorder already stopped (and finalised the file) by itself.
            if (!reachedMax) active.stop()
            active.release()
            val bytes = checkNotNull(target).readBytes()
            Result.success(VoiceClip(bytes, duration))
        } catch (failure: Exception) {
            active.release()
            Result.failure(failure)
        } finally {
            target?.delete()
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Cancelling never fails.
    override fun cancel() {
        recorder?.let { active ->
            try {
                active.stop()
            } catch (expected: RuntimeException) {
                // Nothing was recorded yet.
            }
            active.release()
        }
        recorder = null
        file?.delete()
        file = null
    }

    @Suppress("DEPRECATION") // The context constructor exists from API 31 only.
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

    private companion object {
        const val SAMPLING_RATE = 16_000
        const val BIT_RATE = 32_000
    }
}

/**
 * A [MediaDataSource] over a decrypted clip in memory (audit A2-M2): MediaPlayer reads the bytes from here, so a
 * decrypted voice note is never written to a file. The array stays the caller's; it is not copied or wiped.
 */
internal class ByteArrayMediaDataSource(private val bytes: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || position >= bytes.size) return END_OF_STREAM
        val count = minOf(size.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, count)
        return count
    }

    override fun getSize(): Long = bytes.size.toLong()

    override fun close() = Unit

    private companion object {
        const val END_OF_STREAM = -1
    }
}

/**
 * [VoicePlayer] over [MediaPlayer]: the decrypted clip is played from memory ([ByteArrayMediaDataSource], audit
 * A2-M2), never from a file, and the player is released when playback ends, fails or is cancelled.
 */
class MediaVoicePlayer @Inject constructor() : VoicePlayer {
    @Volatile
    private var player: MediaPlayer? = null

    override suspend fun play(bytes: ByteArray) {
        stop()
        playFrom(ByteArrayMediaDataSource(bytes))
    }

    override fun stop() {
        player?.let { active ->
            player = null
            runCatching { active.stop() }
            active.release()
        }
    }

    @Suppress("SwallowedException") // A clip that cannot be played just ends; nothing to report or log.
    private suspend fun playFrom(source: MediaDataSource) = suspendCancellableCoroutine { continuation ->
        val media = MediaPlayer()
        player = media
        val finish = {
            if (player === media) player = null
            media.release()
            if (continuation.isActive) continuation.resume(Unit)
        }
        media.setOnCompletionListener { finish() }
        media.setOnErrorListener { _, _, _ ->
            finish()
            true
        }
        continuation.invokeOnCancellation { stop() }
        try {
            media.setDataSource(source)
            media.prepare()
            media.start()
        } catch (failure: IOException) {
            finish()
        } catch (failure: IllegalStateException) {
            finish()
        } catch (failure: IllegalArgumentException) {
            finish()
        }
    }
}

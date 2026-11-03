package dev.kpetriv.sprout.feature.chat

import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/*
 * Ports of the chat screens. The app adapts them to the message store, thread keys and blobs of M4 §1 to §4; this
 * module sees only decrypted, verified content and never touches keys or ciphertext. One thread per kid, so a thread
 * is named by its kid's [KidId] (`threadId = kidId`).
 *
 * Message text is personal data: every type here that carries it keeps it out of `toString`, and nothing in this
 * module logs it.
 */

/** The parent's thread list and per-thread metadata. */
interface ChatThreads {
    /** Every kid's thread, in the household's kid order. */
    fun observeThreads(): Flow<List<ChatThreadSummary>>

    /** One thread; null when the kid does not exist (any more). */
    fun observeThread(threadId: KidId): Flow<ChatThreadSummary?>

    /** Everything in [threadId] up to now has been seen on this device (local unread count only; no receipts). */
    suspend fun markRead(threadId: KidId)
}

/**
 * @property kidName the kid's first name: the thread's title.
 * @property avatarId the kid's avatar id (the list shows the initial until avatars have pictures).
 * @property unreadCount messages from others not yet seen on this device.
 * @property lastMessageAt when the newest message was written; null for an empty thread.
 */
data class ChatThreadSummary(
    val threadId: KidId,
    val kidName: String,
    val avatarId: String,
    val unreadCount: Int,
    val lastMessageAt: Instant?,
) {
    override fun toString(): String = "ChatThreadSummary(threadId=$threadId, unreadCount=$unreadCount)"
}

/** The newest messages of a thread. */
fun interface MessageReader {
    /** The newest [limit] messages of [threadId], oldest first; re-emits on every change. */
    fun observe(threadId: KidId, limit: Int): Flow<MessagePage>
}

/** [messages] oldest first; [hasMore] when older messages exist beyond the limit. */
data class MessagePage(val messages: List<ChatMessage>, val hasMore: Boolean)

/** Who wrote a message, from this device's point of view. */
enum class MessageDirection {
    /** This user (any of their devices). */
    MINE,

    /** Anyone else in the thread. */
    THEIRS,
}

/** The envelope kinds of M4 §1.3. */
enum class MessageKind {
    TEXT,
    PRESET,
    VOICE,
    PHOTO,
}

/** Where an outgoing message stands. Messages from others are always [SENT]. */
enum class SendState {
    /** Stored on this device, not yet accepted by the server. */
    SENDING,
    SENT,

    /** The server refused it, or it could not be stored; it can be retried. */
    FAILED,

    /**
     * The server refused it for good and it holds back everything this device sends after it: "sending is blocked".
     * It can only be discarded ([MessageSender.discard]).
     */
    BLOCKED,
}

/**
 * One decrypted message. Only the field for its [kind] is set: [text] for TEXT (and a PHOTO's caption), [presetId]
 * plus an optional [emoji] for PRESET, [durationMs] and [blobId] for VOICE, [blobId] for PHOTO.
 *
 * @property locked the epoch key is not on this device yet ("waiting for a parent device"); no content is set.
 * @property authorName who wrote a [MessageDirection.THEIRS] message ("Mum", "Leo"), when known.
 * @property authorDeviceId the device that wrote it: a VOICE or PHOTO blob must come from the same device.
 * @property photoSha256 a PHOTO's lower-case hex SHA-256 from its signed content: the blob must decrypt to it.
 * @property fromParentPhone a kid's message written in kid mode on a parent's phone (D-M3-12): anyone holding that
 *   phone could have written it, so it is labelled.
 */
data class ChatMessage(
    val id: String,
    val direction: MessageDirection,
    val kind: MessageKind,
    val createdAt: Instant,
    val sendState: SendState = SendState.SENT,
    val locked: Boolean = false,
    val text: String? = null,
    val presetId: String? = null,
    val emoji: String? = null,
    val durationMs: Long? = null,
    val blobId: String? = null,
    val authorName: String? = null,
    val authorDeviceId: String? = null,
    val photoSha256: String? = null,
    val fromParentPhone: Boolean = false,
) {
    override fun toString(): String =
        "ChatMessage(id=$id, direction=$direction, kind=$kind, sendState=$sendState, locked=$locked)"
}

/** What the screen asks to send. */
sealed interface OutgoingMessage {
    /** Free text, at most [MAX_TEXT_LENGTH] characters. */
    data class Text(val text: String) : OutgoingMessage {
        override fun toString(): String = "Text(length=${text.length})"
    }

    /** A preset tile ([ChatPresets]); [emoji] is an optional extra from the emoji row. */
    data class Preset(val presetId: String, val emoji: String?) : OutgoingMessage {
        override fun toString(): String = "Preset(presetId=$presetId)"
    }

    /** A recorded voice note. */
    data class Voice(val clip: VoiceClip) : OutgoingMessage {
        override fun toString(): String = "Voice(durationMs=${clip.durationMs})"
    }

    /** A photo, already downscaled by [PickPhoto]. */
    class Photo(val jpeg: ByteArray, val caption: String? = null) : OutgoingMessage {
        override fun equals(other: Any?): Boolean =
            other is Photo && other.jpeg.contentEquals(jpeg) && other.caption == caption

        override fun hashCode(): Int = 31 * jpeg.contentHashCode() + caption.hashCode()

        override fun toString(): String = "Photo(bytes=${jpeg.size})"
    }

    companion object {
        /** Longest TEXT message (M4 §1.3). */
        const val MAX_TEXT_LENGTH: Int = 1_000
    }
}

/**
 * Writes outgoing messages (`MessageWrites` plus, for media, the blob first). A success means the message is stored
 * and queued; [MessageReader] then shows it with its [SendState].
 *
 * Implementations run every write to the end in an application-scoped, non-cancellable writer (review M6): a screen
 * that is closed while a send is under way does not leave a half-written blob or message behind. The caller may stop
 * waiting; the write still completes.
 */
interface MessageSender {
    suspend fun send(threadId: KidId, message: OutgoingMessage): Result<Unit>

    /** Queues a stored [SendState.FAILED] message again. */
    suspend fun retry(threadId: KidId, messageId: String): Result<Unit>

    /**
     * Removes a stored [SendState.BLOCKED] message of this device, with what it queued (D-M4-19). Its place in the
     * thread's sequence stays used, so the other devices skip it after a day. Fails for any other message.
     */
    suspend fun discard(threadId: KidId, messageId: String): Result<Unit>
}

/** Whether this device can encrypt in a thread (M4 §1.2: latest epoch unwrappable, no revoked member in it). */
fun interface ThreadKeyStatus {
    fun observe(threadId: KidId): Flow<ThreadKeyState>
}

enum class ThreadKeyState {
    USABLE,

    /** No usable thread key yet: the device shows "waiting for a parent device" and does not send. */
    WAITING_FOR_PARENT,
}

/** A recorded voice note: AAC-LC bytes and the length. */
class VoiceClip(val bytes: ByteArray, val durationMs: Long) {
    override fun equals(other: Any?): Boolean =
        other is VoiceClip && other.bytes.contentEquals(bytes) && other.durationMs == durationMs

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + durationMs.hashCode()

    override fun toString(): String = "VoiceClip(bytes=${bytes.size}, durationMs=$durationMs)"
}

/**
 * The microphone. The screen asks for `RECORD_AUDIO` before [start]; the recorder must also stop by itself at
 * [MAX_DURATION_MS] (the screen stops it then too).
 */
interface VoiceRecorder {
    /** Starts recording; fails when the microphone is unavailable or permission is missing. */
    suspend fun start(): Result<Unit>

    /** Stops and returns the clip. */
    suspend fun stop(): Result<VoiceClip>

    /** Stops and throws the recording away. Safe to call when not recording. */
    fun cancel()

    companion object {
        /** Longest voice note (M4 §2). */
        const val MAX_DURATION_MS: Long = 30_000

        /** Shorter clips are discarded as accidental taps. */
        const val MIN_DURATION_MS: Long = 500
    }
}

/** Plays voice notes, one at a time. */
interface VoicePlayer {
    /** Plays [bytes] and returns when playback ends; cancelling the caller stops it. */
    suspend fun play(bytes: ByteArray)

    /** Stops whatever is playing. */
    fun stop()
}

/** Fetches, verifies and decrypts a blob (`SyncTransport.fetchBlob` plus the local cache). */
fun interface BlobLoader {
    /** The plaintext of [blobId]; a failure when it is missing, locked, or not what [expected] says (audit M5). */
    suspend fun load(blobId: String, expected: BlobExpectation): Result<ByteArray>
}

/**
 * What a message's blob must be (audit M5): a blob of [threadId]'s thread, written by [authorDeviceId] (the message's
 * author device), whose plaintext has the SHA-256 [sha256] when the message names one (a photo). Never logged.
 */
data class BlobExpectation(val threadId: KidId, val authorDeviceId: String?, val sha256: String?)

/**
 * The kindness hook (M6 fills it): looks at text a kid is about to send. It never blocks: a [FilterVerdict.Nudge] is
 * shown to the kid, who may still send. Implementations must not log or store [text].
 */
fun interface OutgoingMessageFilter {
    suspend fun check(text: String, tier: KidUiTier): FilterVerdict

    companion object {
        /** Lets everything through; the default until M6. */
        val None: OutgoingMessageFilter = OutgoingMessageFilter { _, _ -> FilterVerdict.Ok }
    }
}

sealed interface FilterVerdict {
    data object Ok : FilterVerdict

    /** Worth a second look; [suggestion] is a kinder wording, when the filter has one. */
    data class Nudge(val suggestion: String?) : FilterVerdict {
        override fun toString(): String = "Nudge(hasSuggestion=${suggestion != null})"
    }
}

/** Turns an image picked in the system photo picker into the bytes to send: at most 1280 px, JPEG q80. */
fun interface PickPhoto {
    /** Reads the content [uri]; fails when it cannot be read or decoded. */
    suspend fun read(uri: String): Result<ByteArray>
}

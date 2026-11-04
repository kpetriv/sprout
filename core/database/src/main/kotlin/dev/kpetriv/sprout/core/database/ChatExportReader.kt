package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.MessageEntity
import dev.kpetriv.sprout.core.domainledger.chat.MessageKind
import dev.kpetriv.sprout.core.domainledger.chat.MessagePlaintext
import dev.kpetriv.sprout.core.domainledger.chat.MessagePlaintextCodec
import dev.kpetriv.sprout.core.model.BlobId
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.MessageId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.ThreadId
import dev.kpetriv.sprout.core.sync.BlobChunkJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import kotlin.time.Instant

/**
 * One chat message as the readable export writes it (M7 §3, D-M7-9): the time shown on screen, the author's role, the
 * kind and its content, and the media file name of a photo or voice note. [locked] messages (no key on this device
 * yet) carry no content. Message content is personal data: `toString` prints ids and the kind only.
 *
 * @property text a TEXT message's text, or a PHOTO's caption.
 * @property media the file name of the photo (`<blobId>.jpg`) or voice note (`<blobId>.m4a`) under `chat/media/`;
 *   null for other kinds.
 */
@Suppress("LongParameterList") // One field per exported fact.
class ExportedMessage internal constructor(
    val messageId: MessageId,
    val sentAt: Instant,
    val authorRole: Role,
    val kind: MessageKind,
    val text: String?,
    val presetId: String?,
    val emoji: String?,
    val durationMs: Long?,
    val media: String?,
    val locked: Boolean,
    val late: Boolean,
    internal val blobId: BlobId?,
    internal val authorDeviceId: DeviceId,
    internal val photoSha256: String?,
) {
    override fun toString(): String = "ExportedMessage(messageId=$messageId, kind=$kind, locked=$locked)"
}

/** One kid's thread, oldest message first. */
class ExportedThread(val threadId: ThreadId, val messages: List<ExportedMessage>) {
    override fun toString(): String = "ExportedThread(threadId=$threadId, messages=${messages.size})"
}

/** A photo or voice note the export could not include, and why. */
data class MissingMedia(val messageId: MessageId, val media: String, val reason: Reason) {
    enum class Reason {
        /** Not in this device's cache, and fetching it failed or was not possible. */
        NOT_AVAILABLE,

        /** No key on this device opens it yet. */
        LOCKED,

        /** It is not what its message says (another blob, or a different photo): never exported. */
        INVALID,
    }
}

/** Where [ChatExportReader.exportMedia] writes each decrypted file; the bytes are wiped after the call returns. */
fun interface MediaSink {
    suspend fun write(fileName: String, bytes: ByteArray)
}

/** Fetches the chunks of a blob that is not cached (the app: `SyncTransport.fetchBlob`); a failure is thrown. */
fun interface BlobFetcher {
    suspend fun fetch(blobId: BlobId): List<BlobChunkJson>
}

/**
 * The device-side, readable chat export (M7 §3, D-M7-9): reads this device's stored chat messages per thread and
 * decodes their stored plaintext (decrypted when they arrived) into [ExportedThread]s, and writes the photos and voice
 * notes from the blob cache, decrypted ([exportMedia]). The server export stays ciphertext.
 *
 * The caller authorises the parent (biometric or PIN) before calling; every read here also refuses anything but a
 * PARENT session ([IllegalStateException]). Visible messages only (received, locked and this device's own); held and
 * quarantined rows never carry content. Nothing here is logged.
 */
class ChatExportReader @Inject constructor(
    private val database: SproutDatabase,
    private val blobs: ChatBlobs,
    private val sessions: SessionStore,
) {
    private val messages = database.messageDao()

    /** The threads that have a visible message. */
    suspend fun threads(): List<ThreadId> {
        requireParent()
        return messages.visibleThreadIds().map(::ThreadId)
    }

    /** [threadId]'s visible messages, oldest first (the order the thread shows them). */
    suspend fun read(threadId: ThreadId): ExportedThread {
        requireParent()
        return ExportedThread(threadId, messages.visibleIn(threadId.value).map { it.toExported() })
    }

    /**
     * Writes every photo and voice note of [thread] to [sink] as `<blobId>.jpg` or `<blobId>.m4a`, decrypted and
     * checked against its message (thread, author device, and a photo's hash). A blob that is not cached is fetched
     * with [fetch] when one is given (best effort: a failure lists it as missing). Returns what could not be written.
     */
    suspend fun exportMedia(thread: ExportedThread, sink: MediaSink, fetch: BlobFetcher? = null): List<MissingMedia> {
        requireParent()
        val missing = mutableListOf<MissingMedia>()
        val written = mutableSetOf<String>()
        val withMedia = thread.messages.filter { !it.locked && it.blobId != null && it.media != null }
        for (message in withMedia) {
            val media = requireNotNull(message.media)
            if (media in written) continue
            when (val opened = open(thread.threadId, message, requireNotNull(message.blobId), fetch)) {
                is Opened.Bytes -> try {
                    sink.write(media, opened.bytes)
                    written += media
                } finally {
                    opened.bytes.fill(0)
                }

                is Opened.Missing -> missing += MissingMedia(message.messageId, media, opened.reason)
            }
        }
        // A locked message's media is missing too: it cannot be opened until a key arrives.
        thread.messages.filter { it.locked && it.media != null }.forEach {
            missing += MissingMedia(it.messageId, requireNotNull(it.media), MissingMedia.Reason.LOCKED)
        }
        return missing
    }

    private sealed interface Opened {
        class Bytes(val bytes: ByteArray) : Opened

        class Missing(val reason: MissingMedia.Reason) : Opened
    }

    // A fetch is best effort: any failure lists the media as missing, and its message may quote server data.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun open(
        threadId: ThreadId,
        message: ExportedMessage,
        blobId: BlobId,
        fetch: BlobFetcher?,
    ): Opened {
        val expected = BlobExpectation(BlobTarget.Thread(threadId), message.authorDeviceId, message.photoSha256)
        return try {
            val cached = blobs.open(blobId, expected)
            when {
                cached != null -> Opened.Bytes(cached)
                fetch == null -> Opened.Missing(MissingMedia.Reason.NOT_AVAILABLE)
                else -> Opened.Bytes(blobs.ingest(blobId, fetch.fetch(blobId), expected))
            }
        } catch (unavailable: BlobUnavailableException) {
            Opened.Missing(
                when (unavailable.reason) {
                    BlobUnavailableException.Reason.LOCKED -> MissingMedia.Reason.LOCKED
                    BlobUnavailableException.Reason.INVALID -> MissingMedia.Reason.INVALID
                    BlobUnavailableException.Reason.NOT_FOUND -> MissingMedia.Reason.NOT_AVAILABLE
                },
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Opened.Missing(MissingMedia.Reason.NOT_AVAILABLE)
        }
    }

    private suspend fun requireParent() {
        check(sessions.get()?.role == Role.PARENT) { "only a parent exports the chat" }
    }

    private fun MessageEntity.toExported(): ExportedMessage {
        val content = plaintext?.let { MessagePlaintextCodec.decode(kind, it) }
        val blob = blobId?.let(::BlobId)
        return ExportedMessage(
            messageId = MessageId(messageId),
            sentAt = Instant.fromEpochMilliseconds(displayedAtEpochMs),
            authorRole = authorRole,
            kind = kind,
            text = when (content) {
                is MessagePlaintext.Text -> content.text
                is MessagePlaintext.Photo -> content.caption
                else -> null
            },
            presetId = (content as? MessagePlaintext.Preset)?.presetId,
            emoji = (content as? MessagePlaintext.Preset)?.emoji,
            durationMs = (content as? MessagePlaintext.Voice)?.durationMs,
            media = blob?.let { mediaName(it, kind) },
            locked = content == null,
            late = late,
            blobId = blob,
            authorDeviceId = authorDeviceId,
            photoSha256 = (content as? MessagePlaintext.Photo)?.photoRef?.sha256,
        )
    }

    companion object {
        /** The media file name of a [kind] message's blob [blobId]; null for kinds without media. */
        fun mediaName(blobId: BlobId, kind: MessageKind): String? = when (kind) {
            MessageKind.PHOTO -> "${blobId.value}.jpg"
            MessageKind.VOICE -> "${blobId.value}.m4a"
            else -> null
        }

        /**
         * [thread] as the export's `chat/<kidId>.json`: `threadId` and `messages`, each with `messageId`, `sentAt`
         * (ISO 8601, UTC), `authorRole`, `kind`, `locked`, `late` and, when set, `text`, `presetId`, `emoji`,
         * `durationMs` and `media`.
         */
        fun toJson(thread: ExportedThread): JsonObject = buildJsonObject {
            put("threadId", thread.threadId.value)
            put(
                "messages",
                buildJsonArray {
                    thread.messages.forEach { add(toJson(it)) }
                },
            )
        }

        /** [missing] as the export's `chat/missing.json` entries: `messageId`, `media` and `reason`. */
        fun toJson(missing: List<MissingMedia>): JsonArray = buildJsonArray {
            missing.forEach { entry ->
                add(
                    buildJsonObject {
                        put("messageId", entry.messageId.value)
                        put("media", entry.media)
                        put("reason", entry.reason.name)
                    },
                )
            }
        }

        private fun toJson(message: ExportedMessage): JsonObject = buildJsonObject {
            put("messageId", message.messageId.value)
            put("sentAt", message.sentAt.toString())
            put("authorRole", message.authorRole.name)
            put("kind", message.kind.name)
            put("locked", message.locked)
            put("late", message.late)
            message.text?.let { put("text", it) }
            message.presetId?.let { put("presetId", it) }
            message.emoji?.let { put("emoji", it) }
            message.durationMs?.let { put("durationMs", it) }
            message.media?.let { put("media", it) }
        }
    }
}

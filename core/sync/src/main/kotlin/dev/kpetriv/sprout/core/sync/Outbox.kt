package dev.kpetriv.sprout.core.sync

import kotlinx.serialization.json.JsonObject

/**
 * The local outbox as the engine drains it (rows written with every local change, ADR-0001).
 *
 * [due] has **head-of-line** semantics: it returns pending items in `seq` order and stops at the first item that is
 * not yet due, so an item waiting for its backoff holds back everything queued after it. That keeps the push order
 * equal to the write order across syncs (a device's ledger events form a hash chain).
 *
 * **Blocked outbox.** An item whose kind [blocks on refusal][OutboxItem.Kind.blocksOnRefusal] (a ledger event, a chat
 * message, a blob chunk) and that the server refused for good stays queued with its next attempt
 * [SyncEngine.EVENT_REJECTION_RETRY_MS] later ([fail] with a [SyncError.Permanent]), so it holds back everything after
 * it. Implementations that back a UI expose that state as `observeBlocked()`: the lowest queued `seq` whose latest
 * failure was a blocking permanent refusal, **of any of those kinds** (messages and blob chunks included, not only
 * ledger events), with the server's code; null when nothing blocks (core/database `RoomOutboxSource`, the test
 * fixture `InMemoryOutboxSource`). It is not a member of this interface because the engine never reads it.
 */
interface OutboxSource {
    /**
     * Up to [limit] pending items in `seq` order, stopping at the first one whose next attempt is after
     * [nowEpochMs].
     */
    suspend fun due(nowEpochMs: Long, limit: Int): List<OutboxItem>

    /** The item was delivered (or permanently refused); remove it. */
    suspend fun ack(seq: Long)

    /** The item failed with [error]; count the attempt, store the error's code and retry at [nextAttemptAtEpochMs]. */
    suspend fun fail(seq: Long, error: SyncError, nextAttemptAtEpochMs: Long)
}

/**
 * One queued change; exactly the field of its [kind] is set.
 *
 * @property eventJson for [Kind.EVENT]: the event's `LedgerJson` tree.
 * @property document for [Kind.DOCUMENT]: the signed envelope.
 * @property messageJson for [Kind.MESSAGE]: the signed message's `MessageJson` tree (core/domain-ledger
 *   `MessageEnvelope`).
 * @property blobChunk for [Kind.BLOB_CHUNK]: one signed chunk of an encrypted blob, header and bytes. A blob's chunks
 *   are queued before the message that refers to it.
 * @property attempts failed attempts so far; the backoff grows with it.
 */
@Suppress("LongParameterList") // One optional field per kind.
class OutboxItem(
    val seq: Long,
    val kind: Kind,
    val eventJson: JsonObject?,
    val document: DocumentEnvelope?,
    val attempts: Int,
    val messageJson: JsonObject? = null,
    val blobChunk: BlobChunkJson? = null,
) {
    init {
        require(attempts >= 0) { "attempts must not be negative" }
        val set = listOfNotNull(
            eventJson?.let { Kind.EVENT },
            document?.let { Kind.DOCUMENT },
            messageJson?.let { Kind.MESSAGE },
            blobChunk?.let { Kind.BLOB_CHUNK },
        )
        require(set == listOf(kind)) { "an item carries exactly the field of its kind" }
    }

    val pushId: PushItemId
        get() = when (kind) {
            Kind.EVENT -> PushItemId.ofEvent(requireNotNull(eventJson))
            Kind.DOCUMENT -> PushItemId.ofDocument(requireNotNull(document))
            Kind.MESSAGE -> PushItemId.ofMessage(requireNotNull(messageJson))
            Kind.BLOB_CHUNK -> PushItemId.ofBlobChunk(requireNotNull(blobChunk))
        }

    /** The same item after one more failed attempt. */
    fun withAttempts(attempts: Int): OutboxItem =
        OutboxItem(seq, kind, eventJson, document, attempts, messageJson, blobChunk)

    override fun toString(): String = "OutboxItem(seq=$seq, kind=$kind, pushId=$pushId, attempts=$attempts)"

    /** This item as handed to [SyncTransport.push]. */
    fun toPushItem(): PushItem = when (kind) {
        Kind.EVENT -> PushItem.Event(requireNotNull(eventJson))
        Kind.DOCUMENT -> PushItem.Document(requireNotNull(document))
        Kind.MESSAGE -> PushItem.Message(requireNotNull(messageJson))
        Kind.BLOB_CHUNK -> PushItem.BlobChunk(requireNotNull(blobChunk))
    }

    /**
     * What an item carries. [EVENT] and [MESSAGE] are sequenced per author device (a hash chain, a per-thread `seq`);
     * a [BLOB_CHUNK] is queued before the message or chore submission that names its blob. A permanent refusal of any
     * of the three keeps it queued and blocks the outbox ([blocksOnRefusal], [SyncEngine]), so nothing that depends on
     * it goes out; a [DOCUMENT] refused for good is acked.
     */
    enum class Kind {
        EVENT,
        DOCUMENT,
        MESSAGE,
        BLOB_CHUNK,
        ;

        /** A permanent refusal keeps the item queued and stops the push (everything after it waits). */
        val blocksOnRefusal: Boolean get() = this != DOCUMENT
    }

    companion object {
        fun event(seq: Long, json: JsonObject, attempts: Int = 0): OutboxItem =
            OutboxItem(seq, Kind.EVENT, json, null, attempts)

        fun document(seq: Long, envelope: DocumentEnvelope, attempts: Int = 0): OutboxItem =
            OutboxItem(seq, Kind.DOCUMENT, null, envelope, attempts)

        fun message(seq: Long, json: JsonObject, attempts: Int = 0): OutboxItem =
            OutboxItem(seq, Kind.MESSAGE, null, null, attempts, messageJson = json)

        fun blobChunk(seq: Long, chunk: BlobChunkJson, attempts: Int = 0): OutboxItem =
            OutboxItem(seq, Kind.BLOB_CHUNK, null, null, attempts, blobChunk = chunk)
    }
}

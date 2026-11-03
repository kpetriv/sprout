package dev.kpetriv.sprout.core.sync

import kotlinx.serialization.json.JsonObject

/**
 * The local outbox as the engine drains it (rows written with every local change, ADR-0001).
 *
 * **Two lanes** (D-M4-22). The outbox is two independent queues, each in `seq` order: the **ledger lane**
 * ([OutboxItem.Lane.LEDGER]: events and documents) and the **chat lane** ([OutboxItem.Lane.CHAT]: messages and blob
 * chunks). Nothing in one lane waits for the other, so a chat item that is deferred (a blob over the daily budget) or
 * refused never holds back a ledger event, and a refused event never holds back a chat message.
 *
 * [due] has **head-of-line** semantics per lane: within a lane it returns pending items in `seq` order and stops at
 * the first item that is not yet due, so an item waiting for its backoff holds back everything queued after it **in its
 * lane**. That keeps each lane's push order equal to its write order across syncs (a device's ledger events form a
 * hash chain; its messages a per-thread `seq`, and a blob's chunks go before the message that names it).
 *
 * **Blocked lane.** An item whose kind [blocks on refusal][OutboxItem.Kind.blocksOnRefusal] (a ledger event, a chat
 * message, a blob chunk) and that the server refused for good stays queued with its next attempt
 * [SyncEngine.EVENT_REJECTION_RETRY_MS] later ([fail] with a [SyncError.Permanent]), so it holds back everything after
 * it in its lane. Implementations that back a UI expose that state as `observeBlocked()`: the lowest queued `seq`
 * whose latest failure was a blocking permanent refusal, **of any of those kinds** (messages and blob chunks included,
 * not only ledger events), with the server's code, optionally narrowed to one lane; null when nothing blocks
 * (core/database `RoomOutboxSource`, the test fixture `InMemoryOutboxSource`). It is not a member of this interface
 * because the engine never reads it.
 */
interface OutboxSource {
    /**
     * Up to [limit] pending items of [lanes], merged in `seq` order; within each lane the items stop at the first one
     * whose next attempt is after [nowEpochMs] (head of line per lane).
     */
    suspend fun due(nowEpochMs: Long, limit: Int, lanes: Set<OutboxItem.Lane> = OutboxItem.Lane.ALL): List<OutboxItem>

    /** The item was delivered; remove it. */
    suspend fun ack(seq: Long)

    /**
     * The item was refused for good and is dropped (a document: [OutboxItem.Kind.blocksOnRefusal] is false): remove it
     * **and** record [rejection] in the same transaction where the store keeps an audit log (core/database writes the
     * `SYNC_REJECTED` row with the delete, audit A2-L3), so no crash can leave a refused item looking delivered. The
     * default only acks.
     */
    suspend fun ackRejected(seq: Long, rejection: PushRejection) = ack(seq)

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
     * of the three keeps it queued and blocks its [lane] ([blocksOnRefusal], [SyncEngine]), so nothing queued after it
     * in that lane goes out; a [DOCUMENT] refused for good is acked.
     */
    enum class Kind {
        EVENT,
        DOCUMENT,
        MESSAGE,
        BLOB_CHUNK,
        ;

        /** A permanent refusal keeps the item queued and stops its lane (everything after it in the lane waits). */
        val blocksOnRefusal: Boolean get() = this != DOCUMENT

        /** The outbox lane of this kind (D-M4-22). */
        val lane: Lane
            get() = when (this) {
                EVENT, DOCUMENT -> Lane.LEDGER
                MESSAGE, BLOB_CHUNK -> Lane.CHAT
            }
    }

    /**
     * One of the outbox's two independent head-of-line queues (D-M4-22): [LEDGER] carries events and documents, [CHAT]
     * messages and blob chunks. Order and head-of-line blocking hold within a lane, never across lanes.
     */
    enum class Lane {
        LEDGER,
        CHAT,
        ;

        /** The kinds queued in this lane. */
        val kinds: List<Kind> get() = Kind.entries.filter { it.lane == this }

        companion object {
            /** Both lanes. */
            val ALL: Set<Lane> = entries.toSet()
        }
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

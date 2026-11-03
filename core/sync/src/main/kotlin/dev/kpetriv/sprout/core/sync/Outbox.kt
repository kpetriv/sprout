package dev.kpetriv.sprout.core.sync

import kotlinx.serialization.json.JsonObject

/**
 * The local outbox as the engine drains it (rows written with every local change, ADR-0001).
 *
 * [due] has **head-of-line** semantics: it returns pending items in `seq` order and stops at the first item that is
 * not yet due, so an item waiting for its backoff holds back everything queued after it. That keeps the push order
 * equal to the write order across syncs (a device's ledger events form a hash chain).
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

    /**
     * What an item carries. [EVENT] and [MESSAGE] are sequenced per author device (a hash chain, a per-thread `seq`),
     * so a permanent refusal keeps them queued and blocks the outbox ([SyncEngine]); [DOCUMENT] and [BLOB_CHUNK] are
     * acked when refused for good.
     */
    enum class Kind { EVENT, DOCUMENT, MESSAGE, BLOB_CHUNK }

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

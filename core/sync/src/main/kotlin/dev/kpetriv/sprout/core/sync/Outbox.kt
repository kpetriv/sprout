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
 * One queued change.
 *
 * @property eventJson for [Kind.EVENT]: the event's `LedgerJson` tree.
 * @property document for [Kind.DOCUMENT]: the signed envelope.
 * @property attempts failed attempts so far; the backoff grows with it.
 */
class OutboxItem(
    val seq: Long,
    val kind: Kind,
    val eventJson: JsonObject?,
    val document: DocumentEnvelope?,
    val attempts: Int,
) {
    init {
        require(attempts >= 0) { "attempts must not be negative" }
        when (kind) {
            Kind.EVENT -> require(eventJson != null && document == null) { "an event item carries only an event" }

            Kind.DOCUMENT -> require(document != null && eventJson == null) {
                "a document item carries only a document"
            }
        }
    }

    val pushId: PushItemId
        get() = when (kind) {
            Kind.EVENT -> PushItemId.ofEvent(requireNotNull(eventJson))
            Kind.DOCUMENT -> PushItemId.ofDocument(requireNotNull(document))
        }

    override fun toString(): String = "OutboxItem(seq=$seq, kind=$kind, pushId=$pushId, attempts=$attempts)"

    enum class Kind { EVENT, DOCUMENT }

    companion object {
        fun event(seq: Long, json: JsonObject, attempts: Int = 0): OutboxItem =
            OutboxItem(seq, Kind.EVENT, json, null, attempts)

        fun document(seq: Long, envelope: DocumentEnvelope, attempts: Int = 0): OutboxItem =
            OutboxItem(seq, Kind.DOCUMENT, null, envelope, attempts)
    }
}

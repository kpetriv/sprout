package dev.kpetriv.sprout.core.sync

import kotlinx.serialization.json.JsonObject

/**
 * An in-memory outbox with the [OutboxSource] contract: head-of-line [due], one row per document (a newer write of
 * the same document replaces a pending row and takes a new `seq`, like core/database's upsert).
 */
class InMemoryOutboxSource : OutboxSource {
    /** One queued row and its retry state. */
    data class Row(val item: OutboxItem, val nextAttemptAtEpochMs: Long = 0, val lastError: String? = null)

    private val rows = sortedMapOf<Long, Row>()
    private var nextSeq = 1L

    @Synchronized
    fun enqueueEvent(json: JsonObject): Long = (nextSeq++).also { rows[it] = Row(OutboxItem.event(it, json)) }

    @Synchronized
    fun enqueueDocument(envelope: DocumentEnvelope): Long {
        val id = PushItemId.ofDocument(envelope)
        rows.entries.removeAll { it.value.item.pushId == id }
        return (nextSeq++).also { rows[it] = Row(OutboxItem.document(it, envelope)) }
    }

    /** Pending rows in `seq` order. */
    @Synchronized
    fun rows(): List<Row> = rows.values.toList()

    @Synchronized
    fun row(seq: Long): Row? = rows[seq]

    override suspend fun due(nowEpochMs: Long, limit: Int): List<OutboxItem> = synchronized(this) {
        rows.values.asSequence().takeWhile {
            it.nextAttemptAtEpochMs <= nowEpochMs
        }.take(limit).map { it.item }.toList()
    }

    override suspend fun ack(seq: Long) {
        synchronized(this) { rows.remove(seq) }
    }

    override suspend fun fail(seq: Long, error: SyncError, nextAttemptAtEpochMs: Long) {
        synchronized(this) {
            val item = rows[seq]?.item ?: return
            rows[seq] = Row(
                item = OutboxItem(item.seq, item.kind, item.eventJson, item.document, item.attempts + 1),
                nextAttemptAtEpochMs = nextAttemptAtEpochMs,
                lastError = error.code,
            )
        }
    }
}

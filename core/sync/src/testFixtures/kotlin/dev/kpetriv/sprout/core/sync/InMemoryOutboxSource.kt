package dev.kpetriv.sprout.core.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject

/**
 * An in-memory outbox with the [OutboxSource] contract: head-of-line [due], one row per document (a newer write of
 * the same document replaces a pending row and takes a new `seq`, like core/database's upsert), and
 * [observeBlocked] as the contract describes it (any kind that [blocks on refusal][OutboxItem.Kind.blocksOnRefusal]).
 */
class InMemoryOutboxSource : OutboxSource {
    /**
     * One queued row and its retry state; [blocking] when its latest failure was a permanent refusal of a kind that
     * blocks the outbox.
     */
    data class Row(
        val item: OutboxItem,
        val nextAttemptAtEpochMs: Long = 0,
        val lastError: String? = null,
        val blocking: Boolean = false,
    )

    /** The row holding the outbox back: its `seq` and the server's code. */
    data class Blocked(val seq: Long, val code: String)

    private val rows = sortedMapOf<Long, Row>()
    private var nextSeq = 1L
    private val blocked = MutableStateFlow<Blocked?>(null)

    /** The lowest queued row whose latest failure was a blocking permanent refusal (event, message or blob chunk). */
    fun observeBlocked(): Flow<Blocked?> = blocked.asStateFlow()

    private fun publishBlocked() {
        blocked.value = rows.values.firstOrNull { it.blocking }?.let { Blocked(it.item.seq, it.lastError.orEmpty()) }
    }

    @Synchronized
    fun enqueueEvent(json: JsonObject): Long = (nextSeq++).also { rows[it] = Row(OutboxItem.event(it, json)) }

    @Synchronized
    fun enqueueMessage(json: JsonObject): Long = (nextSeq++).also { rows[it] = Row(OutboxItem.message(it, json)) }

    @Synchronized
    fun enqueueBlobChunk(chunk: BlobChunkJson): Long =
        (nextSeq++).also { rows[it] = Row(OutboxItem.blobChunk(it, chunk)) }

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
        synchronized(this) {
            rows.remove(seq)
            publishBlocked()
        }
    }

    override suspend fun fail(seq: Long, error: SyncError, nextAttemptAtEpochMs: Long) {
        synchronized(this) {
            val item = rows[seq]?.item ?: return
            rows[seq] = Row(
                item = item.withAttempts(item.attempts + 1),
                nextAttemptAtEpochMs = nextAttemptAtEpochMs,
                lastError = error.code,
                blocking = error is SyncError.Permanent && item.kind.blocksOnRefusal,
            )
            publishBlocked()
        }
    }
}

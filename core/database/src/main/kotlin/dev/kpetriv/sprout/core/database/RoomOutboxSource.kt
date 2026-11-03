package dev.kpetriv.sprout.core.database

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.database.dao.OutboxHead
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.AuditLogEntity
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.domainledger.chat.BlobJson
import dev.kpetriv.sprout.core.domainledger.chat.MessageJson
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.sync.BlobChunkJson
import dev.kpetriv.sprout.core.sync.DocumentJsonCodec
import dev.kpetriv.sprout.core.sync.OutboxItem
import dev.kpetriv.sprout.core.sync.OutboxSource
import dev.kpetriv.sprout.core.sync.PushRejection
import dev.kpetriv.sprout.core.sync.SyncError
import dev.kpetriv.sprout.core.sync.code
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject
import kotlin.time.Instant

/**
 * [OutboxSource] over the `outbox` table, **head of line per lane** (D-M3-15, D-M4-22): the ledger lane
 * (`LEDGER_EVENT`, `DOCUMENT`) and the chat lane (`MESSAGE`, `BLOB_CHUNK`) are read separately; within each, [due]
 * reads rows in `seq` order and stops at the first one whose `nextAttemptAtEpochMs` is after now, so an item waiting
 * for its backoff holds back everything queued after it in its lane, and each lane's push order stays its write order
 * (a device's events form a hash chain). The two lanes' due rows are merged by `seq`. Rows are read without their
 * bytes; a blob chunk's bytes are loaded for that item only, and one call returns at most [MAX_CHUNKS_PER_BATCH]
 * chunks (review M5: at most 1.4 MB of chunk data in memory per batch). The rest follow in the next sync, which the
 * outbox trigger requests as soon as the acks leave a due head.
 *
 * A `LEDGER_EVENT` row becomes [OutboxItem.event] with its `LedgerJson` tree; a `DOCUMENT` row [OutboxItem.document]
 * with its signed envelope; a `MESSAGE` row [OutboxItem.message] with its signed `MessageJson` tree; a `BLOB_CHUNK` row
 * [OutboxItem.blobChunk] with its signed header and bytes (queued before the message that names the blob). A row
 * whose payload no longer decodes can never be sent: it is deleted and audited `SYNC_REJECTED` (code [UNDECODABLE])
 * instead of blocking the queue. [ack] deletes; [ackRejected] deletes and writes the `SYNC_REJECTED` row in one
 * transaction (audit A2-L3); [fail] counts the attempt and stores only the error's code.
 */
class RoomOutboxSource @Inject constructor(private val database: SproutDatabase, private val clock: Clock) :
    OutboxSource {
    private val outbox = database.outboxDao()
    private val audit = database.auditLogDao()

    override suspend fun due(nowEpochMs: Long, limit: Int, lanes: Set<OutboxItem.Lane>): List<OutboxItem> {
        require(limit > 0) { "limit must be positive" }
        // Each lane is sorted, so a prefix of the merge is a prefix of each lane: head of line holds per lane.
        return lanes.flatMap { dueOf(it, nowEpochMs, limit) }.sortedBy { it.seq }.take(limit)
    }

    /** Up to [limit] due items of [lane], in `seq` order, stopping at its first row not yet due. */
    private suspend fun dueOf(lane: OutboxItem.Lane, nowEpochMs: Long, limit: Int): List<OutboxItem> {
        val items = ArrayList<OutboxItem>(limit)
        var chunks = 0
        for (row in outbox.headOf(lane.outboxKinds, limit)) {
            val isChunk = row.kind == OutboxKind.BLOB_CHUNK
            if (row.nextAttemptAtEpochMs > nowEpochMs || (isChunk && chunks == MAX_CHUNKS_PER_BATCH)) break
            if (isChunk) chunks++
            val item = itemOf(row)
            if (item == null) drop(row) else items += item
        }
        return items
    }

    override suspend fun ack(seq: Long) = outbox.delete(seq)

    /**
     * Drops a row the server refused for good (a document) **with** its `SYNC_REJECTED` audit row, in one transaction
     * (audit A2-L3): a crash cannot leave the row gone without the refusal on record (`ThreadKeyWrites.settle` reads
     * "row gone, no refusal" as confirmed). The row is written exactly as [auditRejections] writes it, so the report's
     * later audit of the same rejection adds nothing.
     */
    override suspend fun ackRejected(seq: Long, rejection: PushRejection) {
        database.withTransaction {
            outbox.delete(seq)
            auditOnce(rejection)
        }
    }

    override suspend fun fail(seq: Long, error: SyncError, nextAttemptAtEpochMs: Long) =
        outbox.markFailed(seq, error.code, nextAttemptAtEpochMs)

    /**
     * Records what the backend refused for good in one sync (`SyncReport.rejections`): one `SYNC_REJECTED` audit row
     * per `(seq, code)`, with the push id as target and `{code, seq, blocking}` as details (ids and the server's code
     * only). A blocking ledger event (D-M3-33) is offered again every `SyncEngine.EVENT_REJECTION_RETRY_MS` and
     * refused again; that repeat is not audited again. A new code for the same `seq` is.
     */
    suspend fun auditRejections(rejections: List<PushRejection>) {
        if (rejections.isEmpty()) return
        database.withTransaction {
            rejections.forEach { auditOnce(it) }
        }
    }

    /** One `SYNC_REJECTED` row for [rejection] unless an identical one exists. */
    private suspend fun auditOnce(rejection: PushRejection) {
        val details = rejectionDetails(rejection)
        val target = rejection.id.value
        if (audit.countExact(AuditAction.SYNC_REJECTED, target, details) == 0) audit(target, details)
    }

    /**
     * The item holding an outbox lane back (D-M3-33, D-M4-22), for the dashboard's "sync blocked" state and the chat's
     * "sending is blocked": the lowest queued `seq` of a kind that [blocks on refusal][OutboxItem.Kind.blocksOnRefusal]
     * (a ledger event, a chat message or a blob chunk) with a blocking `SYNC_REJECTED` audit row ([auditRejections]),
     * the latest code it was refused with, and when it was first refused; of [lane] only, or of either lane when null
     * (both lanes can be blocked at once: the lower `seq` is shown). Null when nothing blocks; it clears as soon as the
     * item is accepted (its row is acked) or removed (a discarded message, D-M4-19).
     */
    fun observeBlocked(lane: OutboxItem.Lane? = null): Flow<BlockedPush?> = observeBlockedPush(database, lane)

    private suspend fun itemOf(row: OutboxHead): OutboxItem? = when (row.kind) {
        OutboxKind.LEDGER_EVENT -> parseObjectOrNull(row.payload)?.let { OutboxItem.event(row.seq, it, row.attempts) }

        OutboxKind.DOCUMENT -> DocumentJsonCodec.decodeOrNull(row.payload)
            ?.takeIf { it.signature != null }
            ?.let { OutboxItem.document(row.seq, it, row.attempts) }

        OutboxKind.MESSAGE -> MessageJson.decodeOrNull(row.payload)
            ?.takeIf { it.signature != null }
            ?.let { OutboxItem.message(row.seq, MessageJson.toJsonObject(it), row.attempts) }

        OutboxKind.BLOB_CHUNK -> {
            val header = parseObjectOrNull(row.payload)
            val data = outbox.dataOf(row.seq)
            if (header != null && data != null && BlobJson.decodeOrNull(header)?.signature != null) {
                OutboxItem.blobChunk(row.seq, BlobChunkJson(header, data), row.attempts)
            } else {
                null
            }
        }
    }

    private suspend fun drop(row: OutboxHead) {
        database.withTransaction {
            outbox.delete(row.seq)
            audit(row.refId, encode(buildJsonObject { put(CODE, UNDECODABLE) }))
        }
    }

    private suspend fun audit(target: String, detailsJson: String) {
        val session = database.sessionDao().get()
        audit.insert(
            AuditLogEntity(
                atEpochMs = clock.now().toEpochMilliseconds(),
                actorUid = session?.uid ?: LocalTrial.PARENT,
                deviceId = session?.deviceId ?: DeviceId(UNKNOWN_DEVICE),
                action = AuditAction.SYNC_REJECTED,
                target = target,
                detailsJson = detailsJson,
            ),
        )
    }

    private fun parseObjectOrNull(text: String): JsonObject? = try {
        LedgerJson.json.parseToJsonElement(text) as? JsonObject
    } catch (expected: IllegalArgumentException) {
        null
    }

    companion object {
        /** `SYNC_REJECTED` code of an outbox row whose payload cannot be decoded (never sendable). */
        const val UNDECODABLE: String = "undecodable"

        /** Most blob chunks one [due] call returns (review M5). */
        const val MAX_CHUNKS_PER_BATCH: Int = 2

        private const val UNKNOWN_DEVICE = "unknown"
        private const val CODE = "code"
        private const val SEQ = "seq"
        private const val BLOCKING = "blocking"

        /** The audit details of [rejection]; built the same way every time, so equal rejections compare equal. */
        private fun rejectionDetails(rejection: PushRejection): String = encode(
            buildJsonObject {
                put(CODE, rejection.code)
                put(SEQ, rejection.seq)
                put(BLOCKING, rejection.blocking)
            },
        )

        private fun encode(details: JsonObject): String =
            LedgerJson.json.encodeToString(JsonObject.serializer(), details)
    }
}

/**
 * An item the backend refused for good and that holds back the outbox (D-M3-33): its outbox [seq], the server's
 * latest [code] for it, [since] when it was first refused, and which row it is ([kind], [refId]: an event id, a
 * message id, `"<blobId>.<chunk>"`). The dashboard shows "sync blocked"; a chat message (or the blob of one) shows
 * "sending is blocked" with Discard.
 */
data class BlockedPush(
    val seq: Long,
    val code: String,
    val since: Instant,
    val kind: OutboxKind = OutboxKind.LEDGER_EVENT,
    val refId: String = "",
)

/** One blocking refusal read back from the audit log. */
internal class BlockingRejection(val seq: Long, val code: String, val atEpochMs: Long)

/** The blocking refusal recorded in [row] (`SYNC_REJECTED` details `{code, seq, blocking: true}`), or null. */
internal fun blockingRejectionOf(row: AuditLogEntity): BlockingRejection? {
    val details = try {
        LedgerJson.json.parseToJsonElement(row.detailsJson) as? JsonObject
    } catch (expected: IllegalArgumentException) {
        null
    } ?: return null
    val blocking = (details["blocking"] as? JsonPrimitive)?.booleanOrNull == true
    val seq = (details["seq"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    val code = (details["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return if (blocking && seq != null && code != null) BlockingRejection(seq, code, row.atEpochMs) else null
}

/** Kinds whose permanent refusal blocks their outbox lane (`OutboxItem.Kind.blocksOnRefusal`). */
internal val BLOCKING_KINDS: List<OutboxKind> =
    listOf(OutboxKind.LEDGER_EVENT, OutboxKind.MESSAGE, OutboxKind.BLOB_CHUNK)

/** The outbox kinds queued in this lane (`OutboxItem.Kind.lane`, D-M4-22). */
internal val OutboxItem.Lane.outboxKinds: List<OutboxKind>
    get() = when (this) {
        OutboxItem.Lane.LEDGER -> listOf(OutboxKind.LEDGER_EVENT, OutboxKind.DOCUMENT)
        OutboxItem.Lane.CHAT -> listOf(OutboxKind.MESSAGE, OutboxKind.BLOB_CHUNK)
    }

/** [BLOCKING_KINDS] of [lane], or of both lanes when null. */
private fun blockingKinds(lane: OutboxItem.Lane?): List<OutboxKind> =
    if (lane == null) BLOCKING_KINDS else BLOCKING_KINDS.filter { it in lane.outboxKinds }

/** See [RoomOutboxSource.observeBlocked]. The chat screens pass [OutboxItem.Lane.CHAT]. */
internal fun observeBlockedPush(database: SproutDatabase, lane: OutboxItem.Lane? = null): Flow<BlockedPush?> = combine(
    database.outboxDao().observeHeads(blockingKinds(lane)),
    database.auditLogDao().observeAction(AuditAction.SYNC_REJECTED),
    ::blockedPushOf,
).distinctUntilChanged()

/** [RoomOutboxSource.observeBlocked] now, as a one-off read (inside or outside a transaction). */
internal suspend fun currentBlockedPush(database: SproutDatabase, lane: OutboxItem.Lane? = null): BlockedPush? =
    blockedPushOf(
        database.outboxDao().headsOf(blockingKinds(lane)),
        database.auditLogDao().withAction(AuditAction.SYNC_REJECTED),
    )

private fun blockedPushOf(heads: List<OutboxHead>, rows: List<AuditLogEntity>): BlockedPush? {
    val queued = heads.associateBy { it.seq }
    val blocking = rows.mapNotNull { row -> blockingRejectionOf(row)?.takeIf { it.seq in queued } }
    val seq = blocking.minOfOrNull { it.seq } ?: return null
    val forSeq = blocking.filter { it.seq == seq }
    val head = queued.getValue(seq)
    return BlockedPush(
        seq = seq,
        code = forSeq.last().code,
        since = Instant.fromEpochMilliseconds(forSeq.minOf { it.atEpochMs }),
        kind = head.kind,
        refId = head.refId,
    )
}

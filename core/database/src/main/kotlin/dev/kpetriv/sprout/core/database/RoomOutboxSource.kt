package dev.kpetriv.sprout.core.database

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.AuditLogEntity
import dev.kpetriv.sprout.core.database.entity.OutboxEntity
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.model.DeviceId
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
 * [OutboxSource] over the `outbox` table, **head of line** (D-M3-15): [due] reads rows in `seq` order and stops at the
 * first one whose `nextAttemptAtEpochMs` is after now, so an item waiting for its backoff holds back everything
 * queued after it and push order stays write order (a device's events form a hash chain).
 *
 * A `LEDGER_EVENT` row becomes [OutboxItem.event] with its `LedgerJson` tree; a `DOCUMENT` row [OutboxItem.document]
 * with its signed envelope. A row whose payload no longer decodes can never be sent: it is deleted and audited
 * `SYNC_REJECTED` (code [UNDECODABLE]) instead of blocking the queue. [ack] deletes; [fail] counts the attempt and
 * stores only the error's code.
 */
class RoomOutboxSource @Inject constructor(private val database: SproutDatabase, private val clock: Clock) :
    OutboxSource {
    private val outbox = database.outboxDao()
    private val audit = database.auditLogDao()

    override suspend fun due(nowEpochMs: Long, limit: Int): List<OutboxItem> {
        require(limit > 0) { "limit must be positive" }
        val items = ArrayList<OutboxItem>(limit)
        for (row in outbox.head(limit)) {
            if (row.nextAttemptAtEpochMs > nowEpochMs) break
            val item = itemOf(row)
            if (item == null) drop(row) else items += item
        }
        return items
    }

    override suspend fun ack(seq: Long) = outbox.delete(seq)

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
            for (rejection in rejections) {
                val details = rejectionDetails(rejection)
                val target = rejection.id.value
                if (audit.countExact(AuditAction.SYNC_REJECTED, target, details) == 0) audit(target, details)
            }
        }
    }

    /**
     * The ledger event holding the outbox back (D-M3-33), for the dashboard's "sync blocked" state: the lowest queued
     * `seq` with a blocking `SYNC_REJECTED` audit row ([auditRejections]), the latest code it was refused with, and
     * when it was first refused. Null when nothing blocks; it clears as soon as the event is accepted (its row is
     * acked) or removed.
     */
    fun observeBlocked(): Flow<BlockedPush?> = combine(
        outbox.observeSeqs(OutboxKind.LEDGER_EVENT),
        audit.observeAction(AuditAction.SYNC_REJECTED),
    ) { seqs, rows ->
        val queued = seqs.toHashSet()
        val blocking = rows.mapNotNull { row -> blockingRejectionOf(row)?.takeIf { it.seq in queued } }
        val seq = blocking.minOfOrNull { it.seq } ?: return@combine null
        val forSeq = blocking.filter { it.seq == seq }
        BlockedPush(
            seq = seq,
            code = forSeq.last().code,
            since = Instant.fromEpochMilliseconds(forSeq.minOf { it.atEpochMs }),
        )
    }.distinctUntilChanged()

    private fun itemOf(row: OutboxEntity): OutboxItem? = when (row.kind) {
        OutboxKind.LEDGER_EVENT -> parseObjectOrNull(row.payload)?.let { OutboxItem.event(row.seq, it, row.attempts) }

        OutboxKind.DOCUMENT -> DocumentJsonCodec.decodeOrNull(row.payload)
            ?.takeIf { it.signature != null }
            ?.let { OutboxItem.document(row.seq, it, row.attempts) }
    }

    private suspend fun drop(row: OutboxEntity) {
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

    /** One blocking refusal read back from the audit log. */
    private class BlockingRejection(val seq: Long, val code: String, val atEpochMs: Long)

    private fun blockingRejectionOf(row: AuditLogEntity): BlockingRejection? {
        val details = parseObjectOrNull(row.detailsJson) ?: return null
        val blocking = (details[BLOCKING] as? JsonPrimitive)?.booleanOrNull == true
        val seq = (details[SEQ] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        val code = (details[CODE] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return if (blocking && seq != null && code != null) BlockingRejection(seq, code, row.atEpochMs) else null
    }

    private fun parseObjectOrNull(text: String): JsonObject? = try {
        LedgerJson.json.parseToJsonElement(text) as? JsonObject
    } catch (expected: IllegalArgumentException) {
        null
    }

    companion object {
        /** `SYNC_REJECTED` code of an outbox row whose payload cannot be decoded (never sendable). */
        const val UNDECODABLE: String = "undecodable"

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
 * A ledger event the backend refused for good and that holds back the outbox (D-M3-33): its outbox [seq], the
 * server's latest [code] for it, and [since] when it was first refused. The dashboard shows "sync blocked".
 */
data class BlockedPush(val seq: Long, val code: String, val since: Instant)

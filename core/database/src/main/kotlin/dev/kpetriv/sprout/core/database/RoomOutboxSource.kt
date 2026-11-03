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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

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
     * Records what the backend refused for good in one sync (`SyncReport.rejections`, already acked by the engine):
     * one `SYNC_REJECTED` audit row each, with the push id and the server's code only.
     */
    suspend fun auditRejections(rejections: List<PushRejection>) {
        if (rejections.isEmpty()) return
        database.withTransaction {
            rejections.forEach { audit(it.id.value, it.code) }
        }
    }

    private fun itemOf(row: OutboxEntity): OutboxItem? = when (row.kind) {
        OutboxKind.LEDGER_EVENT -> parseObjectOrNull(row.payload)?.let { OutboxItem.event(row.seq, it, row.attempts) }

        OutboxKind.DOCUMENT -> DocumentJsonCodec.decodeOrNull(row.payload)
            ?.takeIf { it.signature != null }
            ?.let { OutboxItem.document(row.seq, it, row.attempts) }
    }

    private suspend fun drop(row: OutboxEntity) {
        database.withTransaction {
            outbox.delete(row.seq)
            audit(row.refId, UNDECODABLE)
        }
    }

    private suspend fun audit(target: String, code: String) {
        val session = database.sessionDao().get()
        database.auditLogDao().insert(
            AuditLogEntity(
                atEpochMs = clock.now().toEpochMilliseconds(),
                actorUid = session?.uid ?: LocalTrial.PARENT,
                deviceId = session?.deviceId ?: DeviceId(UNKNOWN_DEVICE),
                action = AuditAction.SYNC_REJECTED,
                target = target,
                detailsJson = LedgerJson.json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject { put("code", code) },
                ),
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

        private const val UNKNOWN_DEVICE = "unknown"
    }
}

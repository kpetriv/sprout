package dev.kpetriv.sprout.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** What an outbox row carries. */
enum class OutboxKind {
    /**
     * A ledger event; [OutboxEntity.refId] is `<eventId>:<hash>` (the event row's key, so a conflicting copy under a
     * stored eventId gets its own row) and the payload its `LedgerJson` form.
     */
    LEDGER_EVENT,
}

/**
 * Work for the sync engine (M3), written in the same transaction as the change it publishes. Drained in [seq]
 * order; a row is deleted once the transport has acknowledged it.
 *
 * @property nextAttemptAtEpochMs earliest time to retry (backoff); 0 means immediately.
 */
@Entity(
    tableName = "outbox",
    indices = [
        Index(value = ["kind", "refId"], unique = true),
        Index(value = ["nextAttemptAtEpochMs"]),
    ],
)
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val kind: OutboxKind,
    val refId: String,
    val payload: String,
    val createdAtEpochMs: Long,
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    val lastError: String? = null,
    @ColumnInfo(defaultValue = "0") val nextAttemptAtEpochMs: Long = 0,
)

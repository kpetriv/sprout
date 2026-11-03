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

    /**
     * A synced document (`DocTypes` in core/sync): [OutboxEntity.refId] is `<docType>/<docId>`,
     * [OutboxEntity.docType], [OutboxEntity.docId] and [OutboxEntity.lamport] are set, and the payload is the signed
     * `DocumentEnvelope` of the document's latest state (schema version 3; version 2 rows held unsigned JSON and were
     * dropped by the migration). One row per document: a later write of the same document replaces a row that has not
     * been sent yet and takes a new [OutboxEntity.seq].
     */
    DOCUMENT,
}

/**
 * Work for the sync engine (M3), written in the same transaction as the change it publishes. Drained in [seq]
 * order, head of line (D-M3-15); a row is deleted once the transport has acknowledged it or refused it for good.
 *
 * @property nextAttemptAtEpochMs earliest time to retry (backoff); 0 means immediately.
 * @property lastError the `SyncError` code of the last failed attempt; never free text.
 * @property docType for [OutboxKind.DOCUMENT]: the document collection, see `DocTypes`.
 * @property docId for [OutboxKind.DOCUMENT]: the document's id.
 * @property lamport for [OutboxKind.DOCUMENT]: the device Lamport value of the write (last-writer-wins key).
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
    val docType: String? = null,
    val docId: String? = null,
    val lamport: Long? = null,
)

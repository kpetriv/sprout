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

    /**
     * A chat message (M4 §1.3): [OutboxEntity.refId] is the message id and the payload the signed `MessageEnvelope`
     * (`MessageJson`). Immutable: never replaced.
     */
    MESSAGE,

    /**
     * One chunk of an encrypted blob (M4 §2): [OutboxEntity.refId] is the chunk's document id (`<blobId>.<chunk>`),
     * the payload its signed `BlobChunkHeader` (`BlobJson`) and [OutboxEntity.data] the chunk bytes. Queued before the
     * message that names the blob, so head-of-line pushing sends the blob first.
     */
    BLOB_CHUNK,
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
 * @property data for [OutboxKind.BLOB_CHUNK]: the chunk's bytes (E2EE ciphertext); null otherwise.
 */
@Suppress("LongParameterList") // One column per field.
@Entity(
    tableName = "outbox",
    indices = [
        Index(value = ["kind", "refId"], unique = true),
        Index(value = ["nextAttemptAtEpochMs"]),
    ],
)
class OutboxEntity(
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
    val data: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean = other is OutboxEntity && seq == other.seq && kind == other.kind &&
        refId == other.refId && payload == other.payload && createdAtEpochMs == other.createdAtEpochMs &&
        attempts == other.attempts && lastError == other.lastError &&
        nextAttemptAtEpochMs == other.nextAttemptAtEpochMs && docType == other.docType && docId == other.docId &&
        lamport == other.lamport && (data?.contentEquals(other.data) ?: (other.data == null))

    override fun hashCode(): Int = 31 * seq.hashCode() + refId.hashCode()

    override fun toString(): String = "OutboxEntity(seq=$seq, kind=$kind, refId=$refId, attempts=$attempts)"
}

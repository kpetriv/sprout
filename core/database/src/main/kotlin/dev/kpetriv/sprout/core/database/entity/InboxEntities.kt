package dev.kpetriv.sprout.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.model.DeviceId

/**
 * A pulled event that verified but does not link to its author's stored chain yet (a gap before it, M3 §2), or whose
 * account is not known yet. Kept as its raw JSON tree and planned again on every ingest (`retryHeld`); removed once
 * it is stored in `ledger_events` (applied or quarantined).
 */
@Entity(
    tableName = "ledger_inbox",
    primaryKeys = ["eventId", "hash"],
    indices = [Index(value = ["authorDeviceId", "lamport"])],
)
data class LedgerInboxEntity(
    val eventId: EventId,
    val hash: String,
    val payloadJson: String,
    val authorDeviceId: DeviceId,
    val lamport: Long,
    val receivedAtEpochMs: Long,
)

/**
 * A pulled document refused only because its lamport is too far ahead of this device's clock
 * (`LAMPORT_TOO_FAR_AHEAD`, M3 §3). The latest such copy per document is kept for the audit view; every other
 * rejection is an audit row only.
 *
 * @property envelopeJson the envelope as pulled (signed; never shown).
 * @property reason the `DocumentRejection` name.
 */
@Entity(tableName = "document_inbox", primaryKeys = ["docType", "docId"])
data class DocumentInboxEntity(
    val docType: String,
    val docId: String,
    val envelopeJson: String,
    val reason: String,
    val receivedAtEpochMs: Long,
)

/** One collection's pull position (`SyncCursor.perCollection`), opaque to this layer. */
@Entity(tableName = "sync_cursor")
data class SyncCursorEntity(@PrimaryKey val collection: String, val cursor: String)

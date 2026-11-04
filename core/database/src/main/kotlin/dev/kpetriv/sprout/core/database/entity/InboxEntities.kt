package dev.kpetriv.sprout.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId

/**
 * A pulled event that cannot be decided yet (M3 §2, D-M3-31): its author device is not in the registry mirror yet or
 * is still PENDING, it does not link to its author's stored chain yet (a gap before it), its account is not known yet,
 * or an earlier event of its author in the same ingest was quarantined. Kept as its raw JSON tree, verified and
 * planned again on every ingest (`retryHeld`); removed once it is stored in `ledger_events` (applied or quarantined).
 * At most `RoomLedgerRepository.MAX_HELD_PER_AUTHOR` rows per author device are kept (the oldest is dropped, audited).
 *
 * @property reason why it is held: a `QuarantineReason` name for a verifier hold (`UNKNOWN_DEVICE`,
 *   `DEVICE_NOT_ACTIVE`), otherwise one of `RoomLedgerRepository`'s `HELD_*` codes. Null for rows held by schema
 *   version 3 (they get a reason on the next retry).
 * @property serverReceivedAtEpochMs the backend's `serverReceivedAt` stamp the event was pulled with (schema version
 *   7), moved to its `ledger_events` row when it is stored. Null for rows held before version 7.
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
    val reason: String? = null,
    val serverReceivedAtEpochMs: Long? = null,
)

/**
 * A pulled document that may still apply later (M3 §3, D-M3-31): held because its author device is unknown to the
 * registry mirror or still PENDING (`VerifiedDocument.Held`), or refused because its lamport is too far ahead of this
 * device's clock (`LAMPORT_TOO_FAR_AHEAD`). One row per document (the newest copy by lamport wins); verified again by
 * `RoomRemoteApplier.retryHeld` and removed once it applies, is refused for good, or loses to the stored copy. Every
 * other rejection is an audit row only. At most `RoomRemoteApplier.MAX_HELD_PER_AUTHOR` rows per author device.
 *
 * @property envelopeJson the envelope as pulled (signed; never shown).
 * @property reason the `DocumentRejection` name.
 * @property authorDeviceId the envelope's author device (schema version 4; null for rows kept by version 3).
 */
@Entity(
    tableName = "document_inbox",
    primaryKeys = ["docType", "docId"],
    indices = [Index(value = ["authorDeviceId"])],
)
data class DocumentInboxEntity(
    val docType: String,
    val docId: String,
    val envelopeJson: String,
    val reason: String,
    val receivedAtEpochMs: Long,
    val authorDeviceId: DeviceId? = null,
)

/**
 * One collection's pull position (`SyncCursor.perCollection`) in one household, opaque to this layer. Keyed by the
 * household (schema version 5) so a device that joins another household never resumes the previous one's positions.
 */
@Entity(tableName = "sync_cursor", primaryKeys = ["householdId", "collection"])
data class SyncCursorEntity(val householdId: HouseholdId, val collection: String, val cursor: String)

package dev.kpetriv.sprout.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.UserId

/** Audited actions. Stored by name, so entries keep their meaning if the enum grows. */
enum class AuditAction {
    LEDGER_EVENT_APPENDED,

    /** A local event was stored next to a different event with the same eventId (a conflicting duplicate). */
    LEDGER_EVENT_CONFLICT,
    KID_UPSERTED,
    KID_ARCHIVED,
    ACCOUNT_CREATED,
    CHORE_UPSERTED,
    CHORE_STATE_CHANGED,
    SUBMISSION_SUBMITTED,
    SUBMISSION_DECIDED,
    REWARD_UPSERTED,
    ALLOWANCE_UPSERTED,
    SAVINGS_GOAL_UPSERTED,
    SAVINGS_GOAL_DELETED,
    INTEREST_POLICY_UPDATED,
    HOUSEHOLD_POLICY_UPDATED,
    PARENT_KEY_REGISTERED,
    PARENT_ACTION_QUEUED,
    PARENT_ACTION_DONE,
    PARENT_ACTION_REFUSED,
    PARENT_ACTION_FAILED,
    PARENT_ACTION_CANCELLED,
    PARENT_ACTION_SKIPPED,
    PHOTO_SAVED,
    PHOTO_DELETED,

    /** A pulled event passed verification and was stored (M3). */
    LEDGER_EVENT_INGESTED,

    /** A pulled event was refused and stored quarantined, or only audited when it was too broken to store. */
    LEDGER_EVENT_QUARANTINED,

    /** A pulled document was verified and replaced the local copy. */
    DOCUMENT_APPLIED,

    /** A pulled document was refused; the reason code only. */
    DOCUMENT_REJECTED,
    DEVICE_REGISTERED,
    DEVICE_REVOKED,

    /**
     * The backend refused an outbox item for good (`SyncError.Permanent`): its push id, `seq`, code and whether it
     * blocks the outbox (a ledger event, D-M3-33). One row per `(seq, code)`.
     */
    SYNC_REJECTED,

    /**
     * A held pulled event or document was dropped because its author device had more than the cap of held rows
     * (`RoomLedgerRepository.MAX_HELD_PER_AUTHOR`); ids and the hold reason only.
     */
    HELD_DROPPED,

    /** The local trial household was deleted when this device joined a real household; counts only. */
    LOCAL_HOUSEHOLD_PURGED,
    SESSION_STARTED,
    SESSION_CLEARED,

    /** This device queued a chat message (M4): message id, thread, seq, kind; never content. */
    MESSAGE_SENT,

    /** A pulled chat message was refused for good: ids and the reason only. */
    MESSAGE_QUARANTINED,

    /**
     * Held messages of an author waited longer than the gap timeout for an earlier `seq` that never came (for example
     * one that was quarantined), so the gap was skipped: author, thread and the skipped seq range.
     */
    MESSAGE_GAP_SKIPPED,

    /** This device published a thread key document (rotation or re-wrap): thread, epoch, recipients count. */
    THREAD_KEY_PUBLISHED,

    /** A verified thread key document wraps for this device but its wrap does not open: thread, epoch, creator. */
    THREAD_KEY_UNWRAP_FAILED,

    /** This device queued an encrypted blob for upload: blob id, chunk count, thread or submission. */
    BLOB_QUEUED,
}

/**
 * The append-only, parent-visible audit trail: who did what, when, from which device (PLAN "Availability and
 * operations"). The DAO has no update; the only delete is the local-household purge (M3 §6).
 *
 * @property target the id acted on (an eventId, kidId, accountId, document id, action key or photo file name);
 *   never a name.
 * @property detailsJson ids, types and amounts only. Never personal data or free text (no kid names, no
 *   adjustment reasons).
 */
@Entity(tableName = "audit_log", indices = [Index(value = ["atEpochMs"])])
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atEpochMs: Long,
    val actorUid: UserId,
    val deviceId: DeviceId,
    val action: AuditAction,
    val target: String,
    val detailsJson: String,
)

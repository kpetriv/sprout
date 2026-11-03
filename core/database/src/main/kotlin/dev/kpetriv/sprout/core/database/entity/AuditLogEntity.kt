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
}

/**
 * The append-only, parent-visible audit trail: who did what, when, from which device (PLAN "Availability and
 * operations"). The DAO has no update or delete.
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

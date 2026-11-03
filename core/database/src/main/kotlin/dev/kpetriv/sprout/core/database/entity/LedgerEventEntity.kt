package dev.kpetriv.sprout.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId

/** Where a stored event came from. */
enum class EventOrigin {
    /** Authored on this device. */
    LOCAL,

    /** Pulled from another device through sync (M3). */
    REMOTE,
}

/**
 * One immutable ledger event (ADR-0002). [payloadJson] is the full event in `LedgerJson` form, including hash
 * and signature, and is the source of truth; the other columns are copies of envelope fields for querying.
 *
 * The key is `(eventId, hash)`: a different event under a stored eventId (a conflicting duplicate, ADR-0004
 * section 6) is kept next to the first copy so projections can report it and apply at most one copy.
 *
 * Rows are never updated except to quarantine: a row whose payload no longer decodes, whose hash does not match
 * its stored document, or (origin LOCAL) whose signature does not verify against this device's key is marked
 * [quarantined] with a [quarantineReason] by the repository's maintenance step, excluded from every projection,
 * read and head-hash query, and shown in the parent audit view. Pulled events failing verification will be stored
 * the same way by sync (M3).
 *
 * @property type the event's serial name (for example `chore_rewarded`).
 * @property receivedAtEpochMs when this device stored the row (local wall clock; display and diagnostics only).
 */
@Entity(
    tableName = "ledger_events",
    primaryKeys = ["eventId", "hash"],
    indices = [
        Index(value = ["kidId", "lamport"]),
        Index(value = ["authorDeviceId", "lamport"]),
        Index(value = ["hash"]),
    ],
)
data class LedgerEventEntity(
    val eventId: EventId,
    val householdId: HouseholdId,
    val kidId: KidId,
    val accountId: AccountId,
    val authorUid: UserId,
    val authorDeviceId: DeviceId,
    val authorRole: Role,
    val lamport: Long,
    val createdAtEpochMs: Long,
    val type: String,
    val currency: Currency,
    val schemaVersion: Int,
    val prevHash: String?,
    val hash: String,
    val signature: String?,
    val payloadJson: String,
    val receivedAtEpochMs: Long,
    val origin: EventOrigin,
    @ColumnInfo(defaultValue = "0") val quarantined: Boolean = false,
    val quarantineReason: String? = null,
)

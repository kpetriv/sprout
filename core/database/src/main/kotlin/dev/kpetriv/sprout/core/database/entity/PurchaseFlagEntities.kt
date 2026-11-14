package dev.kpetriv.sprout.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind
import dev.kpetriv.sprout.core.model.UserId

/**
 * One version of a reward this device has seen (D-REL-65): written here, or pulled and verified, **whether or not it
 * won last-writer-wins** against the stored `rewards` row (an older version pulled late is still a price a kid may
 * have seen). Insert-or-ignore on `(rewardId, lamport, authorDeviceId)`; never updated. Pulled kid purchases are
 * compared with these rows on PARENT devices, and a row only counts while it re-verifies the way the catalogue's
 * current row does (`RewardTableCatalogue`), so a row edited after it was recorded fails closed.
 *
 * The reward columns are the version's payload, stored as `rewards` stores them so the signed envelope can be rebuilt
 * from the row. They are null only on a tombstone whose payload did not decode: such a row is a marker (the reward id
 * has history here) and never matches a purchase.
 *
 * @property recordedAtEpochMs when this device recorded the version (its own clock); the earliest one of a reward is
 *   where this device's history of that reward starts.
 */
@Entity(tableName = "reward_versions", primaryKeys = ["rewardId", "lamport", "authorDeviceId"])
data class RewardVersionEntity(
    val rewardId: RewardId,
    val lamport: Long,
    val authorDeviceId: DeviceId,
    val authorUid: UserId?,
    val signature: String?,
    val deleted: Boolean,
    val householdId: HouseholdId?,
    val title: String?,
    val kind: RewardKind?,
    val cost: Money?,
    val currency: Currency?,
    val minutesGranted: Int?,
    val active: Boolean?,
    val forKidsJson: String?,
    val recordedAtEpochMs: Long,
) {
    /**
     * The version as a `rewards` row (lamport and stamp included), for the catalogue's checks; null for a tombstone
     * or a marker, which never match.
     */
    @Suppress("ReturnCount") // Any missing payload column makes it a marker.
    fun toRewardRow(): RewardEntity? {
        if (deleted) return null
        return RewardEntity(
            rewardId = rewardId,
            householdId = householdId ?: return null,
            title = title ?: return null,
            kind = kind ?: return null,
            cost = cost ?: return null,
            currency = currency ?: return null,
            minutesGranted = minutesGranted,
            active = active ?: return null,
            forKidsJson = forKidsJson ?: return null,
            lamport = lamport,
            stamp = DocumentStamp(authorUid, authorDeviceId, signature, deleted = false),
        )
    }

    override fun toString(): String =
        "RewardVersionEntity(rewardId=$rewardId, lamport=$lamport, authorDeviceId=$authorDeviceId, " +
            "deleted=$deleted, title=<redacted>)"

    companion object {
        /**
         * [row] (its lamport and stamp) as a version recorded at [recordedAtEpochMs]; null when the row has no
         * author device (only rows from before schema version 3, which never pass the catalogue).
         */
        fun of(row: RewardEntity, recordedAtEpochMs: Long): RewardVersionEntity? = RewardVersionEntity(
            rewardId = row.rewardId,
            lamport = row.lamport,
            authorDeviceId = row.stamp.authorDeviceId ?: return null,
            authorUid = row.stamp.authorUid,
            signature = row.stamp.signature,
            deleted = row.stamp.deleted,
            householdId = row.householdId,
            title = row.title,
            kind = row.kind,
            cost = row.cost,
            currency = row.currency,
            minutesGranted = row.minutesGranted,
            active = row.active,
            forKidsJson = row.forKidsJson,
            recordedAtEpochMs = recordedAtEpochMs,
        )

        /** A tombstone of [rewardId] whose payload did not decode: kept as a marker only. */
        fun marker(rewardId: RewardId, lamport: Long, stamp: DocumentStamp, recordedAtEpochMs: Long) =
            RewardVersionEntity(
                rewardId = rewardId,
                lamport = lamport,
                authorDeviceId = requireNotNull(stamp.authorDeviceId) { "a pulled version names its author" },
                authorUid = stamp.authorUid,
                signature = stamp.signature,
                deleted = true,
                householdId = null,
                title = null,
                kind = null,
                cost = null,
                currency = null,
                minutesGranted = null,
                active = null,
                forKidsJson = null,
                recordedAtEpochMs = recordedAtEpochMs,
            )
    }
}

/**
 * When this device's reward history starts (D-REL-65); a single row with [id] [SINGLETON_ID], on this device's wall
 * clock. Set by the 9 → 10 migration for upgraded installs, otherwise when the first reward version is recorded or the
 * first pulled purchase is reviewed, whichever comes first. Only purchases the server stamped at or after it are
 * reviewed, for every reward, so a fresh install or a restore never flags the household's past.
 */
@Entity(tableName = "reward_history")
data class RewardHistoryEntity(@PrimaryKey val id: Int = SINGLETON_ID, val startedAtEpochMs: Long) {
    companion object {
        const val SINGLETON_ID: Int = 0
    }
}

/** Why a pulled kid purchase was flagged (D-REL-65). */
enum class PurchaseFlagReason {
    /** This device has recorded no version of the purchase's reward id. */
    NO_SUCH_REWARD,

    /**
     * No recorded version of the reward with a lower lamport than the purchase is parent-authored, live, available to
     * the kid and sold at the purchase's amount, currency and minutes.
     */
    NO_MATCHING_VERSION,
}

/**
 * A pulled kid-authored purchase this PARENT device could not match with any reward version it has seen (D-REL-65),
 * for a parent to check. A flag lives outside the ledger: the purchase is stored and projected like any other, so
 * balances converge on every device whatever its flags. Key `(eventId, hash)`, the stored event's.
 *
 * @property rewardId the purchase's reward id as it claims it (not necessarily a known reward).
 * @property purchaseLamport the purchase's lamport: only a version below it can sell it, so only such a version, when
 *   it is newly recorded, re-checks the flag.
 * @property dismissedAtEpochMs when a parent dismissed the flag; null while it is open.
 */
@Entity(
    tableName = "purchase_flags",
    primaryKeys = ["eventId", "hash"],
    indices = [Index(value = ["rewardId", "purchaseLamport"]), Index(value = ["flaggedAtEpochMs"])],
)
data class PurchaseFlagEntity(
    val eventId: EventId,
    val hash: String,
    val kidId: KidId,
    val rewardId: String,
    val purchaseLamport: Long,
    val reason: PurchaseFlagReason,
    val amount: Money,
    val currency: Currency,
    val minutesGranted: Money?,
    val flaggedAtEpochMs: Long,
    val dismissedAtEpochMs: Long? = null,
)

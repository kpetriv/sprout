package dev.kpetriv.sprout.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.kpetriv.sprout.core.database.entity.LedgerEventEntity
import dev.kpetriv.sprout.core.database.entity.PurchaseFlagEntity
import dev.kpetriv.sprout.core.database.entity.RewardHistoryEntity
import dev.kpetriv.sprout.core.database.entity.RewardVersionEntity
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.model.RewardId
import kotlinx.coroutines.flow.Flow

/** The reward versions this device has seen and where its reward history starts (D-REL-65). */
@Dao
interface RewardVersionDao {
    /** Returns the row id, or -1 when the version is already recorded (the first copy is kept). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(version: RewardVersionEntity): Long

    /** The versions of [rewardId] with a lamport below [lamport], tombstones and markers included. */
    @Query("SELECT * FROM reward_versions WHERE rewardId = :rewardId AND lamport < :lamport ORDER BY lamport")
    suspend fun before(rewardId: RewardId, lamport: Long): List<RewardVersionEntity>

    @Query("SELECT COUNT(*) FROM reward_versions WHERE rewardId = :rewardId")
    suspend fun countFor(rewardId: RewardId): Int

    @Query("SELECT * FROM reward_versions ORDER BY rewardId, lamport, authorDeviceId")
    suspend fun all(): List<RewardVersionEntity>

    @Query("SELECT * FROM reward_history WHERE id = ${RewardHistoryEntity.SINGLETON_ID}")
    suspend fun historyStart(): RewardHistoryEntity?

    /** Sets the device-wide history start unless it is already set. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun startHistoryIfAbsent(start: RewardHistoryEntity): Long
}

/** A flag joined with its reward's current row (null columns when the reward is unknown or a tombstone). */
data class PurchaseFlagRow(
    @Embedded val flag: PurchaseFlagEntity,
    val rewardTitle: String?,
    val rewardCost: Long?,
    val rewardCurrency: String?,
    val rewardMinutes: Int?,
)

/** Flagged pulled purchases (D-REL-65). */
@Dao
interface PurchaseFlagDao {
    /** Returns the row id, or -1 when the purchase is already flagged (open or dismissed). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(flag: PurchaseFlagEntity): Long

    /**
     * The open flags whose purchase is stored and not quarantined (a purchase later quarantined, for example on a
     * forked chain, is listed as a rejected event instead), newest first, each with its reward's current title, cost
     * and minutes when the reward still exists.
     */
    @Query(
        """
        SELECT f.*, r.title AS rewardTitle, r.cost AS rewardCost, r.currency AS rewardCurrency,
            r.minutesGranted AS rewardMinutes
        FROM purchase_flags f
        JOIN ledger_events e ON e.eventId = f.eventId AND e.hash = f.hash AND e.quarantined = 0
        LEFT JOIN rewards r ON r.rewardId = f.rewardId AND r.deleted = 0
        WHERE f.dismissedAtEpochMs IS NULL
        ORDER BY f.flaggedAtEpochMs DESC, f.eventId, f.hash
        """,
    )
    fun observeOpen(): Flow<List<PurchaseFlagRow>>

    /** The open flags of [rewardId] whose purchase's lamport is above [lamport]: those a version at it may sell. */
    @Query(
        "SELECT * FROM purchase_flags WHERE rewardId = :rewardId AND purchaseLamport > :lamport " +
            "AND dismissedAtEpochMs IS NULL",
    )
    suspend fun openAfter(rewardId: String, lamport: Long): List<PurchaseFlagEntity>

    @Query("SELECT * FROM purchase_flags ORDER BY flaggedAtEpochMs, eventId, hash")
    suspend fun all(): List<PurchaseFlagEntity>

    /** The stored, not quarantined purchase a flag names; null once it is gone or quarantined. */
    @Query("SELECT * FROM ledger_events WHERE eventId = :eventId AND hash = :hash AND quarantined = 0")
    suspend fun flaggedEvent(eventId: EventId, hash: String): LedgerEventEntity?

    @Query("DELETE FROM purchase_flags WHERE eventId = :eventId AND hash = :hash")
    suspend fun delete(eventId: EventId, hash: String): Int

    /** Dismisses an open flag; returns 0 when there is none. */
    @Query(
        "UPDATE purchase_flags SET dismissedAtEpochMs = :at " +
            "WHERE eventId = :eventId AND hash = :hash AND dismissedAtEpochMs IS NULL",
    )
    suspend fun dismiss(eventId: EventId, hash: String, at: Long): Int
}

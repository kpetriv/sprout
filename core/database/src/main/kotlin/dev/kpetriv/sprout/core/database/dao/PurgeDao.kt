package dev.kpetriv.sprout.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.UserId

/**
 * Bulk deletes for `PurgeLocalHouseholdUseCase` only (M3 §6): the trial household's rows go when the device joins a
 * real household. Nothing else deletes from these tables (the ledger and the audit log are otherwise append-only).
 */
@Dao
@Suppress("TooManyFunctions") // One delete per purged table.
interface PurgeDao {
    @Query("SELECT kidId FROM kids WHERE householdId = :householdId")
    suspend fun kidsOf(householdId: HouseholdId): List<KidId>

    @Query("DELETE FROM kids WHERE householdId = :householdId")
    suspend fun deleteKids(householdId: HouseholdId): Int

    @Query("DELETE FROM accounts WHERE kidId IN (:kids)")
    suspend fun deleteAccounts(kids: List<KidId>): Int

    @Query("DELETE FROM ledger_events WHERE householdId = :householdId")
    suspend fun deleteEvents(householdId: HouseholdId): Int

    @Query("DELETE FROM balances WHERE kidId IN (:kids)")
    suspend fun deleteBalances(kids: List<KidId>): Int

    @Query("DELETE FROM anomalies WHERE kidId IN (:kids)")
    suspend fun deleteAnomalies(kids: List<KidId>): Int

    @Query("DELETE FROM chores WHERE householdId = :householdId OR kidId IN (:kids)")
    suspend fun deleteChores(householdId: HouseholdId, kids: List<KidId>): Int

    @Query("DELETE FROM chore_submissions WHERE kidId IN (:kids)")
    suspend fun deleteSubmissions(kids: List<KidId>): Int

    @Query("DELETE FROM rewards WHERE householdId = :householdId")
    suspend fun deleteRewards(householdId: HouseholdId): Int

    @Query("DELETE FROM allowance_schedules WHERE kidId IN (:kids)")
    suspend fun deleteAllowances(kids: List<KidId>): Int

    @Query("DELETE FROM savings_goals WHERE kidId IN (:kids)")
    suspend fun deleteGoals(kids: List<KidId>): Int

    @Query("DELETE FROM interest_policy WHERE householdId = :householdId")
    suspend fun deleteInterestPolicy(householdId: HouseholdId): Int

    @Query("DELETE FROM pending_parent_actions WHERE kidId IN (:kids)")
    suspend fun deletePendingActions(kids: List<KidId>): Int

    @Query("SELECT fileName FROM photos WHERE kidId IN (:kids)")
    suspend fun photosOf(kids: List<KidId>): List<String>

    @Query("DELETE FROM photos WHERE kidId IN (:kids)")
    suspend fun deletePhotos(kids: List<KidId>): Int

    @Query("DELETE FROM household_policy")
    suspend fun deleteHouseholdPolicy(): Int

    @Query("DELETE FROM outbox WHERE seq IN (:seqs)")
    suspend fun deleteOutbox(seqs: List<Long>): Int

    @Query("DELETE FROM audit_log WHERE actorUid IN (:actors)")
    suspend fun deleteAudit(actors: List<UserId>): Int

    @Query("DELETE FROM session WHERE householdId = :householdId")
    suspend fun deleteSession(householdId: HouseholdId): Int
}

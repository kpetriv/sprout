package dev.kpetriv.sprout.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.kpetriv.sprout.core.database.entity.AllowanceScheduleEntity
import dev.kpetriv.sprout.core.database.entity.ChoreEntity
import dev.kpetriv.sprout.core.database.entity.ChoreSubmissionEntity
import dev.kpetriv.sprout.core.database.entity.HouseholdPolicyEntity
import dev.kpetriv.sprout.core.database.entity.InterestPolicyEntity
import dev.kpetriv.sprout.core.database.entity.PendingActionStatus
import dev.kpetriv.sprout.core.database.entity.PendingParentActionEntity
import dev.kpetriv.sprout.core.database.entity.PhotoEntity
import dev.kpetriv.sprout.core.database.entity.RewardEntity
import dev.kpetriv.sprout.core.database.entity.SavingsGoalEntity
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.GoalId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.ScheduleId
import dev.kpetriv.sprout.core.model.SubmissionId
import dev.kpetriv.sprout.core.model.SubmissionStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ChoreDao {
    @Upsert
    suspend fun upsert(chore: ChoreEntity)

    /** The row, tombstones included (sync needs them); repositories hide tombstones. */
    @Query("SELECT * FROM chores WHERE choreId = :choreId")
    suspend fun get(choreId: ChoreId): ChoreEntity?

    /** Chores of every kid (null [kidId]) or one kid; archived ones only with [includeArchived]. No tombstones. */
    @Query(
        """
        SELECT * FROM chores WHERE deleted = 0 AND (:kidId IS NULL OR kidId = :kidId)
        AND (:includeArchived OR state != 'ARCHIVED')
        ORDER BY createdAtEpochMs, choreId
        """,
    )
    fun observe(kidId: KidId?, includeArchived: Boolean): Flow<List<ChoreEntity>>
}

@Dao
interface ChoreSubmissionDao {
    @Upsert
    suspend fun upsert(submission: ChoreSubmissionEntity)

    /** The row, tombstones included; repositories hide tombstones. */
    @Query("SELECT * FROM chore_submissions WHERE submissionId = :submissionId")
    suspend fun get(submissionId: SubmissionId): ChoreSubmissionEntity?

    @Query(
        """
        SELECT * FROM chore_submissions WHERE kidId = :kidId AND deleted = 0
        ORDER BY dueOn DESC, submittedAtEpochMs DESC
        """,
    )
    fun observeForKid(kidId: KidId): Flow<List<ChoreSubmissionEntity>>

    /** Submissions with [status] (of every kid), oldest first: the parent's approvals inbox for SUBMITTED. */
    @Query(
        """
        SELECT * FROM chore_submissions WHERE status = :status AND deleted = 0
        ORDER BY submittedAtEpochMs, submissionId
        """,
    )
    fun observeWithStatus(status: SubmissionStatus): Flow<List<ChoreSubmissionEntity>>

    /** The submission of [choreId] due on [dueOn], tombstones included, whatever its id. */
    @Query("SELECT * FROM chore_submissions WHERE choreId = :choreId AND dueOn = :dueOn")
    suspend fun forOccurrence(choreId: ChoreId, dueOn: CalendarDate): ChoreSubmissionEntity?

    /** Every submission of [choreId]. */
    @Query("SELECT * FROM chore_submissions WHERE choreId = :choreId")
    suspend fun forChore(choreId: ChoreId): List<ChoreSubmissionEntity>
}

@Dao
interface RewardDao {
    @Upsert
    suspend fun upsert(reward: RewardEntity)

    /** The row, tombstones included; repositories and the catalogue hide tombstones. */
    @Query("SELECT * FROM rewards WHERE rewardId = :rewardId")
    suspend fun get(rewardId: RewardId): RewardEntity?

    @Query("SELECT * FROM rewards WHERE deleted = 0 ORDER BY cost, rewardId")
    fun observeAll(): Flow<List<RewardEntity>>
}

@Dao
interface AllowanceScheduleDao {
    @Upsert
    suspend fun upsert(schedule: AllowanceScheduleEntity)

    /** The row, tombstones included; repositories hide tombstones. */
    @Query("SELECT * FROM allowance_schedules WHERE scheduleId = :scheduleId")
    suspend fun get(scheduleId: ScheduleId): AllowanceScheduleEntity?

    @Query(
        """
        SELECT * FROM allowance_schedules WHERE deleted = 0 AND (:kidId IS NULL OR kidId = :kidId)
        ORDER BY startsOn, scheduleId
        """,
    )
    fun observe(kidId: KidId?): Flow<List<AllowanceScheduleEntity>>

    @Query("SELECT * FROM allowance_schedules WHERE kidId = :kidId AND deleted = 0 ORDER BY startsOn, scheduleId")
    suspend fun forKid(kidId: KidId): List<AllowanceScheduleEntity>

    @Query("UPDATE allowance_schedules SET lastPaidOn = :lastPaidOn WHERE scheduleId = :scheduleId")
    suspend fun setLastPaidOn(scheduleId: ScheduleId, lastPaidOn: CalendarDate): Int
}

@Dao
interface SavingsGoalDao {
    @Upsert
    suspend fun upsert(goal: SavingsGoalEntity)

    /** The row, tombstones included; repositories hide tombstones. */
    @Query("SELECT * FROM savings_goals WHERE goalId = :goalId")
    suspend fun get(goalId: GoalId): SavingsGoalEntity?

    @Query("SELECT * FROM savings_goals WHERE kidId = :kidId AND deleted = 0 ORDER BY createdOn, goalId")
    fun observeForKid(kidId: KidId): Flow<List<SavingsGoalEntity>>
}

@Dao
interface InterestPolicyDao {
    @Upsert
    suspend fun upsert(policy: InterestPolicyEntity)

    /** The row, tombstones included; the repository hides tombstones. */
    @Query("SELECT * FROM interest_policy WHERE householdId = :householdId")
    suspend fun get(householdId: HouseholdId): InterestPolicyEntity?

    @Query("SELECT * FROM interest_policy WHERE householdId = :householdId AND deleted = 0")
    fun observe(householdId: HouseholdId): Flow<InterestPolicyEntity?>
}

@Dao
interface PendingParentActionDao {
    /** Returns the row id, or -1 when an action with the same key is already stored (in any status). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(action: PendingParentActionEntity): Long

    /** Removes [key]'s row if it was cancelled, so the action can be queued again. */
    @Query("DELETE FROM pending_parent_actions WHERE `key` = :key AND status = 'CANCELLED'")
    suspend fun deleteCancelled(key: String): Int

    @Query("SELECT * FROM pending_parent_actions WHERE status = :status ORDER BY id")
    fun observeWithStatus(status: PendingActionStatus): Flow<List<PendingParentActionEntity>>

    @Query("SELECT * FROM pending_parent_actions WHERE status = 'PENDING' ORDER BY id")
    suspend fun pending(): List<PendingParentActionEntity>

    @Query("SELECT * FROM pending_parent_actions WHERE `key` = :key")
    suspend fun get(key: String): PendingParentActionEntity?

    @Query(
        """
        UPDATE pending_parent_actions SET status = 'DONE', doneAtEpochMs = :doneAtEpochMs, eventId = :eventId
        WHERE `key` = :key AND status = 'PENDING'
        """,
    )
    suspend fun markDone(key: String, eventId: EventId, doneAtEpochMs: Long): Int

    /** Moves a pending action to [status] (FAILED or CANCELLED) with [reason]. */
    @Query(
        """
        UPDATE pending_parent_actions SET status = :status, failureReason = :reason, doneAtEpochMs = :atEpochMs
        WHERE `key` = :key AND status = 'PENDING'
        """,
    )
    suspend fun close(key: String, status: PendingActionStatus, reason: String?, atEpochMs: Long): Int

    /** Withdraws a FAILED or SKIPPED action the parent dismissed from the dashboard (it becomes CANCELLED). */
    @Query(
        """
        UPDATE pending_parent_actions SET status = 'CANCELLED', doneAtEpochMs = :atEpochMs
        WHERE `key` = :key AND status IN ('FAILED', 'SKIPPED')
        """,
    )
    suspend fun dismiss(key: String, atEpochMs: Long): Int

    @Query(
        """
        UPDATE pending_parent_actions SET refusals = refusals + 1, failureReason = :reason
        WHERE `key` = :key AND status = 'PENDING'
        """,
    )
    suspend fun addRefusal(key: String, reason: String): Int
}

@Dao
interface PhotoDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(photo: PhotoEntity)

    @Query("SELECT * FROM photos WHERE fileName = :fileName")
    suspend fun get(fileName: String): PhotoEntity?

    @Query("UPDATE photos SET submissionId = :submissionId WHERE fileName = :fileName")
    suspend fun attach(fileName: String, submissionId: SubmissionId): Int

    @Query("DELETE FROM photos WHERE fileName = :fileName")
    suspend fun delete(fileName: String): Int

    /** Every photo stored for [kidId], attached or not. */
    @Query("SELECT * FROM photos WHERE kidId = :kidId")
    suspend fun forKid(kidId: KidId): List<PhotoEntity>

    /** The photos attached to [submissionId]. */
    @Query("SELECT * FROM photos WHERE submissionId = :submissionId")
    suspend fun forSubmission(submissionId: SubmissionId): List<PhotoEntity>
}

@Dao
interface HouseholdPolicyDao {
    @Upsert
    suspend fun upsert(policy: HouseholdPolicyEntity)

    @Query("SELECT * FROM household_policy WHERE id = ${HouseholdPolicyEntity.SINGLETON_ID}")
    suspend fun get(): HouseholdPolicyEntity?

    @Query("SELECT * FROM household_policy WHERE id = ${HouseholdPolicyEntity.SINGLETON_ID}")
    fun observe(): Flow<HouseholdPolicyEntity?>
}

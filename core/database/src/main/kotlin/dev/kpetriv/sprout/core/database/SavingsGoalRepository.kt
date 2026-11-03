package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.SavingsGoalEntity
import dev.kpetriv.sprout.core.domainhousehold.SavingsGoal
import dev.kpetriv.sprout.core.domainledger.EventAuthor
import dev.kpetriv.sprout.core.model.GoalId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.put
import javax.inject.Inject

/** Kids' savings goals. Progress is `SavingsProgress.of(goal, savingsBalance)` over the ledger's SAVINGS balance. */
interface SavingsGoalRepository {
    /** [kidId]'s goals, oldest first. */
    fun observeGoals(kidId: KidId): Flow<List<SavingsGoal>>

    suspend fun get(id: GoalId): SavingsGoal?

    /** Inserts or replaces [goal]. */
    suspend fun upsert(goal: SavingsGoal)

    /** Removes the goal (the savings stay in the account). False if there is no such goal. */
    suspend fun delete(id: GoalId): Boolean
}

/**
 * [SavingsGoalRepository] on Room: each write, its outbox `DOCUMENT` row and its audit row together. A delete keeps
 * the row as a tombstone (hidden from reads) and queues a tombstone document (envelope `deleted = true`, payload the
 * goal's last state), which no later update can resurrect (D-M3-13).
 */
class RoomSavingsGoalRepository @Inject constructor(
    database: SproutDatabase,
    private val authors: SessionAuthorProvider,
    clock: Clock,
    signer: DeviceSigner,
) : SavingsGoalRepository {
    private val goals = database.savingsGoalDao()
    private val writes = DocumentWrites(database, clock, signer)

    override fun observeGoals(kidId: KidId): Flow<List<SavingsGoal>> =
        goals.observeForKid(kidId).map { rows -> rows.map(SavingsGoalEntity::toDomain) }

    override suspend fun get(id: GoalId): SavingsGoal? = goals.get(id)?.takeUnless { it.stamp.deleted }?.toDomain()

    override suspend fun upsert(goal: SavingsGoal) {
        val author = authors.currentAuthor()
        writes.transaction {
            check(goals.get(goal.id)?.stamp?.deleted != true) { "a deleted goal cannot come back" }
            write(author, goal, deleted = false, AuditAction.SAVINGS_GOAL_UPSERTED)
        }
    }

    override suspend fun delete(id: GoalId): Boolean {
        val author = authors.currentAuthor()
        return writes.transaction {
            val goal = get(id) ?: return@transaction false
            write(author, goal, deleted = true, AuditAction.SAVINGS_GOAL_DELETED)
            true
        }
    }

    private suspend fun write(author: EventAuthor, goal: SavingsGoal, deleted: Boolean, action: AuditAction) {
        val lamport = writes.nextLamport(author.deviceId)
        val row = SavingsGoalEntity.from(goal, lamport)
        val stamp = writes.record(
            author,
            DocTypes.SAVINGS_GOAL,
            goal.id.value,
            lamport,
            DocumentPayloads.encode(SavingsGoal.serializer(), row.toDomain()),
            action,
            deleted = deleted,
        ) {
            put("kidId", goal.kidId.value)
            if (!deleted) {
                put("targetMinor", goal.target.minorUnits)
                put("achieved", goal.achievedOn != null)
            }
        }
        goals.upsert(row.copy(stamp = stamp))
    }
}

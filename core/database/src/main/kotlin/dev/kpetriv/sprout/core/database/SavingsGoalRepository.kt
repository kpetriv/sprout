package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.DocumentJson
import dev.kpetriv.sprout.core.database.entity.SavingsGoalEntity
import dev.kpetriv.sprout.core.domainhousehold.SavingsGoal
import dev.kpetriv.sprout.core.model.GoalId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.buildJsonObject
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
 * [SavingsGoalRepository] on Room: each write, its outbox `DOCUMENT` row and its audit row together. A delete
 * queues a tombstone document (`{"goalId": ..., "deleted": true}`).
 */
class RoomSavingsGoalRepository @Inject constructor(
    private val database: SproutDatabase,
    private val authors: SessionAuthorProvider,
    private val clock: Clock,
) : SavingsGoalRepository {
    private val goals = database.savingsGoalDao()
    private val writes = DocumentWrites(database, clock)

    override fun observeGoals(kidId: KidId): Flow<List<SavingsGoal>> =
        goals.observeForKid(kidId).map { rows -> rows.map(SavingsGoalEntity::toDomain) }

    override suspend fun get(id: GoalId): SavingsGoal? = goals.get(id)?.toDomain()

    override suspend fun upsert(goal: SavingsGoal) {
        val author = authors.currentAuthor()
        writes.transaction {
            val lamport = writes.nextLamport(author.deviceId)
            goals.upsert(SavingsGoalEntity.from(goal, lamport))
            writes.record(
                author,
                DocTypes.SAVINGS_GOAL,
                goal.id.value,
                lamport,
                DocumentJson.json.encodeToJsonElement(SavingsGoal.serializer(), goal),
                AuditAction.SAVINGS_GOAL_UPSERTED,
            ) {
                put("kidId", goal.kidId.value)
                put("targetMinor", goal.target.minorUnits)
                put("achieved", goal.achievedOn != null)
            }
        }
    }

    override suspend fun delete(id: GoalId): Boolean {
        val author = authors.currentAuthor()
        return writes.transaction {
            val goal = goals.get(id) ?: return@transaction false
            val lamport = writes.nextLamport(author.deviceId)
            goals.delete(id)
            val tombstone = buildJsonObject {
                put("goalId", id.value)
                put("kidId", goal.kidId.value)
                put("deleted", true)
            }
            writes.record(
                author,
                DocTypes.SAVINGS_GOAL,
                id.value,
                lamport,
                tombstone,
                AuditAction.SAVINGS_GOAL_DELETED,
            ) {
                put("kidId", goal.kidId.value)
            }
            true
        }
    }
}

package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.DocumentJson
import dev.kpetriv.sprout.core.database.entity.RewardEntity
import dev.kpetriv.sprout.core.domainhousehold.Reward
import dev.kpetriv.sprout.core.domainledger.EventAuthor
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.put
import javax.inject.Inject

/** The household's rewards catalogue. */
interface RewardRepository {
    /**
     * The catalogue, cheapest first: every reward (inactive ones only with [includeInactive]) when [kidId] is null,
     * otherwise what [kidId] can buy now ([Reward.isAvailableTo]).
     */
    fun observeRewards(kidId: KidId? = null, includeInactive: Boolean = false): Flow<List<Reward>>

    suspend fun get(id: RewardId): Reward?

    /** Inserts or replaces [reward]. */
    suspend fun upsert(reward: Reward)

    /** Shows or hides the reward in the shop. False if there is no such reward. */
    suspend fun setActive(id: RewardId, active: Boolean): Boolean
}

/** [RewardRepository] on Room: each write, its outbox `DOCUMENT` row and its audit row in one transaction. */
class RoomRewardRepository @Inject constructor(
    private val database: SproutDatabase,
    private val authors: SessionAuthorProvider,
    private val clock: Clock,
) : RewardRepository {
    private val rewards = database.rewardDao()
    private val writes = DocumentWrites(database, clock)

    override fun observeRewards(kidId: KidId?, includeInactive: Boolean): Flow<List<Reward>> =
        rewards.observeAll().map { rows ->
            rows.map(RewardEntity::toDomain).filter { reward ->
                if (kidId == null) includeInactive || reward.active else reward.isAvailableTo(kidId)
            }
        }

    override suspend fun get(id: RewardId): Reward? = rewards.get(id)?.toDomain()

    override suspend fun upsert(reward: Reward) {
        val author = authors.currentAuthor()
        writes.transaction {
            val lamport = writes.nextLamport(author.deviceId)
            rewards.upsert(RewardEntity.from(reward, lamport))
            record(author, reward, lamport)
        }
    }

    override suspend fun setActive(id: RewardId, active: Boolean): Boolean {
        val author = authors.currentAuthor()
        return writes.transaction {
            val reward = rewards.get(id)?.toDomain() ?: return@transaction false
            if (reward.active != active) {
                val lamport = writes.nextLamport(author.deviceId)
                rewards.setActive(id, active, lamport)
                record(author, reward.copy(active = active), lamport)
            }
            true
        }
    }

    private suspend fun record(author: EventAuthor, reward: Reward, lamport: Long) = writes.record(
        author,
        DocTypes.REWARD,
        reward.id.value,
        lamport,
        DocumentJson.json.encodeToJsonElement(Reward.serializer(), reward),
        AuditAction.REWARD_UPSERTED,
    ) {
        put("kind", reward.kind.name)
        put("costMinor", reward.cost.minorUnits)
        put("currency", Converters.currencyToString(reward.currency))
        put("active", reward.active)
    }
}

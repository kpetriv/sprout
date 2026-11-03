package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.RewardEntity
import dev.kpetriv.sprout.core.domainhousehold.Reward
import dev.kpetriv.sprout.core.domainledger.EventAuthor
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.sync.DocTypes
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
    database: SproutDatabase,
    private val authors: SessionAuthorProvider,
    clock: Clock,
    signer: DeviceSigner,
) : RewardRepository {
    private val rewards = database.rewardDao()
    private val writes = DocumentWrites(database, clock, signer)

    override fun observeRewards(kidId: KidId?, includeInactive: Boolean): Flow<List<Reward>> =
        rewards.observeAll().map { rows ->
            rows.map(RewardEntity::toDomain).filter { reward ->
                if (kidId == null) includeInactive || reward.active else reward.isAvailableTo(kidId)
            }
        }

    override suspend fun get(id: RewardId): Reward? = rewards.get(id)?.takeUnless { it.stamp.deleted }?.toDomain()

    override suspend fun upsert(reward: Reward) {
        val author = authors.currentAuthor()
        writes.transaction { write(author, reward) }
    }

    override suspend fun setActive(id: RewardId, active: Boolean): Boolean {
        val author = authors.currentAuthor()
        return writes.transaction {
            val reward = get(id) ?: return@transaction false
            if (reward.active != active) write(author, reward.copy(active = active))
            true
        }
    }

    private suspend fun write(author: EventAuthor, reward: Reward) {
        val lamport = writes.nextLamport(author.deviceId)
        val row = RewardEntity.from(reward, lamport)
        val stamp = writes.record(
            author,
            DocTypes.REWARD,
            reward.id.value,
            lamport,
            DocumentPayloads.encode(Reward.serializer(), row.toDomain()),
            AuditAction.REWARD_UPSERTED,
        ) {
            put("kind", reward.kind.name)
            put("costMinor", reward.cost.minorUnits)
            put("currency", Converters.currencyToString(reward.currency))
            put("active", reward.active)
        }
        rewards.upsert(row.copy(stamp = stamp))
    }
}

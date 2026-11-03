package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.InterestPolicyEntity
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.InterestPolicy
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.put
import javax.inject.Inject

/** The household's savings interest policy (D-M2-5), and the planner's per-account bookkeeping. */
interface InterestPolicyRepository {
    /** [householdId]'s policy; the default (0 bps, off, accrual on the 1st) until a parent sets one. */
    fun observe(householdId: HouseholdId): Flow<InterestPolicy>

    suspend fun get(householdId: HouseholdId): InterestPolicy

    /** Stores [policy] for its household. */
    suspend fun set(policy: InterestPolicy)

    /** The last accrual day already planned for SAVINGS account [accountId], or null before the first planning run. */
    suspend fun lastAccruedOn(accountId: AccountId): CalendarDate?

    /** Records that interest on [accountId] is planned through [date]. Device-local; never moves backwards. */
    suspend fun recordAccruedThrough(accountId: AccountId, date: CalendarDate)
}

/** [InterestPolicyRepository] on Room: each policy write, its outbox `DOCUMENT` row and its audit row together. */
class RoomInterestPolicyRepository @Inject constructor(
    database: SproutDatabase,
    private val authors: SessionAuthorProvider,
    clock: Clock,
    signer: DeviceSigner,
) : InterestPolicyRepository {
    private val policies = database.interestPolicyDao()
    private val accounts = database.accountDao()
    private val writes = DocumentWrites(database, clock, signer)

    override fun observe(householdId: HouseholdId): Flow<InterestPolicy> =
        policies.observe(householdId).map { it?.toDomain() ?: InterestPolicy(householdId) }

    override suspend fun get(householdId: HouseholdId): InterestPolicy =
        policies.get(householdId)?.takeUnless { it.stamp.deleted }?.toDomain() ?: InterestPolicy(householdId)

    override suspend fun set(policy: InterestPolicy) {
        val author = authors.currentAuthor()
        writes.transaction {
            val lamport = writes.nextLamport(author.deviceId)
            val row = InterestPolicyEntity.from(policy, lamport)
            val stamp = writes.record(
                author,
                DocTypes.INTEREST_POLICY,
                policy.householdId.value,
                lamport,
                DocumentPayloads.encode(InterestPolicy.serializer(), row.toDomain()),
                AuditAction.INTEREST_POLICY_UPDATED,
            ) {
                put("annualRateBasisPoints", policy.annualRateBasisPoints)
                put("accrualDay", policy.accrualDay)
            }
            policies.upsert(row.copy(stamp = stamp))
        }
    }

    override suspend fun lastAccruedOn(accountId: AccountId): CalendarDate? = accounts.get(accountId)?.lastAccruedOn

    override suspend fun recordAccruedThrough(accountId: AccountId, date: CalendarDate) {
        writes.transaction {
            val current = accounts.get(accountId) ?: return@transaction
            if (current.lastAccruedOn == null ||
                date > current.lastAccruedOn
            ) {
                accounts.setLastAccruedOn(accountId, date)
            }
        }
    }
}

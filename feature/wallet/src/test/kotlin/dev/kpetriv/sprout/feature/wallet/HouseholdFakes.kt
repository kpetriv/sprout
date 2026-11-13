package dev.kpetriv.sprout.feature.wallet

import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.crypto.ParentAuthUnavailableException
import dev.kpetriv.sprout.core.crypto.ParentKeyInvalidatedException
import dev.kpetriv.sprout.core.database.AccountRepository
import dev.kpetriv.sprout.core.database.AllowanceRepository
import dev.kpetriv.sprout.core.database.HouseholdPolicy
import dev.kpetriv.sprout.core.database.HouseholdPolicyRepository
import dev.kpetriv.sprout.core.database.RewardRepository
import dev.kpetriv.sprout.core.database.SavingsGoalRepository
import dev.kpetriv.sprout.core.database.usecase.PurchaseResult
import dev.kpetriv.sprout.core.domainhousehold.AllowanceSchedule
import dev.kpetriv.sprout.core.domainhousehold.Reward
import dev.kpetriv.sprout.core.domainhousehold.SavingsGoal
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.ParentAuthorization
import dev.kpetriv.sprout.core.domainledger.ParentAuthorizationRequester
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.GoalId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.ScheduleId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

/** [AccountRepository] over the accounts of an [InMemoryWalletDataSource] (for the use cases). */
class DataSourceAccountRepository(private val source: InMemoryWalletDataSource) : AccountRepository {
    override fun accountsFor(kidId: KidId): Flow<List<Account>> = source.accountsFor(kidId)

    override suspend fun ensureDefaultAccounts(kidId: KidId, fiatCurrency: Currency.Fiat): List<Account> =
        source.accountsFor(kidId).first()
}

class FakeSavingsGoalRepository : SavingsGoalRepository {
    val goals = MutableStateFlow<List<SavingsGoal>>(emptyList())

    override fun observeGoals(kidId: KidId): Flow<List<SavingsGoal>> =
        goals.map { all -> all.filter { it.kidId == kidId } }

    override suspend fun get(id: GoalId): SavingsGoal? = goals.value.firstOrNull { it.id == id }

    override suspend fun upsert(goal: SavingsGoal) {
        goals.value = goals.value.filterNot { it.id == goal.id } + goal
    }

    override suspend fun delete(id: GoalId): Boolean {
        val existed = get(id) != null
        goals.value = goals.value.filterNot { it.id == id }
        return existed
    }
}

/** [HouseholdPolicyRepository] holding [policy]; starts with parent signatures required. */
class FakeHouseholdPolicyRepository(signaturesRequired: Boolean = true) : HouseholdPolicyRepository {
    val policy = MutableStateFlow<HouseholdPolicy?>(policy(signaturesRequired))

    override fun observe(): Flow<HouseholdPolicy?> = policy

    override suspend fun get(): HouseholdPolicy? = policy.value

    override suspend fun ensure(fiatCurrency: Currency.Fiat): HouseholdPolicy = checkNotNull(policy.value)

    override suspend fun registerParentKey(publicKeyDer: ByteArray): HouseholdPolicy = policy(true).also {
        policy.value = it
    }

    fun setSignaturesRequired(required: Boolean) {
        policy.value = policy(required)
    }

    private companion object {
        fun policy(required: Boolean) = HouseholdPolicy(
            requireParentSignature = required,
            parentSignatureFromLamport = if (required) 1 else null,
            parentPublicKey = if (required) byteArrayOf(1, 2, 3) else null,
            fiatCurrency = TestData.gbp,
            createdAt = Instant.fromEpochMilliseconds(0),
        )
    }
}

/** Records every prompt reason; [behaviour] decides what each prompt does. */
class FakeParentAuthorizationRequester : ParentAuthorizationRequester {
    enum class Behaviour {
        /** A working single-use authorisation. */
        GRANT,

        /** The requester itself throws [ParentAuthUnavailableException]. */
        UNAVAILABLE,

        /** Returns an authorisation whose `sign` throws [ParentAuthUnavailableException] (the lock module's shape). */
        UNAVAILABLE_ON_SIGN,

        /** The parent dismissed the prompt. */
        CANCELLED,

        /** A biometric enrolment change invalidated the parent key. */
        KEY_INVALIDATED,
    }

    val reasons = mutableListOf<String>()
    var behaviour = Behaviour.GRANT
    var signCount = 0
        private set

    override suspend fun request(reason: String): ParentAuthorization {
        reasons += reason
        return when (behaviour) {
            Behaviour.GRANT -> ParentAuthorization { payload ->
                signCount++
                payload.copyOfRange(0, minOf(payload.size, SIGNATURE_BYTES))
            }

            Behaviour.UNAVAILABLE -> throw ParentAuthUnavailableException(ParentAuthAvailability.NO_HARDWARE)

            Behaviour.UNAVAILABLE_ON_SIGN -> ParentAuthorization {
                throw ParentAuthUnavailableException(ParentAuthAvailability.NOT_ENROLLED)
            }

            Behaviour.CANCELLED -> error("prompt dismissed")

            Behaviour.KEY_INVALIDATED -> throw ParentKeyInvalidatedException()
        }
    }

    private companion object {
        const val SIGNATURE_BYTES = 8
    }
}

class FakeRewardRepository : RewardRepository {
    val rewards = MutableStateFlow<List<Reward>>(emptyList())
    var failing = false

    /** Makes [upsert] throw, as a storage failure would. */
    var failWrites = false

    override fun observeRewards(kidId: KidId?, includeInactive: Boolean): Flow<List<Reward>> = rewards.map { all ->
        check(!failing) { "storage unavailable" }
        all.filter { if (kidId == null) includeInactive || it.active else it.isAvailableTo(kidId) }
            .sortedBy { it.cost.minorUnits }
    }

    override suspend fun get(id: RewardId): Reward? = rewards.value.firstOrNull { it.id == id }

    override suspend fun upsert(reward: Reward) {
        check(!failWrites) { "write failed" }
        rewards.value = rewards.value.filterNot { it.id == reward.id } + reward
    }

    override suspend fun setActive(id: RewardId, active: Boolean): Boolean {
        val reward = get(id) ?: return false
        upsert(reward.copy(active = active))
        return true
    }
}

class FakeAllowanceRepository : AllowanceRepository {
    val schedules = MutableStateFlow<List<AllowanceSchedule>>(emptyList())

    override fun observeSchedules(kidId: KidId?): Flow<List<AllowanceSchedule>> =
        schedules.map { all -> all.filter { kidId == null || it.kidId == kidId } }

    override suspend fun get(id: ScheduleId): AllowanceSchedule? = schedules.value.firstOrNull { it.id == id }

    override suspend fun upsert(schedule: AllowanceSchedule) {
        schedules.value = schedules.value.filterNot { it.id == schedule.id } + schedule
    }

    override suspend fun lastPaidOn(id: ScheduleId): CalendarDate? = null

    override suspend fun recordPaidThrough(id: ScheduleId, dueOn: CalendarDate) = Unit
}

/** [RewardPurchaser] returning [nextResult] (or throwing [failure]); waits for [gate] when set. */
class FakeRewardPurchaser : RewardPurchaser {
    val purchases = mutableListOf<Pair<KidId, RewardId>>()
    var nextResult: PurchaseResult = PurchaseResult.Unavailable
    var failure: Exception? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun purchase(kidId: KidId, rewardId: RewardId): PurchaseResult {
        gate?.await()
        failure?.let { throw it }
        purchases += kidId to rewardId
        return nextResult
    }
}

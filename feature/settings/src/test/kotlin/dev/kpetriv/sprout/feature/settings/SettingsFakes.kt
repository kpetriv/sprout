package dev.kpetriv.sprout.feature.settings

import dev.kpetriv.sprout.core.crypto.LockoutDecision
import dev.kpetriv.sprout.core.database.AccountRepository
import dev.kpetriv.sprout.core.database.AllowanceRepository
import dev.kpetriv.sprout.core.database.HouseholdPolicy
import dev.kpetriv.sprout.core.database.HouseholdPolicyRepository
import dev.kpetriv.sprout.core.database.InterestPolicyRepository
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.database.SavingsGoalRepository
import dev.kpetriv.sprout.core.database.SessionAuthorProvider
import dev.kpetriv.sprout.core.domainhousehold.AllowanceSchedule
import dev.kpetriv.sprout.core.domainhousehold.SavingsGoal
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.EventAuthor
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.GoalId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.InterestPolicy
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.ScheduleId
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.time.FakeClock
import dev.kpetriv.sprout.core.time.HouseholdCalendar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.time.ZoneOffset
import kotlin.time.Instant

val HOUSEHOLD = HouseholdId("household-1")
val GBP = Currency.Fiat("GBP")

/** Tuesday 3 November 2026, noon UTC. */
val TODAY = CalendarDate(2026, 11, 3)

fun fixedToday(): HouseholdCalendar =
    HouseholdCalendar(FakeClock(Instant.parse("2026-11-03T12:00:00Z"))) { ZoneOffset.UTC }

/** Set [failWrites] to make every write throw, like a broken database. */
open class Failing {
    var failWrites = false

    protected fun checkWrite() {
        if (failWrites) throw IOException("write failed")
    }
}

class FakeKidRepository(vararg initial: Kid) :
    Failing(),
    KidRepository {
    val kids = MutableStateFlow(initial.toList())

    override fun observeKids(): Flow<List<Kid>> = kids.map { list -> list.filterNot { it.archived } }

    override suspend fun getKid(id: KidId): Kid? = kids.value.firstOrNull { it.id == id }

    override suspend fun upsert(kid: Kid) {
        checkWrite()
        kids.update { list -> list.filterNot { it.id == kid.id } + kid }
    }

    override suspend fun archive(id: KidId): Boolean {
        checkWrite()
        val kid = getKid(id) ?: return false
        upsert(kid.copy(archived = true))
        return true
    }
}

class FakeAccountRepository :
    Failing(),
    AccountRepository {
    val accounts = MutableStateFlow<List<Account>>(emptyList())
    val ensured = mutableListOf<Pair<KidId, Currency.Fiat>>()

    override fun accountsFor(kidId: KidId): Flow<List<Account>> =
        accounts.map { list -> list.filter { it.kidId == kidId } }

    override suspend fun ensureDefaultAccounts(kidId: KidId, fiatCurrency: Currency.Fiat): List<Account> {
        checkWrite()
        ensured += kidId to fiatCurrency
        if (accounts.value.none { it.kidId == kidId }) {
            accounts.update {
                it + listOf(
                    Account(AccountId("${kidId.value}-main"), kidId, fiatCurrency, AccountKind.MAIN),
                    Account(AccountId("${kidId.value}-savings"), kidId, fiatCurrency, AccountKind.SAVINGS),
                    Account(AccountId("${kidId.value}-minutes"), kidId, Currency.Minutes, AccountKind.MAIN),
                )
            }
        }
        return accounts.value.filter { it.kidId == kidId }
    }
}

class FakeAllowanceRepository :
    Failing(),
    AllowanceRepository {
    val schedules = MutableStateFlow<List<AllowanceSchedule>>(emptyList())

    override fun observeSchedules(kidId: KidId?): Flow<List<AllowanceSchedule>> =
        schedules.map { list -> list.filter { kidId == null || it.kidId == kidId } }

    override suspend fun get(id: ScheduleId): AllowanceSchedule? = schedules.value.firstOrNull { it.id == id }

    override suspend fun upsert(schedule: AllowanceSchedule) {
        checkWrite()
        schedules.update { list -> list.filterNot { it.id == schedule.id } + schedule }
    }

    override suspend fun lastPaidOn(id: ScheduleId): CalendarDate? = null

    override suspend fun recordPaidThrough(id: ScheduleId, dueOn: CalendarDate) = Unit
}

class FakeSavingsGoalRepository :
    Failing(),
    SavingsGoalRepository {
    val goals = MutableStateFlow<List<SavingsGoal>>(emptyList())

    override fun observeGoals(kidId: KidId): Flow<List<SavingsGoal>> =
        goals.map { list -> list.filter { it.kidId == kidId } }

    override suspend fun get(id: GoalId): SavingsGoal? = goals.value.firstOrNull { it.id == id }

    override suspend fun upsert(goal: SavingsGoal) {
        checkWrite()
        goals.update { list -> list.filterNot { it.id == goal.id } + goal }
    }

    override suspend fun delete(id: GoalId): Boolean {
        checkWrite()
        val present = get(id) != null
        goals.update { list -> list.filterNot { it.id == id } }
        return present
    }
}

class FakeInterestPolicyRepository(initial: InterestPolicy = InterestPolicy(HOUSEHOLD)) :
    Failing(),
    InterestPolicyRepository {
    val policy = MutableStateFlow(initial)
    val writes = mutableListOf<InterestPolicy>()

    override fun observe(householdId: HouseholdId): Flow<InterestPolicy> = policy

    override suspend fun get(householdId: HouseholdId): InterestPolicy = policy.value

    override suspend fun set(policy: InterestPolicy) {
        checkWrite()
        writes += policy
        this.policy.value = policy
    }

    override suspend fun lastAccruedOn(accountId: AccountId): CalendarDate? = null

    override suspend fun recordAccruedThrough(accountId: AccountId, date: CalendarDate) = Unit
}

class FakeHouseholdPolicyRepository(initial: HouseholdPolicy? = policy(hasKey = false)) : HouseholdPolicyRepository {
    val policy = MutableStateFlow(initial)

    /** The server never recorded this device's key (D-M3-39). */
    val serverPending = MutableStateFlow(false)

    override fun observeServerRegistrationPending(): Flow<Boolean> = serverPending

    override fun observe(): Flow<HouseholdPolicy?> = policy

    override suspend fun get(): HouseholdPolicy? = policy.value

    override suspend fun ensure(fiatCurrency: Currency.Fiat): HouseholdPolicy =
        policy.value ?: policy(hasKey = false, currency = fiatCurrency).also { policy.value = it }

    override suspend fun registerParentKey(publicKeyDer: ByteArray): HouseholdPolicy =
        policy(hasKey = true).also { policy.value = it }

    companion object {
        fun policy(hasKey: Boolean, currency: Currency.Fiat = GBP) = HouseholdPolicy(
            requireParentSignature = hasKey,
            parentSignatureFromLamport = if (hasKey) 1 else null,
            parentPublicKey = if (hasKey) byteArrayOf(1, 2, 3) else null,
            fiatCurrency = currency,
            createdAt = Instant.fromEpochSeconds(0),
        )
    }
}

class FakeAuthors : SessionAuthorProvider {
    override suspend fun currentAuthor() = EventAuthor(HOUSEHOLD, UserId("parent"), Role.PARENT, DeviceId("device-1"))
}

fun householdContext(policies: HouseholdPolicyRepository = FakeHouseholdPolicyRepository()) =
    HouseholdContext(FakeAuthors(), policies) { Currency.Fiat("EUR") }

/** Records what it was handed (copies, since the caller zeroes the arrays) and answers [outcome]. */
class FakePinChanger(var outcome: PinChangeOutcome = PinChangeOutcome.Changed) : PinChanger {
    val calls = mutableListOf<Triple<String, String, String>>()
    var received: List<CharArray> = emptyList()

    override suspend fun changePin(current: CharArray, new: CharArray, confirm: CharArray): PinChangeOutcome {
        calls += Triple(String(current), String(new), String(confirm))
        received = listOf(current, new, confirm)
        return outcome
    }
}

/** Records the PINs it was handed (copies, since the caller zeroes them) and answers [result]. */
class FakeRegistrar(var result: suspend (purpose: String) -> SigningKeyOutcome = { SigningKeyOutcome.Registered }) :
    ParentKeyRegistrar {
    val calls = mutableListOf<Pair<String, String>>()
    var received: CharArray? = null
    var needsRenewal = false

    override suspend fun needsRenewal(): Boolean = needsRenewal

    override suspend fun enrol(pin: CharArray): SigningKeyOutcome = record("enrol", pin)

    override suspend fun renew(pin: CharArray): SigningKeyOutcome = record("renew", pin)

    private suspend fun record(purpose: String, pin: CharArray): SigningKeyOutcome {
        calls += purpose to String(pin)
        received = pin
        return result(purpose)
    }
}

class FakeLockPreferences(initial: Boolean = true) : LockPreferences {
    private val flow = MutableStateFlow(initial)
    override val lockWhenLeaving: StateFlow<Boolean> = flow.asStateFlow()

    override fun setLockWhenLeaving(enabled: Boolean) {
        flow.value = enabled
    }
}

/** A lockout wait used across the PIN tests. */
val WAIT_30_SECONDS: LockoutDecision = LockoutDecision.Wait(kotlin.time.Duration.parse("30s"))

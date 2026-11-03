package dev.kpetriv.sprout.feature.parenthome

import dev.kpetriv.sprout.core.database.AccountRepository
import dev.kpetriv.sprout.core.database.AllowanceRepository
import dev.kpetriv.sprout.core.database.ChoreRepository
import dev.kpetriv.sprout.core.database.FailedParentAction
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.database.PendingParentActionRepository
import dev.kpetriv.sprout.core.database.SubmissionDecision
import dev.kpetriv.sprout.core.database.SubmissionFilter
import dev.kpetriv.sprout.core.database.entity.PendingActionStatus
import dev.kpetriv.sprout.core.database.usecase.DrainResult
import dev.kpetriv.sprout.core.database.usecase.FailedAction
import dev.kpetriv.sprout.core.domainhousehold.AllowanceSchedule
import dev.kpetriv.sprout.core.domainhousehold.Chore
import dev.kpetriv.sprout.core.domainhousehold.PendingParentAction
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.Balance
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.LedgerAnomaly
import dev.kpetriv.sprout.core.domainledger.LedgerEvent
import dev.kpetriv.sprout.core.domainledger.LedgerRepository
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.ParentAuthorizationRequester
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreState
import dev.kpetriv.sprout.core.model.ChoreSubmission
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.PhotoRef
import dev.kpetriv.sprout.core.model.ScheduleId
import dev.kpetriv.sprout.core.model.SubmissionId
import dev.kpetriv.sprout.core.time.FakeClock
import dev.kpetriv.sprout.core.time.HouseholdCalendar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Instant

val GBP = Currency.Fiat("GBP")

/** Tuesday 3 November 2026, noon UTC. */
fun fixedToday(): HouseholdCalendar =
    HouseholdCalendar(FakeClock(Instant.parse("2026-11-03T12:00:00Z"))) { ZoneOffset.UTC }

fun accountsOf(kidId: KidId) = listOf(
    Account(AccountId("${kidId.value}-main"), kidId, GBP, AccountKind.MAIN),
    Account(AccountId("${kidId.value}-savings"), kidId, GBP, AccountKind.SAVINGS),
    Account(AccountId("${kidId.value}-minutes"), kidId, Currency.Minutes, AccountKind.MAIN),
)

class FakeKidRepository(vararg initial: Kid) : KidRepository {
    val kids = MutableStateFlow(initial.toList())

    override fun observeKids(): Flow<List<Kid>> = kids

    override suspend fun getKid(id: KidId): Kid? = kids.value.firstOrNull { it.id == id }

    override suspend fun upsert(kid: Kid) = error("read only")

    override suspend fun archive(id: KidId): Boolean = error("read only")
}

class FakeAccountRepository : AccountRepository {
    val accounts = MutableStateFlow<List<Account>>(emptyList())

    override fun accountsFor(kidId: KidId): Flow<List<Account>> =
        accounts.map { list -> list.filter { it.kidId == kidId } }

    override suspend fun ensureDefaultAccounts(kidId: KidId, fiatCurrency: Currency.Fiat): List<Account> =
        error("read only")
}

/** Balances and anomalies per kid; the write half of [LedgerRepository] is never used by the dashboard. */
class FakeLedgerRepository : LedgerRepository {
    val balances = MutableStateFlow<Map<KidId, List<Balance>>>(emptyMap())
    val anomalies = MutableStateFlow<Map<KidId, List<LedgerAnomaly>>>(emptyMap())

    fun setBalance(kidId: KidId, accountId: AccountId, currency: Currency, amount: Long) {
        val balance = Balance(accountId, currency, Money(amount), Instant.fromEpochSeconds(0), 1)
        balances.value += kidId to (balances.value[kidId].orEmpty().filterNot { it.accountId == accountId } + balance)
    }

    fun setAnomalies(kidId: KidId, count: Int) {
        anomalies.value += kidId to List(count) {
            LedgerAnomaly.OverdraftDetected(AccountId("${kidId.value}-main"), Money(1), emptyList())
        }
    }

    override fun observeBalances(kidId: KidId): Flow<List<Balance>> = balances.map { it[kidId].orEmpty() }

    override fun observeAnomalies(kidId: KidId): Flow<List<LedgerAnomaly>> = anomalies.map { it[kidId].orEmpty() }

    override fun observeEvents(kidId: KidId, limit: Int): Flow<List<LedgerEvent>> = error("unused")

    override suspend fun append(event: LedgerEvent): AppendResult = error("unused")

    override suspend fun latestLamport(): Long = 0

    override suspend fun headHash(authorDeviceId: DeviceId): String? = null
}

class FakeChoreRepository : ChoreRepository {
    val pendingSubmissions = MutableStateFlow<List<ChoreSubmission>>(emptyList())
    var failReads = false

    fun setPending(count: Int, kidId: KidId) {
        pendingSubmissions.value = List(count) { index ->
            ChoreSubmission(
                id = SubmissionId("submission-$index"),
                choreId = ChoreId("chore-$index"),
                dueOn = CalendarDate(2026, 11, 3),
                kidId = kidId,
                submittedAt = Instant.fromEpochSeconds(0),
            )
        }
    }

    override fun observeSubmissions(filter: SubmissionFilter): Flow<List<ChoreSubmission>> {
        check(filter == SubmissionFilter.PendingForParent) { "the dashboard only counts pending submissions" }
        return pendingSubmissions.map { if (failReads) error("database closed") else it }
    }

    override fun observeChores(kidId: KidId?, includeArchived: Boolean): Flow<List<Chore>> = error("unused")

    override suspend fun get(id: ChoreId): Chore? = error("unused")

    override suspend fun upsert(chore: Chore) = error("unused")

    override suspend fun setState(id: ChoreId, state: ChoreState): Boolean = error("unused")

    override suspend fun getSubmission(id: SubmissionId): ChoreSubmission? = error("unused")

    override suspend fun submit(choreId: ChoreId, dueOn: CalendarDate, note: String?, photo: PhotoRef?) =
        error("unused")

    override suspend fun decide(id: SubmissionId, decision: SubmissionDecision, reason: String?) = error("unused")
}

class FakeAllowanceRepository : AllowanceRepository {
    val schedules = MutableStateFlow<List<AllowanceSchedule>>(emptyList())

    override fun observeSchedules(kidId: KidId?): Flow<List<AllowanceSchedule>> =
        schedules.map { list -> list.filter { kidId == null || it.kidId == kidId } }

    override suspend fun get(id: ScheduleId): AllowanceSchedule? = schedules.value.firstOrNull { it.id == id }

    override suspend fun upsert(schedule: AllowanceSchedule) = error("read only")

    override suspend fun lastPaidOn(id: ScheduleId): CalendarDate? = null

    override suspend fun recordPaidThrough(id: ScheduleId, dueOn: CalendarDate) = Unit
}

class FakePendingParentActionRepository : PendingParentActionRepository {
    val pending = MutableStateFlow<List<PendingParentAction>>(emptyList())

    override suspend fun enqueue(action: PendingParentAction): Boolean = error("unused")

    override fun observePending(): Flow<List<PendingParentAction>> = pending

    override suspend fun pending(): List<PendingParentAction> = pending.value

    val failed = MutableStateFlow<List<FailedParentAction>>(emptyList())
    val skipped = MutableStateFlow<List<PendingParentAction>>(emptyList())
    val dismissed = mutableListOf<String>()

    override suspend fun markDone(key: String, eventId: EventId): Boolean {
        pending.value = pending.value.filterNot { it.key == key }
        return true
    }

    override suspend fun markFailed(key: String, reason: String): Boolean {
        val action = pending.value.firstOrNull { it.key == key } ?: return false
        pending.value -= action
        failed.value += FailedParentAction(action, reason, refusals = 0)
        return true
    }

    override suspend fun recordRefusal(key: String, reason: String): PendingActionStatus? = error("unused")

    override suspend fun cancel(key: String, reason: String?): Boolean = error("unused")

    override fun observeFailed(): Flow<List<FailedParentAction>> = failed

    override suspend fun recordSkipped(action: PendingParentAction): Boolean = error("unused")

    override fun observeSkipped(): Flow<List<PendingParentAction>> = skipped

    override suspend fun dismiss(key: String): Boolean {
        val before = failed.value.size + skipped.value.size
        failed.value = failed.value.filterNot { it.action.key == key }
        skipped.value = skipped.value.filterNot { it.key == key }
        dismissed += key
        return failed.value.size + skipped.value.size < before
    }
}

/**
 * Asks for one authorisation per queued action, like `DrainPendingParentActionsUseCase.drainWith`, with a reason
 * naming the action's key, and marks each done. [failKeys] are refused by "the ledger" (stay queued);
 * [invalidKeys] fail before any prompt (FAILED for good); [alreadyPaidKeys] are done without a prompt.
 * [keyInvalidatedAt] stops the drain at that action as an invalidated parent key would; [running] answers
 * [DrainResult.AlreadyRunning].
 */
class FakeSigner(private val queue: FakePendingParentActionRepository) : PendingActionSigner {
    var failKeys: Set<String> = emptySet()
    var invalidKeys: Set<String> = emptySet()
    var alreadyPaidKeys: Set<String> = emptySet()
    var keyInvalidatedAt: String? = null
    var running = false
    var calls = 0
    val reasons = mutableListOf<String>()

    @Suppress("ReturnCount") // Mirrors the use case's early stops.
    override suspend fun signAll(requester: ParentAuthorizationRequester): DrainResult {
        calls++
        if (running) return DrainResult.AlreadyRunning
        val signed = mutableListOf<EventId>()
        val failed = mutableListOf<FailedAction>()
        var alreadyPaid = 0
        for (action in queue.pending.value) {
            when (action.key) {
                keyInvalidatedAt -> return DrainResult.Drained(signed, alreadyPaid, failed, keyInvalidated = true)

                in alreadyPaidKeys -> {
                    queue.markDone(action.key, newEventId())
                    alreadyPaid++
                }

                in invalidKeys -> {
                    queue.markFailed(action.key, "invalid")
                    failed += FailedAction(action, AppendResult.Rejected("invalid"), permanent = true)
                }

                else -> {
                    val reason = "Pay ${action.key}"
                    reasons += reason
                    requester.request(reason).sign(byteArrayOf(1))
                    if (action.key in failKeys) {
                        failed += FailedAction(action, AppendResult.Rejected("refused"))
                    } else {
                        val id = newEventId()
                        queue.markDone(action.key, id)
                        signed += id
                    }
                }
            }
        }
        return DrainResult.Drained(signed, alreadyPaid, failed)
    }

    private fun newEventId() = EventId(UUID.randomUUID().toString())
}

fun allowanceAction(kidId: KidId, period: String) = PendingParentAction.PayAllowance(
    scheduleId = ScheduleId("${kidId.value}-weekly"),
    kidId = kidId,
    accountId = AccountId("${kidId.value}-main"),
    amount = Money(500),
    currency = GBP,
    periodKey = period,
    dueOn = CalendarDate(2026, 11, 1),
)

fun interestAction(kidId: KidId, period: String) = PendingParentAction.AccrueInterest(
    kidId = kidId,
    accountId = AccountId("${kidId.value}-savings"),
    currency = GBP,
    principal = Money(10_000),
    annualRateBasisPoints = 250,
    days = 30,
    periodKey = period,
    amount = Money(21),
)

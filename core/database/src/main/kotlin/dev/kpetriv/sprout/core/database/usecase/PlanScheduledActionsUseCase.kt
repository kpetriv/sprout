package dev.kpetriv.sprout.core.database.usecase

import dev.kpetriv.sprout.core.database.AccountRepository
import dev.kpetriv.sprout.core.database.AllowanceRepository
import dev.kpetriv.sprout.core.database.InterestPolicyRepository
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.database.LedgerSnapshot
import dev.kpetriv.sprout.core.database.PendingParentActionRepository
import dev.kpetriv.sprout.core.database.RoomLedgerRepository
import dev.kpetriv.sprout.core.domainhousehold.AllowancePlanner
import dev.kpetriv.sprout.core.domainhousehold.InterestPlanner
import dev.kpetriv.sprout.core.domainhousehold.PendingParentAction
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.Projection
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.ScheduleId
import dev.kpetriv.sprout.core.time.HouseholdCalendar
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Outcome of [PlanScheduledActionsUseCase].
 *
 * @property queued actions newly queued for the parent to sign.
 * @property skippedAllowance per schedule, due dates older than the catch-up window that were not queued (D-M2-4).
 *   Also recorded as skipped rows in `pending_parent_actions` ([PendingParentActionRepository.observeSkipped]) so the
 *   parent dashboard can list them later.
 * @property redeemed owed screen-time grants credited by this run ([RedeemOwedGrantsUseCase]).
 */
data class PlanResult(
    val queued: List<PendingParentAction>,
    val skippedAllowance: Map<ScheduleId, List<CalendarDate>>,
    val redeemed: RedeemResult = RedeemResult(emptyList(), emptyMap()),
)

/**
 * Plans every active kid's allowance ([AllowancePlanner]) and savings interest ([InterestPlanner]) up to today and
 * queues the payments in `pending_parent_actions` for the parent's next "Sign now", after crediting any screen-time
 * grant still owed ([RedeemOwedGrantsUseCase]). Called at startup and by the daily WorkManager job.
 *
 * Idempotent: the per-schedule `lastPaidOn` and per-account `lastAccruedOn` bookkeeping advance as periods are
 * queued, queueing is idempotent by action key, and periods the ledger already holds are not queued. A SAVINGS account
 * planned for the first time starts accruing from that day.
 *
 * **Interest principal (R2).** Each period's principal is the SAVINGS balance at the end of the period's start day
 * ([InterestPlanner]), projected from the kid's verified events **created** up to that day. Approximation: an event's
 * day is its `createdAt` (wall clock, display only, ADR-0002) in the household zone, folded in lamport order; an event
 * written offline with a wrong device clock lands on the wrong day. Good enough for interest on a kid's savings; it is
 * never used for ordering or validity.
 */
@Suppress("LongParameterList") // Every collaborator is a repository the planners read or write.
class PlanScheduledActionsUseCase @Inject constructor(
    private val kids: KidRepository,
    private val accounts: AccountRepository,
    private val allowances: AllowanceRepository,
    private val interest: InterestPolicyRepository,
    private val pending: PendingParentActionRepository,
    private val ledger: RoomLedgerRepository,
    private val redeemOwedGrants: RedeemOwedGrantsUseCase,
    private val calendar: HouseholdCalendar,
) {
    /** Plans up to [today] (the household-local date by default). */
    suspend operator fun invoke(today: CalendarDate = calendar.today()): PlanResult {
        val redeemed = redeemOwedGrants()
        val snapshot = ledger.snapshot()
        val projection = snapshot.projection
        val queued = mutableListOf<PendingParentAction>()
        val skipped = mutableMapOf<ScheduleId, List<CalendarDate>>()
        for (kid in kids.observeKids().first()) {
            planAllowance(kid, today, projection, queued, skipped)
            planInterest(kid, today, snapshot, queued)
        }
        return PlanResult(queued, skipped, redeemed)
    }

    private suspend fun planAllowance(
        kid: Kid,
        today: CalendarDate,
        projection: Projection,
        queued: MutableList<PendingParentAction>,
        skipped: MutableMap<ScheduleId, List<CalendarDate>>,
    ) {
        for (schedule in allowances.observeSchedules(kid.id).first()) {
            val plan = AllowancePlanner.plan(schedule, allowances.lastPaidOn(schedule.id), today)
            plan.due.filterNot { it.claim() in projection.claimedPeriods }.forEach { queue(it, queued) }
            plan.due.lastOrNull()?.let { allowances.recordPaidThrough(schedule.id, it.dueOn) }
            if (plan.skipped.isNotEmpty()) {
                skipped[schedule.id] = plan.skipped
                plan.skipped.forEach { pending.recordSkipped(AllowancePlanner.paymentFor(schedule, it)) }
            }
        }
    }

    private suspend fun planInterest(
        kid: Kid,
        today: CalendarDate,
        snapshot: LedgerSnapshot,
        queued: MutableList<PendingParentAction>,
    ) {
        val policy = interest.get(kid.householdId)
        val savings = accounts.accountsFor(kid.id).first().filter { it.kind == AccountKind.SAVINGS }
        val history = BalanceHistory(snapshot, kid.id, calendar)
        for (account in savings) {
            val last = interest.lastAccruedOn(account.id)
            if (last == null) {
                interest.recordAccruedThrough(account.id, today)
                continue
            }
            val plan = InterestPlanner.plan(policy, account, { history.balanceOn(account.id, it) }, last, today)
            plan.accruals.filterNot { it.claim() in snapshot.projection.claimedPeriods }.forEach { queue(it, queued) }
            plan.accruedThrough?.let { interest.recordAccruedThrough(account.id, it) }
        }
    }

    private suspend fun queue(action: PendingParentAction, queued: MutableList<PendingParentAction>) {
        if (pending.enqueue(action)) queued += action
    }
}

/**
 * Balances of [kidId]'s accounts at the end of a household-local day: the projection of the kid's events whose
 * `createdAt`, in the household zone ([calendar]), falls on or before that day. One projection per day asked, cached.
 */
internal class BalanceHistory(
    private val snapshot: LedgerSnapshot,
    private val kidId: KidId,
    private val calendar: HouseholdCalendar,
) {
    private val byDay = HashMap<CalendarDate, Projection>()

    fun balanceOn(accountId: AccountId, day: CalendarDate): Money = byDay.getOrPut(day) {
        snapshot.projectionOf { it.envelope.kidId == kidId && calendar.dateOf(it.envelope.createdAt) <= day }
    }.balanceOf(accountId)
}

package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.InterestCalculator
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.InterestPolicy

/**
 * The outcome of planning interest for one SAVINGS account.
 *
 * @property accruals the accruals to queue, oldest first; only those with a positive amount.
 * @property accruedThrough the latest elapsed accrual day, or null if none elapsed. The caller stores it as the
 *   next `lastAccruedOn` even when every amount rounded to zero, so a zero balance does not bank days that a
 *   later deposit would then earn interest on.
 */
data class InterestPlan(val accruals: List<PendingParentAction.AccrueInterest>, val accruedThrough: CalendarDate?)

/**
 * Plans savings interest (D-M2-5): simple interest, Actual/365, accrued monthly on the policy's accrual day using
 * [InterestCalculator.accrue]. Pure: the caller supplies the SAVINGS balance history (`balanceOn`), the last accrual
 * date and today's household-local date.
 *
 * Each elapsed accrual day yields one [PendingParentAction.AccrueInterest] covering the actual days since the
 * previous accrual day (or since `lastAccruedOn`), with period key `YYYY-MM` of the accrual day.
 *
 * **Principal per period.** A period runs from the day after its start (the previous accrual day, or
 * `lastAccruedOn`) to its accrual day. Its principal is the **opening balance**: `balanceOn(start)`, the SAVINGS
 * balance at the end of the start day (negative counts as zero), so a deposit made during a period starts earning in
 * the next one and a balance that changes between periods earns on what it was in each. Over a multi-period gap each
 * accrual's principal also includes the interest planned before it in the same plan (not yet in the ledger, so not in
 * `balanceOn`), matching what would have happened had every accrual been signed on time.
 *
 * After a long gap at most [AllowancePlanner.MAX_CATCH_UP_PERIODS] accruals are emitted; the days of older periods
 * are folded into the oldest emitted one (whose principal is then the balance at `lastAccruedOn`), so no interest is
 * lost, only some compounding between those periods.
 */
object InterestPlanner {
    /** Accruals due; see [plan]. */
    fun due(
        policy: InterestPolicy,
        account: Account,
        balanceOn: (CalendarDate) -> Money,
        lastAccruedOn: CalendarDate,
        today: CalendarDate,
    ): List<PendingParentAction.AccrueInterest> = plan(policy, account, balanceOn, lastAccruedOn, today).accruals

    /**
     * Plans interest on [account] (a SAVINGS account) for accrual days after [lastAccruedOn] up to and including
     * [today]. [balanceOn] gives the account's balance at the end of a household-local day; it is only asked about
     * period start days (at most [AllowancePlanner.MAX_CATCH_UP_PERIODS] of them). [lastAccruedOn] is the last accrual
     * day already accrued, or the date interest started (policy enabled or account opened) when there has been none.
     * A negative balance earns nothing. Interest is off (empty plan, but [InterestPlan.accruedThrough] still advances)
     * at 0 bps.
     */
    fun plan(
        policy: InterestPolicy,
        account: Account,
        balanceOn: (CalendarDate) -> Money,
        lastAccruedOn: CalendarDate,
        today: CalendarDate,
    ): InterestPlan {
        require(account.kind == AccountKind.SAVINGS) { "interest accrues on SAVINGS accounts only" }
        val accrualDays = lastAccruedOn.plusDaysOrNull(1)
            ?.let { start -> ChoreScheduler.monthly(policy.accrualDay, start).takeWhile { it <= today }.toList() }
            .orEmpty()
        val periods = if (policy.isEnabled) periods(lastAccruedOn, accrualDays) else emptyList()

        var plannedInterest = Money.ZERO
        val accruals = periods.mapNotNull { period ->
            val opening = balanceOn(period.start).let { if (it.isNegative) Money.ZERO else it }
            val principal = opening + plannedInterest
            val amount = InterestCalculator.accrue(principal, policy.annualRateBasisPoints, period.days)
            amount.takeIf { it.isPositive }?.let {
                PendingParentAction.AccrueInterest(
                    kidId = account.kidId,
                    accountId = account.id,
                    currency = account.currency,
                    principal = principal,
                    annualRateBasisPoints = policy.annualRateBasisPoints,
                    days = period.days,
                    periodKey = period.accrualDay.monthlyPeriodKey(),
                    amount = amount,
                ).also { plannedInterest += amount }
            }
        }
        return InterestPlan(accruals, accrualDays.lastOrNull())
    }

    /** One accrual period: interest for [days] days after [start], credited on [accrualDay]. */
    private data class Period(val start: CalendarDate, val accrualDay: CalendarDate, val days: Int)

    /**
     * One [Period] per accrual day, with every period older than the catch-up window folded into the oldest kept one
     * (which then starts at [lastAccruedOn]).
     */
    private fun periods(lastAccruedOn: CalendarDate, accrualDays: List<CalendarDate>): List<Period> {
        val all = accrualDays.mapIndexed { index, day ->
            val previous = if (index == 0) lastAccruedOn else accrualDays[index - 1]
            Period(previous, day, previous.daysUntil(day).toInt())
        }
        val folded = (all.size - AllowancePlanner.MAX_CATCH_UP_PERIODS).coerceAtLeast(0)
        if (folded == 0) return all
        val kept = all.drop(folded)
        val oldest = kept.first()
        val merged = oldest.copy(start = lastAccruedOn, days = oldest.days + all.take(folded).sumOf { it.days })
        return listOf(merged) + kept.drop(1)
    }
}

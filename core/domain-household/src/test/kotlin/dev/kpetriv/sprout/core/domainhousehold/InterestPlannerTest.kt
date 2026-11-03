package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.InterestCalculator
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.InterestPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class InterestPlannerTest {
    private val fivePercent = InterestPolicy(HOUSEHOLD, annualRateBasisPoints = 500, accrualDay = 1)

    /** A balance that never changes. */
    private fun flat(minor: Long): (CalendarDate) -> Money = { gbp(minor) }

    @Test
    fun oneMonthOfInterestUsesActualDays() {
        // £100.00 at 5% for October's 31 days: 10000 * 500 * 31 / 3650000 = 42.46... -> 42.
        val due = InterestPlanner.due(fivePercent, SAVINGS, flat(10_000), date("2026-10-01"), date("2026-11-02"))
        val accrual = due.single()
        assertEquals(31, accrual.days)
        assertEquals(Money(42), accrual.amount)
        assertEquals(Money(10_000), accrual.principal)
        assertEquals(500, accrual.annualRateBasisPoints)
        assertEquals("2026-11", accrual.periodKey)
        assertEquals(SAVINGS.id, accrual.accountId)
        assertEquals(KID, accrual.kidId)
        assertEquals(GBP, accrual.currency)
        assertEquals("interest/${SAVINGS.id}/2026-11", accrual.key)
    }

    @Test
    fun aPartialFirstPeriodCountsFromLastAccruedOn() {
        val due = InterestPlanner.due(fivePercent, SAVINGS, flat(1_000_000), date("2026-10-20"), date("2026-11-01"))
        assertEquals(12, due.single().days)
        assertEquals(InterestCalculator.accrue(Money(1_000_000), 500, 12), due.single().amount)
    }

    @Test
    fun februaryInALeapYearHas29Days() {
        val due = InterestPlanner.due(fivePercent, SAVINGS, flat(1_000_000), date("2028-02-01"), date("2028-03-01"))
        assertEquals(29, due.single().days)
        val common = InterestPlanner.due(fivePercent, SAVINGS, flat(1_000_000), date("2027-02-01"), date("2027-03-01"))
        assertEquals(28, common.single().days)
    }

    @Test
    fun catchUpCompoundsEachPlannedAccrual() {
        val policy = InterestPolicy(HOUSEHOLD, annualRateBasisPoints = 2000, accrualDay = 15)
        val due = InterestPlanner.due(policy, SAVINGS, flat(1_000_000), date("2026-08-15"), date("2026-11-20"))
        assertEquals(listOf("2026-09", "2026-10", "2026-11"), due.map { it.periodKey })
        assertEquals(listOf(31, 30, 31), due.map { it.days })
        var principal = Money(1_000_000)
        for (accrual in due) {
            assertEquals(principal, accrual.principal)
            assertEquals(InterestCalculator.accrue(principal, 2000, accrual.days), accrual.amount)
            principal += accrual.amount
        }
    }

    @Test
    fun longGapsAreCappedAtEightAccrualsWithoutLosingDays() {
        val plan = InterestPlanner.plan(fivePercent, SAVINGS, flat(1_000_000), date("2025-01-01"), date("2026-11-02"))
        assertEquals(AllowancePlanner.MAX_CATCH_UP_PERIODS, plan.accruals.size)
        assertEquals(
            listOf("2026-04", "2026-05", "2026-06", "2026-07", "2026-08", "2026-09", "2026-10", "2026-11"),
            plan.accruals.map { it.periodKey },
        )
        assertEquals(date("2025-01-01").daysUntil(date("2026-11-01")).toInt(), plan.accruals.sumOf { it.days })
        assertEquals(date("2025-01-01").daysUntil(date("2026-04-01")).toInt(), plan.accruals.first().days)
        assertEquals(date("2026-11-01"), plan.accruedThrough)
    }

    @Test
    fun nothingDueBeforeTheNextAccrualDay() {
        val plan = InterestPlanner.plan(fivePercent, SAVINGS, flat(10_000), date("2026-11-01"), date("2026-11-30"))
        assertEquals(emptyList(), plan.accruals)
        assertNull(plan.accruedThrough)
        // Re-planning on the accrual day itself after accruing it is a no-op.
        assertEquals(
            emptyList(),
            InterestPlanner.due(fivePercent, SAVINGS, flat(10_000), date("2026-11-01"), date("2026-11-01")),
        )
    }

    @Test
    fun zeroRateOrZeroAmountsProduceNoActionsButAdvanceTheBaseline() {
        val off = InterestPlanner.plan(
            InterestPolicy(HOUSEHOLD),
            SAVINGS,
            flat(10_000),
            date("2026-09-01"),
            date("2026-11-02"),
        )
        assertEquals(emptyList(), off.accruals)
        assertEquals(date("2026-11-01"), off.accruedThrough)
        // 1p at 5% for a month rounds to zero.
        val tiny = InterestPlanner.plan(fivePercent, SAVINGS, flat(1), date("2026-10-01"), date("2026-11-02"))
        assertEquals(emptyList(), tiny.accruals)
        assertEquals(date("2026-11-01"), tiny.accruedThrough)
        val overdrawn = InterestPlanner.plan(fivePercent, SAVINGS, flat(-5_000), date("2026-10-01"), date("2026-11-02"))
        assertEquals(emptyList(), overdrawn.accruals)
    }

    @Test
    fun onlySavingsAccountsEarnInterest() {
        val main = Account(MAIN, KID, GBP, AccountKind.MAIN)
        assertFailsWith<IllegalArgumentException> {
            InterestPlanner.due(fivePercent, main, flat(10_000), date("2026-10-01"), date("2026-11-02"))
        }
    }

    @Test
    fun accrueInterestValidation() {
        fun action(bps: Int = 500, days: Int = 30, amount: Long = 10, principal: Long = 100) =
            PendingParentAction.AccrueInterest(
                KID,
                SAVINGS.id,
                GBP,
                Money(principal),
                bps,
                days,
                "2026-11",
                Money(amount),
            )
        action()
        assertFailsWith<IllegalArgumentException> { action(bps = 0) }
        assertFailsWith<IllegalArgumentException> { action(bps = 2001) }
        assertFailsWith<IllegalArgumentException> { action(days = 0) }
        assertFailsWith<IllegalArgumentException> { action(amount = 0) }
        assertFailsWith<IllegalArgumentException> { action(principal = -1) }
    }

    @Test
    fun eachPeriodEarnsOnItsOwnOpeningBalance() {
        // £100 until 15 September, £300 from then on: September earns on £100 (the 1 September balance), October on
        // £300 plus September's interest, which is planned but not yet in the ledger.
        val history: (CalendarDate) -> Money = { day ->
            if (day < date("2026-09-15")) gbp(10_000) else gbp(30_000)
        }
        val asked = mutableListOf<String>()
        val plan = InterestPlanner.plan(
            fivePercent,
            SAVINGS,
            { day ->
                asked += day.toString()
                history(day)
            },
            date("2026-09-01"),
            date("2026-11-02"),
        )
        val (september, october) = plan.accruals
        assertEquals(Money(10_000), september.principal)
        assertEquals(InterestCalculator.accrue(Money(10_000), 500, 30), september.amount)
        assertEquals("2026-10", september.periodKey)
        assertEquals(Money(30_000) + september.amount, october.principal)
        assertEquals(InterestCalculator.accrue(Money(30_000) + september.amount, 500, 31), october.amount)
        assertEquals("2026-11", october.periodKey)
        assertEquals(listOf("2026-09-01", "2026-10-01"), asked, "only period start days are looked up")
    }

    @Test
    fun aDepositDuringAPeriodStartsEarningInTheNextOne() {
        val history: (CalendarDate) -> Money = { day ->
            if (day < date("2026-10-20")) Money.ZERO else gbp(50_000)
        }
        val plan = InterestPlanner.plan(fivePercent, SAVINGS, history, date("2026-10-01"), date("2026-12-01"))
        val november = plan.accruals.single()
        assertEquals("2026-12", november.periodKey)
        assertEquals(Money(50_000), november.principal)
        assertEquals(30, november.days)
        assertEquals(date("2026-12-01"), plan.accruedThrough)
    }

    @Test
    fun foldedCatchUpPeriodsUseTheBalanceAtLastAccruedOn() {
        val history: (CalendarDate) -> Money = { day ->
            if (day == date("2025-01-01")) gbp(1_000) else gbp(1_000_000)
        }
        val plan = InterestPlanner.plan(fivePercent, SAVINGS, history, date("2025-01-01"), date("2026-11-02"))
        assertEquals(AllowancePlanner.MAX_CATCH_UP_PERIODS, plan.accruals.size)
        assertEquals(Money(1_000), plan.accruals.first().principal)
    }
}

package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AllowanceCadence
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.ScheduleId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AllowancePlannerTest {
    private val weekly = allowance(AllowanceCadence.Weekly(DayOfWeek.SATURDAY), startsOn = date("2026-10-01"))
    private val monthly = allowance(AllowanceCadence.Monthly(1), startsOn = date("2026-01-01"))

    private fun keys(actions: List<PendingParentAction.PayAllowance>) = actions.map { it.periodKey }

    @Test
    fun weeklyPaysEachMissedSaturdayWithItsIsoWeek() {
        val due = AllowancePlanner.due(weekly, lastPaidOn = date("2026-10-17"), today = date("2026-11-02"))
        assertEquals(listOf("2026-10-24", "2026-10-31"), due.map { it.dueOn.toString() })
        assertEquals(listOf("2026-W43", "2026-W44"), keys(due))
        val first = due.first()
        assertEquals(ScheduleId("a1"), first.scheduleId)
        assertEquals(KID, first.kidId)
        assertEquals(MAIN, first.accountId)
        assertEquals(Money(500), first.amount)
        assertEquals(GBP, first.currency)
        assertEquals("allowance/a1/2026-W43", first.key)
    }

    @Test
    fun monthlyUsesYearMonthKeys() {
        val due = AllowancePlanner.due(monthly, lastPaidOn = date("2026-09-01"), today = date("2026-11-02"))
        assertEquals(listOf("2026-10", "2026-11"), keys(due))
        assertEquals(listOf("2026-10-01", "2026-11-01"), due.map { it.dueOn.toString() })
    }

    @Test
    fun firstPaymentStartsAtStartsOnAndIncludesToday() {
        val due = AllowancePlanner.due(weekly, lastPaidOn = null, today = date("2026-10-10"))
        assertEquals(listOf("2026-10-03", "2026-10-10"), due.map { it.dueOn.toString() })
        assertEquals(emptyList(), AllowancePlanner.due(weekly, lastPaidOn = null, today = date("2026-09-30")))
        assertEquals(emptyList(), AllowancePlanner.due(weekly, lastPaidOn = null, today = date("2026-10-02")))
    }

    @Test
    fun replanningIsIdempotent() {
        val today = date("2026-11-02")
        val first = AllowancePlanner.due(monthly, date("2026-09-01"), today)
        assertEquals(first, AllowancePlanner.due(monthly, date("2026-09-01"), today))
        // After paying, nothing is due until the next period.
        assertEquals(emptyList(), AllowancePlanner.due(monthly, first.last().dueOn, today))
        assertEquals(emptyList(), AllowancePlanner.due(monthly, first.last().dueOn, date("2026-11-30")))
        assertEquals(listOf("2026-12"), keys(AllowancePlanner.due(monthly, first.last().dueOn, date("2026-12-01"))))
    }

    @Test
    fun catchUpIsCappedAtEightPeriodsAndReportsTheSkippedOnes() {
        val plan = AllowancePlanner.plan(monthly, lastPaidOn = null, today = date("2026-12-15"))
        assertEquals(AllowancePlanner.MAX_CATCH_UP_PERIODS, plan.due.size)
        assertEquals(
            listOf("2026-05", "2026-06", "2026-07", "2026-08", "2026-09", "2026-10", "2026-11", "2026-12"),
            keys(plan.due),
        )
        assertEquals(listOf("2026-01-01", "2026-02-01", "2026-03-01", "2026-04-01"), plan.skipped.map { it.toString() })
        assertEquals(plan.due, AllowancePlanner.due(monthly, null, date("2026-12-15")))
    }

    @Test
    fun exactlyEightMissedPeriodsAreAllPaid() {
        val plan = AllowancePlanner.plan(monthly, lastPaidOn = null, today = date("2026-08-01"))
        assertEquals(8, plan.due.size)
        assertEquals(emptyList(), plan.skipped)
    }

    @Test
    fun weeklyCatchUpAcrossAYearBoundaryUsesWeekBasedYears() {
        val schedule = allowance(AllowanceCadence.Weekly(DayOfWeek.MONDAY), startsOn = date("2020-01-01"))
        val plan = AllowancePlanner.plan(schedule, lastPaidOn = date("2020-11-30"), today = date("2021-01-11"))
        assertEquals(
            listOf(
                "2020-W50",
                "2020-W51",
                "2020-W52",
                "2020-W53",
                "2021-W01",
                "2021-W02",
            ),
            keys(plan.due),
        )
        // 2020-12-28 is in 2020-W53 and 2021-01-04 starts 2021-W01.
        assertEquals("2020-12-28", plan.due[3].dueOn.toString())
        assertEquals(emptyList(), plan.skipped)
        val long = AllowancePlanner.plan(schedule, lastPaidOn = null, today = date("2021-01-11"))
        assertEquals(8, long.due.size)
        assertEquals(52 + 2 - 8, long.skipped.size)
        assertEquals(keys(plan.due), keys(long.due).takeLast(6))
    }

    @Test
    fun keysAreUniquePerPeriod() {
        val schedule = allowance(AllowanceCadence.Weekly(DayOfWeek.SUNDAY), startsOn = date("2000-01-01"))
        var lastPaid: CalendarDate? = null
        val all = mutableListOf<String>()
        var today = date("2000-01-01")
        while (today < date("2030-01-01")) {
            val due = AllowancePlanner.due(schedule, lastPaid, today)
            all += keys(due)
            lastPaid = due.lastOrNull()?.dueOn ?: lastPaid
            today = today.plusDays(1)
        }
        assertEquals(all.size, all.toSet().size)
        assertTrue(all.size > 1500)
    }

    @Test
    fun inactiveSchedulesPayNothing() {
        val paused = allowance(AllowanceCadence.Monthly(1), active = false)
        assertEquals(AllowancePlan(emptyList(), emptyList()), AllowancePlanner.plan(paused, null, date("2026-11-02")))
        assertNull(AllowancePlanner.nextDueDate(paused, date("2026-11-02")))
    }

    @Test
    fun nextDueDateRespectsStartsOn() {
        assertEquals(date("2026-10-03"), AllowancePlanner.nextDueDate(weekly, date("2026-01-01")))
        assertEquals(date("2026-11-07"), AllowancePlanner.nextDueDate(weekly, date("2026-11-02")))
        assertEquals(date("2026-11-14"), AllowancePlanner.nextDueDate(weekly, date("2026-11-07")))
    }

    @Test
    fun minutesAllowancesAreAllowed() {
        val minutes = allowance(AllowanceCadence.Monthly(1), currency = Currency.Minutes, amount = 60)
        assertEquals(Currency.Minutes, AllowancePlanner.due(minutes, null, date("2026-01-01")).single().currency)
    }

    @Test
    fun scheduleAndActionValidation() {
        assertFailsWith<IllegalArgumentException> { allowance(AllowanceCadence.Monthly(1), amount = 0) }
        assertFailsWith<IllegalArgumentException> {
            PendingParentAction.PayAllowance(ScheduleId("a"), KID, MAIN, Money(0), GBP, "2026-11", date("2026-11-01"))
        }
    }
}

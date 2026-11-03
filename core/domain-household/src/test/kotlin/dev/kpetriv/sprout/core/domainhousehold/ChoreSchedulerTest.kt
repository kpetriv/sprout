package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.ChoreState
import dev.kpetriv.sprout.core.model.DayOfWeek
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChoreSchedulerTest {
    private fun dates(chore: Chore, from: String, to: String) =
        ChoreScheduler.occurrences(chore, date(from), date(to)).map { it.dueOn.toString() }

    @Test
    fun onceWithADateOccursOnlyOnThatDate() {
        val chore = chore(ChoreSchedule.Once(date("2026-11-10")))
        assertEquals(listOf("2026-11-10"), dates(chore, "2026-11-01", "2026-11-30"))
        assertEquals(listOf("2026-11-10"), dates(chore, "2026-11-10", "2026-11-10"))
        assertEquals(emptyList(), dates(chore, "2026-11-11", "2026-12-31"))
        assertEquals(date("2026-11-10"), ChoreScheduler.nextDue(chore, date("2026-11-09"))?.dueOn)
        assertNull(ChoreScheduler.nextDue(chore, date("2026-11-10")))
    }

    @Test
    fun onceWithoutADateIsOffTheCalendarButAlwaysSubmittable() {
        val chore = chore(ChoreSchedule.Once())
        assertEquals(emptyList(), dates(chore, "2026-01-01", "2026-12-31"))
        assertNull(ChoreScheduler.nextDue(chore, date("2026-01-01")))
        assertTrue(ChoreScheduler.isDueOn(chore, date("2026-03-04")))
    }

    @Test
    fun anAnyTimeChoreHasOneOccurrenceKeyItsUtcCreationDate() {
        val chore = chore(ChoreSchedule.Once())
        // CREATED_AT is 2026-09-21T14:13:20Z.
        assertEquals(date("2026-09-21"), ChoreScheduler.anyTimeDueOn(chore))
        assertEquals(date("2026-09-21"), ChoreScheduler.occurrenceDate(chore, date("2026-11-03")))
        assertEquals(date("2026-09-21"), ChoreScheduler.occurrenceDate(chore, date("2027-01-01")))
        val weekly = chore(ChoreSchedule.Weekly(DayOfWeek.MONDAY))
        assertEquals(date("2026-11-02"), ChoreScheduler.occurrenceDate(weekly, date("2026-11-02")))
    }

    @Test
    fun dailyOnChosenWeekdaysAcrossAYearEnd() {
        val chore = chore(ChoreSchedule.Daily(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)))
        // 2026-12-28 is a Monday.
        assertEquals(
            listOf("2026-12-28", "2026-12-30", "2027-01-01", "2027-01-04", "2027-01-06"),
            dates(chore, "2026-12-27", "2027-01-06"),
        )
    }

    @Test
    fun everyDayIncludesTheLeapDay() {
        val chore = chore(ChoreSchedule.Daily.EVERY_DAY)
        assertEquals(listOf("2028-02-28", "2028-02-29", "2028-03-01"), dates(chore, "2028-02-28", "2028-03-01"))
        assertEquals(listOf("2027-02-28", "2027-03-01"), dates(chore, "2027-02-28", "2027-03-01"))
        assertEquals(366, ChoreScheduler.occurrences(chore, date("2028-01-01"), date("2028-12-31")).size)
    }

    @Test
    fun weeklyStepsSevenDaysAcrossMonthAndYearEnds() {
        val chore = chore(ChoreSchedule.Weekly(DayOfWeek.SUNDAY))
        assertEquals(
            listOf("2026-12-20", "2026-12-27", "2027-01-03", "2027-01-10"),
            dates(chore, "2026-12-15", "2027-01-15"),
        )
        // from on the weekday itself is included.
        assertEquals(listOf("2026-11-08"), dates(chore, "2026-11-08", "2026-11-14"))
        assertEquals(52, ChoreScheduler.occurrences(chore, date("2026-01-01"), date("2026-12-31")).size)
        assertEquals(date("2026-11-15"), ChoreScheduler.nextDue(chore, date("2026-11-08"))?.dueOn)
    }

    @Test
    fun monthlyOnTheTwentyEighthIncludingFebruary() {
        val chore = chore(ChoreSchedule.Monthly(28))
        assertEquals(
            listOf("2026-11-28", "2026-12-28", "2027-01-28", "2027-02-28", "2027-03-28"),
            dates(chore, "2026-11-28", "2027-03-31"),
        )
        assertEquals(listOf("2028-02-28"), dates(chore, "2028-02-01", "2028-02-29"))
    }

    @Test
    fun monthlyStartsNextMonthWhenTheDayHasPassed() {
        val chore = chore(ChoreSchedule.Monthly(1))
        assertEquals(listOf("2026-12-01", "2027-01-01"), dates(chore, "2026-11-02", "2027-01-01"))
        assertEquals(date("2027-01-01"), ChoreScheduler.nextDue(chore, date("2026-12-01"))?.dueOn)
    }

    @Test
    fun onlyActiveChoresHaveOccurrences() {
        for (state in listOf(ChoreState.PROPOSED, ChoreState.ARCHIVED)) {
            val chore = chore(ChoreSchedule.Daily.EVERY_DAY, state = state)
            assertEquals(emptyList(), dates(chore, "2026-01-01", "2026-12-31"))
            assertNull(ChoreScheduler.nextDue(chore, date("2026-01-01")))
            assertFalse(ChoreScheduler.isDueOn(chore, date("2026-01-02")))
            assertFalse(ChoreScheduler.isDueOn(chore(ChoreSchedule.Once(), state = state), date("2026-01-02")))
        }
    }

    @Test
    fun emptyOrInvertedRangesHaveNoOccurrences() {
        assertEquals(emptyList(), dates(chore(ChoreSchedule.Daily.EVERY_DAY), "2026-11-02", "2026-11-01"))
    }

    @Test
    fun schedulesStopAtTheEndOfTheCalendar() {
        val daily = chore(ChoreSchedule.Daily.EVERY_DAY)
        assertNull(ChoreScheduler.nextDue(daily, CalendarDate.MAX))
        assertEquals(listOf("9999-12-30", "9999-12-31"), dates(daily, "9999-12-30", "9999-12-31"))
        // 9999-12-31 is a Friday: the next Saturday does not exist.
        assertNull(ChoreScheduler.nextDue(chore(ChoreSchedule.Weekly(DayOfWeek.SATURDAY)), date("9999-12-25")))
        assertNull(ChoreScheduler.nextDue(chore(ChoreSchedule.Monthly(5)), date("9999-12-05")))
    }

    @Test
    fun isDueOnMatchesTheSchedule() {
        val weekly = chore(ChoreSchedule.Weekly(DayOfWeek.MONDAY))
        assertTrue(ChoreScheduler.isDueOn(weekly, date("2026-11-02")))
        assertFalse(ChoreScheduler.isDueOn(weekly, date("2026-11-03")))
        assertTrue(ChoreScheduler.isDueOn(chore(ChoreSchedule.Monthly(15)), date("2027-02-15")))
        assertFalse(ChoreScheduler.isDueOn(chore(ChoreSchedule.Monthly(15)), date("2027-02-16")))
    }

    /** Property: walking nextDue from just before `from` yields exactly `occurrences(from, to)`. */
    @Test
    fun occurrencesAgreeWithRepeatedNextDue() {
        val random = Random(seed = 2026)
        repeat(500) {
            val chore = chore(randomSchedule(random))
            val from = CalendarDate.fromEpochDay(
                random.nextLong(date("1999-01-01").toEpochDay(), date("2040-01-01").toEpochDay()),
            )
            val to = from.plusDays(random.nextInt(-5, 800))
            val expected = ChoreScheduler.occurrences(chore, from, to)
            val walked = generateSequence(ChoreScheduler.nextDue(chore, from.plusDays(-1))) {
                ChoreScheduler.nextDue(chore, it.dueOn)
            }.takeWhile { it.dueOn <= to }.toList()
            assertEquals(expected, walked, "schedule ${chore.schedule} from $from to $to")
            assertTrue(expected.all { it.dueOn in from..to && ChoreScheduler.isDueOn(chore, it.dueOn) })
            assertEquals(expected.sortedBy { it.dueOn }.distinct(), expected)
        }
    }

    private fun randomSchedule(random: Random): ChoreSchedule = when (random.nextInt(4)) {
        0 -> ChoreSchedule.Once(date("2020-01-01").plusDays(random.nextInt(0, 7000)))

        1 -> ChoreSchedule.Daily(
            DayOfWeek.entries.filter {
                random.nextBoolean()
            }.toSet().ifEmpty { setOf(DayOfWeek.TUESDAY) },
        )

        2 -> ChoreSchedule.Weekly(DayOfWeek.entries.random(random))

        else -> ChoreSchedule.Monthly(random.nextInt(1, 29))
    }
}

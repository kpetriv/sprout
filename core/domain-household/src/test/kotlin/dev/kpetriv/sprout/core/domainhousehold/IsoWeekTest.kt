package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.domainledger.PeriodKeys
import dev.kpetriv.sprout.core.model.CalendarDate
import java.time.LocalDate
import java.time.temporal.IsoFields
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IsoWeekTest {
    private fun week(text: String) = IsoWeek.of(date(text)).toString()

    @Test
    fun knownWeeks() {
        assertEquals("2026-W45", week("2026-11-02"))
        assertEquals("2026-W45", week("2026-11-08"))
        assertEquals("2026-W46", week("2026-11-09"))
        assertEquals("2026-W01", week("2026-01-01"))
        assertEquals("1900-W01", week("1900-01-01"))
        assertEquals("9999-W52", week("9999-12-31"))
    }

    @Test
    fun yearBoundaries() {
        // A late-December Monday in week 1 of the next year.
        assertEquals("2025-W01", week("2024-12-30"))
        assertEquals("2009-W01", week("2008-12-29"))
        // Early-January days in week 53 of the previous year.
        assertEquals("2020-W53", week("2020-12-31"))
        assertEquals("2020-W53", week("2021-01-03"))
        assertEquals("2021-W01", week("2021-01-04"))
        assertEquals("2015-W53", week("2016-01-03"))
        assertEquals("2009-W53", week("2010-01-03"))
        assertEquals("2026-W53", week("2027-01-01"))
        assertEquals("2026-W53", week("2026-12-31"))
    }

    @Test
    fun matchesJavaTimeForEveryDayFrom1990To2060() {
        var expected = LocalDate.of(1990, 1, 1)
        var current = CalendarDate(1990, 1, 1)
        while (expected.year < 2060) {
            val iso = IsoWeek.of(current)
            assertEquals(expected.get(IsoFields.WEEK_BASED_YEAR), iso.weekBasedYear, "$current")
            assertEquals(expected.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), iso.week, "$current")
            expected = expected.plusDays(1)
            current = current.plusDays(1)
        }
    }

    @Test
    fun periodKeys() {
        assertEquals("2026-W45", date("2026-11-05").weeklyPeriodKey())
        assertEquals("2026-W53", date("2027-01-01").weeklyPeriodKey())
        assertEquals("2026-11", date("2026-11-05").monthlyPeriodKey())
        assertEquals("2027-01", date("2027-01-01").monthlyPeriodKey())
        // Keys match the ledger's own formatter and pass its validation.
        val iso = IsoWeek.of(date("2027-01-01"))
        assertEquals(PeriodKeys.weekly(iso.weekBasedYear, iso.week), date("2027-01-01").weeklyPeriodKey())
        assertEquals(PeriodKeys.monthly(2027, 1), date("2027-01-01").monthlyPeriodKey())
        var day = date("2019-12-01")
        while (day < date("2032-01-31")) {
            assertTrue(PeriodKeys.isValid(day.weeklyPeriodKey()) && PeriodKeys.isValid(day.monthlyPeriodKey()), "$day")
            day = day.plusDays(1)
        }
    }

    @Test
    fun ordersAndValidates() {
        assertTrue(IsoWeek(2026, 53) < IsoWeek(2027, 1))
        assertFailsWith<IllegalArgumentException> { IsoWeek(2026, 0) }
        assertFailsWith<IllegalArgumentException> { IsoWeek(2026, 54) }
    }
}

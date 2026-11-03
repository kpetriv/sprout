package dev.kpetriv.sprout.core.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalendarMathTest {
    private fun d(text: String) = CalendarDate.parse(text)

    @Test
    fun epochDaysOfKnownDates() {
        assertEquals(0, d("1970-01-01").toEpochDay())
        assertEquals(-1, d("1969-12-31").toEpochDay())
        assertEquals(11_016, d("2000-02-29").toEpochDay())
        assertEquals(20_759, d("2026-11-02").toEpochDay())
        assertEquals(-25_567, CalendarDate.MIN.toEpochDay())
        assertEquals(2_932_896, CalendarDate.MAX.toEpochDay())
        assertEquals(d("2026-11-02"), CalendarDate.fromEpochDay(20_759))
    }

    @Test
    fun daysOfWeekOfKnownDates() {
        assertEquals(DayOfWeek.THURSDAY, d("1970-01-01").dayOfWeek)
        assertEquals(DayOfWeek.MONDAY, d("1900-01-01").dayOfWeek)
        assertEquals(DayOfWeek.TUESDAY, d("2000-02-29").dayOfWeek)
        assertEquals(DayOfWeek.MONDAY, d("2026-11-02").dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, d("2026-11-08").dayOfWeek)
        assertEquals(DayOfWeek.FRIDAY, CalendarDate.MAX.dayOfWeek)
    }

    @Test
    fun everyDateMatchesJavaTimeFrom1900To2101() {
        var expected = LocalDate.of(1900, 1, 1)
        var date = CalendarDate.MIN
        while (expected.year <= 2100) {
            assertEquals(expected.toEpochDay(), date.toEpochDay(), "epoch day of $date")
            assertEquals(expected.dayOfWeek.value, date.dayOfWeek.isoNumber, "day of week of $date")
            assertEquals(expected.dayOfYear, date.dayOfYear, "day of year of $date")
            assertEquals(expected.lengthOfMonth(), date.daysInMonth, "days in month of $date")
            assertEquals(expected.isLeapYear, date.isLeapYear, "leap year of $date")
            expected = expected.plusDays(1)
            val next = date.plusDays(1)
            assertEquals(expected.toString(), next.toString())
            date = next
        }
    }

    @Test
    fun randomEpochDaysRoundTripAcrossTheWholeRange() {
        val random = Random(seed = 45)
        repeat(20_000) {
            val epochDay = random.nextLong(CalendarDate.MIN.toEpochDay(), CalendarDate.MAX.toEpochDay() + 1)
            val date = CalendarDate.fromEpochDay(epochDay)
            assertEquals(LocalDate.ofEpochDay(epochDay).toString(), date.toString())
            assertEquals(epochDay, date.toEpochDay())
        }
    }

    @Test
    fun plusDaysCrossesMonthYearAndLeapDay() {
        assertEquals(d("2026-02-01"), d("2026-01-31").plusDays(1))
        assertEquals(d("2027-01-01"), d("2026-12-31").plusDays(1))
        assertEquals(d("2024-02-29"), d("2024-02-28").plusDays(1))
        assertEquals(d("2025-03-01"), d("2025-02-28").plusDays(1))
        assertEquals(d("2023-12-31"), d("2024-01-01").plusDays(-1))
        assertEquals(d("2025-02-28"), d("2024-02-29").plusDays(365))
        assertEquals(d("2100-03-01"), d("2100-02-28").plusDays(1))
        assertEquals(d("2000-02-29"), d("2000-02-28").plusDays(1L))
        assertEquals(366, d("2024-01-01").daysUntil(d("2025-01-01")))
        assertEquals(-365, d("2026-01-01").daysUntil(d("2025-01-01")))
        val from = LocalDate.of(2001, 9, 9)
        val to = LocalDate.of(2042, 3, 17)
        assertEquals(ChronoUnit.DAYS.between(from, to), d("2001-09-09").daysUntil(d("2042-03-17")))
    }

    @Test
    fun plusDaysRejectsDatesOutsideTheRange() {
        assertFailsWith<IllegalArgumentException> { CalendarDate.MAX.plusDays(1) }
        assertFailsWith<IllegalArgumentException> { CalendarDate.MIN.plusDays(-1) }
        assertFailsWith<IllegalArgumentException> { CalendarDate.MIN.plusDays(Long.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { CalendarDate.MAX.plusDays(Long.MIN_VALUE) }
        assertFailsWith<IllegalArgumentException> { CalendarDate.fromEpochDay(CalendarDate.MAX.toEpochDay() + 1) }
    }

    @Test
    fun leapYearsFollowTheGregorianRules() {
        assertTrue(CalendarDate.isLeapYear(2024))
        assertTrue(CalendarDate.isLeapYear(2000))
        assertFalse(CalendarDate.isLeapYear(1900))
        assertFalse(CalendarDate.isLeapYear(2100))
        assertFalse(CalendarDate.isLeapYear(2026))
        assertTrue(d("2028-07-01").isLeapYear)
        assertEquals(29, d("2024-02-10").daysInMonth)
        assertEquals(28, d("2026-02-10").daysInMonth)
        assertEquals(30, d("2026-11-02").daysInMonth)
        assertEquals(31, d("2026-12-02").daysInMonth)
        assertEquals(366, d("2024-12-31").dayOfYear)
        assertEquals(365, d("2026-12-31").dayOfYear)
    }

    @Test
    fun yearMonthArithmetic() {
        assertEquals(YearMonth(2027, 1), YearMonth(2026, 12).plusMonths(1))
        assertEquals(YearMonth(2025, 12), YearMonth(2026, 1).plusMonths(-1))
        assertEquals(YearMonth(2028, 2), YearMonth(2026, 11).plusMonths(15))
        assertEquals(YearMonth(2026, 11), YearMonth(2026, 11).plusMonths(0))
        assertEquals(29, YearMonth(2024, 2).daysInMonth)
        assertEquals(d("2026-11-28"), YearMonth(2026, 11).atDay(28))
        assertFailsWith<IllegalArgumentException> { YearMonth(9999, 12).plusMonths(1) }
        assertFailsWith<IllegalArgumentException> { YearMonth(1900, 1).plusMonths(-1) }
        assertFailsWith<IllegalArgumentException> { YearMonth(2026, 2).atDay(29) }
    }

    @Test
    fun dayOfWeekArithmetic() {
        assertEquals(1, DayOfWeek.MONDAY.isoNumber)
        assertEquals(7, DayOfWeek.SUNDAY.isoNumber)
        assertEquals(DayOfWeek.MONDAY, DayOfWeek.SUNDAY.plus(1))
        assertEquals(DayOfWeek.SUNDAY, DayOfWeek.MONDAY.plus(-1))
        assertEquals(DayOfWeek.WEDNESDAY, DayOfWeek.WEDNESDAY.plus(14))
        assertEquals(DayOfWeek.FRIDAY, DayOfWeek.ofIso(5))
        assertFailsWith<IllegalArgumentException> { DayOfWeek.ofIso(0) }
        assertFailsWith<IllegalArgumentException> { DayOfWeek.ofIso(8) }
    }
}

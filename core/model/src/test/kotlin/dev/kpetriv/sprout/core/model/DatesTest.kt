package dev.kpetriv.sprout.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DatesTest {
    @Test
    fun yearMonthRoundTripsThroughText() {
        assertEquals("2019-03", YearMonth(2019, 3).toString())
        assertEquals(YearMonth(2019, 3), YearMonth.parse("2019-03"))
        assertEquals("\"2019-03\"", Json.encodeToString(YearMonth.serializer(), YearMonth(2019, 3)))
        assertEquals(YearMonth(2019, 12), Json.decodeFromString(YearMonth.serializer(), "\"2019-12\""))
    }

    @Test
    fun yearMonthRejectsBadInput() {
        assertFailsWith<IllegalArgumentException> { YearMonth(2019, 0) }
        assertFailsWith<IllegalArgumentException> { YearMonth(2019, 13) }
        assertFailsWith<IllegalArgumentException> { YearMonth(1899, 1) }
        assertFailsWith<IllegalArgumentException> { YearMonth.parse("2019-3") }
        assertFailsWith<IllegalArgumentException> { YearMonth.parse("2019-03-01") }
    }

    @Test
    fun yearsUntilCountsTheBirthMonthAsReached() {
        val birth = YearMonth(2018, 6)
        assertEquals(7, birth.yearsUntil(YearMonth(2026, 5)))
        assertEquals(8, birth.yearsUntil(YearMonth(2026, 6)))
        assertEquals(8, birth.yearsUntil(YearMonth(2026, 7)))
        assertEquals(0, birth.yearsUntil(birth))
    }

    @Test
    fun yearMonthOrdersChronologically() {
        assertTrue(YearMonth(2025, 12) < YearMonth(2026, 1))
    }

    @Test
    fun calendarDateValidatesDaysPerMonth() {
        CalendarDate(2024, 2, 29)
        assertFailsWith<IllegalArgumentException> { CalendarDate(2025, 2, 29) }
        assertFailsWith<IllegalArgumentException> { CalendarDate(1900, 2, 29) }
        CalendarDate(2000, 2, 29)
        assertFailsWith<IllegalArgumentException> { CalendarDate(2026, 4, 31) }
        CalendarDate(2026, 1, 31)
        assertFailsWith<IllegalArgumentException> { CalendarDate(2026, 1, 0) }
    }

    @Test
    fun calendarDateRoundTripsThroughText() {
        val date = CalendarDate(2026, 11, 2)
        assertEquals("2026-11-02", date.toString())
        assertEquals(date, CalendarDate.parse("2026-11-02"))
        assertEquals(
            date,
            Json.decodeFromString(CalendarDate.serializer(), Json.encodeToString(CalendarDate.serializer(), date)),
        )
        assertEquals(YearMonth(2026, 11), date.yearMonth)
        assertTrue(CalendarDate(2026, 1, 31) < CalendarDate(2026, 2, 1))
        assertFailsWith<IllegalArgumentException> { CalendarDate.parse("2026-11-2") }
    }
}

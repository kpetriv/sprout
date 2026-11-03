package dev.kpetriv.sprout.core.time

import dev.kpetriv.sprout.core.model.CalendarDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class HouseholdCalendarTest {
    private val lateEvening = Instant.parse("2026-11-03T23:30:00Z")

    @Test
    fun todayIsTheClocksDateInTheHouseholdZone() {
        val clock = FakeClock(lateEvening)
        assertEquals(CalendarDate(2026, 11, 3), HouseholdCalendar(clock) { ZoneOffset.UTC }.today())
        assertEquals(CalendarDate(2026, 11, 4), HouseholdCalendar(clock) { ZoneId.of("Europe/Sofia") }.today())
        assertEquals(CalendarDate(2026, 11, 3), HouseholdCalendar(clock) { ZoneId.of("America/New_York") }.today())
    }

    @Test
    fun todayUpdatesEmitsAgainAfterMidnight() = runTest {
        // A clock that moves with the test's virtual time.
        val start = lateEvening.toEpochMilliseconds()
        val clock = object : Clock {
            override fun now() = Instant.fromEpochMilliseconds(start + testScheduler.currentTime)
        }
        val dates = mutableListOf<CalendarDate>()
        val job = launch { HouseholdCalendar(clock) { ZoneOffset.UTC }.todayUpdates().toList(dates) }

        runCurrent()
        assertEquals(listOf(CalendarDate(2026, 11, 3)), dates)
        advanceTimeBy(29.minutes)
        assertEquals(listOf(CalendarDate(2026, 11, 3)), dates)
        advanceTimeBy(2.minutes)
        assertEquals(listOf(CalendarDate(2026, 11, 3), CalendarDate(2026, 11, 4)), dates)
        advanceTimeBy(24.hours)
        assertEquals(CalendarDate(2026, 11, 5), dates.last())
        job.cancel()
    }
}

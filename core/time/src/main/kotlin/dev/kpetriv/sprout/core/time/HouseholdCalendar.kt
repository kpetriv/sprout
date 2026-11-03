package dev.kpetriv.sprout.core.time

import dev.kpetriv.sprout.core.model.CalendarDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import java.time.ZoneId
import kotlin.time.Instant

/**
 * Calendar dates in the household's time zone: what "today" is for chores, allowances, interest and ages.
 *
 * The one place that turns [clock]'s instant into a [CalendarDate], so every screen and use case agrees on the date.
 * The zone is the device's until households store their own (sync, M3); [zone] is read on every call, so a time zone
 * change on the device applies at once. Bound as a singleton in `core/common`; tests construct it with a fixed zone.
 */
class HouseholdCalendar(private val clock: Clock, private val zone: () -> ZoneId = ZoneId::systemDefault) {
    /** The household's time zone now. */
    fun zone(): ZoneId = zone.invoke()

    /** Today's household-local date. */
    fun today(): CalendarDate = dateOf(clock.now())

    /** The household-local date [instant] falls on. */
    fun dateOf(instant: Instant): CalendarDate {
        val date = java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()).atZone(zone()).toLocalDate()
        return CalendarDate(date.year, date.monthValue, date.dayOfMonth)
    }

    /**
     * [today] now and again after every household-local midnight, without repeats: a screen that stays open overnight
     * moves to the new day. Sleeps until the next midnight (at least [MIN_TICK_MILLIS], so a clock that does not move
     * cannot spin) and re-reads the clock and zone when it wakes, so a changed clock or zone is picked up then.
     */
    fun todayUpdates(): Flow<CalendarDate> = flow {
        while (true) {
            emit(today())
            delay(millisUntilNextMidnight().coerceAtLeast(MIN_TICK_MILLIS))
        }
    }.distinctUntilChanged()

    private fun millisUntilNextMidnight(): Long {
        val zone = zone()
        val now = java.time.Instant.ofEpochMilli(clock.now().toEpochMilliseconds())
        val nextMidnight = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        return nextMidnight.toEpochMilli() - now.toEpochMilli()
    }

    private companion object {
        const val MIN_TICK_MILLIS = 1_000L
    }
}

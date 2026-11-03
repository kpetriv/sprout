package dev.kpetriv.sprout.feature.timebank

import dev.kpetriv.sprout.core.time.Clock
import dev.kpetriv.sprout.core.time.HouseholdCalendar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.ZoneId
import javax.inject.Inject
import kotlin.time.Instant

/**
 * The default [TimeBankClock]: [Clock] for the instant, [HouseholdCalendar] for the zone, and a ticker that wakes on
 * each whole second of the wall clock (at least [MIN_TICK_MILLIS] apart, so a clock that does not move cannot spin).
 */
class HouseholdTimeBankClock @Inject constructor(private val clock: Clock, private val calendar: HouseholdCalendar) :
    TimeBankClock {
    override fun now(): Instant = clock.now()

    override fun zone(): ZoneId = calendar.zone()

    override fun ticks(): Flow<Instant> = flow {
        while (true) {
            val now = clock.now()
            emit(now)
            val untilNextSecond = MILLIS_PER_SECOND - now.toEpochMilliseconds().mod(MILLIS_PER_SECOND)
            delay(untilNextSecond.coerceAtLeast(MIN_TICK_MILLIS))
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val MIN_TICK_MILLIS = 200L
    }
}

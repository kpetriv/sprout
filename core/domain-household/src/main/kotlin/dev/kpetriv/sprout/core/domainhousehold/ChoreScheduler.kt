package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreOccurrence
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.ChoreState
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.YearMonth

/**
 * Expands a chore's [ChoreSchedule] into due dates. Pure calendar maths on household-local [CalendarDate]s, so there
 * is no time zone or DST to get wrong. Only [ChoreState.ACTIVE] chores have occurrences; a proposed or archived chore
 * has none. `Once(null)` ("any time") is off the calendar and also has none: the kid submits it on any date, but it has
 * exactly one occurrence key, [anyTimeDueOn], so it is submitted, approved and paid once ([occurrenceDate]).
 */
object ChoreScheduler {
    /** Every occurrence of [chore] from [from] to [to], both inclusive, in date order. Empty if [to] < [from]. */
    fun occurrences(chore: Chore, from: CalendarDate, to: CalendarDate): List<ChoreOccurrence> {
        if (chore.state != ChoreState.ACTIVE || to < from) return emptyList()
        return dueDates(chore.schedule, from).takeWhile { it <= to }.map { ChoreOccurrence(chore.id, it) }.toList()
    }

    /** The first occurrence of [chore] strictly after [after], or null if there is none. */
    fun nextDue(chore: Chore, after: CalendarDate): ChoreOccurrence? {
        if (chore.state != ChoreState.ACTIVE || after == CalendarDate.MAX) return null
        return dueDates(chore.schedule, after.plusDays(1)).firstOrNull()?.let { ChoreOccurrence(chore.id, it) }
    }

    /**
     * Whether [chore] can be submitted on [date]: [date] is a due date. `Once(null)` accepts any date (its submission
     * is keyed on [anyTimeDueOn] whatever the date, see [occurrenceDate]); non-active chores accept none.
     */
    fun isDueOn(chore: Chore, date: CalendarDate): Boolean = when {
        chore.state != ChoreState.ACTIVE -> false
        chore.schedule == ChoreSchedule.Once(null) -> true
        else -> dueDates(chore.schedule, date).firstOrNull() == date
    }

    /**
     * The one occurrence key of an "any time" chore (`Once(null)`): the UTC calendar date of [Chore.createdAt]. UTC,
     * not the household zone, so every device derives the same key from the synced chore whatever its time zone; it
     * is an identity key for submission ids and `ChoreRewarded` period claims, never shown as a deadline.
     */
    fun anyTimeDueOn(chore: Chore): CalendarDate =
        CalendarDate.fromEpochDay(chore.createdAt.toEpochMilliseconds().floorDiv(MILLIS_PER_DAY))

    /**
     * The due date that keys the occurrence of [chore] done on [date]: [date] itself for a scheduled chore, and
     * [anyTimeDueOn] for `Once(null)`, so an any-time chore has a single submission id and a single period claim and
     * pays once however many days it is submitted on.
     */
    fun occurrenceDate(chore: Chore, date: CalendarDate): CalendarDate =
        if (chore.schedule == ChoreSchedule.Once(null)) anyTimeDueOn(chore) else date

    private const val MILLIS_PER_DAY = 86_400_000L

    /** Due dates of [schedule] on or after [from], ascending, ending at [CalendarDate.MAX]. */
    internal fun dueDates(schedule: ChoreSchedule, from: CalendarDate): Sequence<CalendarDate> = when (schedule) {
        is ChoreSchedule.Once -> listOfNotNull(schedule.dueOn?.takeIf { it >= from }).asSequence()
        is ChoreSchedule.Daily -> daysFrom(from).filter { it.dayOfWeek in schedule.daysOfWeek }
        is ChoreSchedule.Weekly -> weekly(schedule.dayOfWeek, from)
        is ChoreSchedule.Monthly -> monthly(schedule.dayOfMonth, from)
    }

    /** Every [dayOfWeek] on or after [from]. */
    internal fun weekly(dayOfWeek: DayOfWeek, from: CalendarDate): Sequence<CalendarDate> {
        val offset = (dayOfWeek.ordinal - from.dayOfWeek.ordinal).mod(DayOfWeek.DAYS_PER_WEEK)
        val first = from.plusDaysOrNull(offset.toLong()) ?: return emptySequence()
        return generateSequence(first) { it.plusDaysOrNull(DayOfWeek.DAYS_PER_WEEK.toLong()) }
    }

    /** Day [dayOfMonth] (1..28, so every month has it) of every month, on or after [from]. */
    internal fun monthly(dayOfMonth: Int, from: CalendarDate): Sequence<CalendarDate> {
        val firstMonth = if (from.day <= dayOfMonth) from.yearMonth else from.yearMonth.nextOrNull()
        return generateSequence(firstMonth) { it.nextOrNull() }.map { it.atDay(dayOfMonth) }
    }

    private fun daysFrom(from: CalendarDate): Sequence<CalendarDate> = generateSequence(from) { it.plusDaysOrNull(1) }
}

/** The date [days] later, or null past [CalendarDate.MAX]. */
internal fun CalendarDate.plusDaysOrNull(days: Long): CalendarDate? =
    if (days > daysUntil(CalendarDate.MAX)) null else plusDays(days)

/** The following month, or null after December of `YearMonth.MAX_YEAR`. */
internal fun YearMonth.nextOrNull(): YearMonth? = if (this == CalendarDate.MAX.yearMonth) null else plusMonths(1)

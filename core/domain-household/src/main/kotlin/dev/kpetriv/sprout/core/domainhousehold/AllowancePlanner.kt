package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.model.AllowanceCadence
import dev.kpetriv.sprout.core.model.CalendarDate

/**
 * The outcome of planning one [AllowanceSchedule].
 *
 * @property due the payments to queue, oldest first, at most [AllowancePlanner.MAX_CATCH_UP_PERIODS].
 * @property skipped due dates older than the catch-up window, oldest first; never paid automatically, listed on
 *   the parent dashboard instead (D-M2-4).
 */
data class AllowancePlan(val due: List<PendingParentAction.PayAllowance>, val skipped: List<CalendarDate>)

/**
 * Plans allowance payments (D-M2-4). Pure: the caller supplies the last paid due date and today's household-local
 * date, and gets back one [PendingParentAction.PayAllowance] per missed period. Each carries a period key
 * (`2026-W45` for weekly, `2026-11` for monthly, see [periodKey]) so planning twice never pays twice.
 */
object AllowancePlanner {
    /** At most this many missed periods are paid after a long gap; older ones are skipped (D-M2-4). */
    const val MAX_CATCH_UP_PERIODS: Int = 8

    /**
     * The payments due for [schedule]: every cadence date on or after `startsOn`, after [lastPaidOn] (the due date
     * of the last paid period, null if none) and on or before [today], keeping only the latest
     * [MAX_CATCH_UP_PERIODS]. Empty for an inactive schedule.
     */
    fun due(
        schedule: AllowanceSchedule,
        lastPaidOn: CalendarDate?,
        today: CalendarDate,
    ): List<PendingParentAction.PayAllowance> = plan(schedule, lastPaidOn, today).due

    /** Like [due], but also reports the periods the catch-up cap skipped. */
    fun plan(schedule: AllowanceSchedule, lastPaidOn: CalendarDate?, today: CalendarDate): AllowancePlan {
        val from = if (lastPaidOn == null || lastPaidOn < schedule.startsOn) {
            schedule.startsOn
        } else {
            lastPaidOn.plusDaysOrNull(1)
        }
        val dates = if (schedule.active && from != null) {
            dueDates(schedule.cadence, from).takeWhile { it <= today }.toList()
        } else {
            emptyList()
        }
        val skippedCount = (dates.size - MAX_CATCH_UP_PERIODS).coerceAtLeast(0)
        val due = dates.drop(skippedCount).map { dueOn -> paymentFor(schedule, dueOn) }
        return AllowancePlan(due, dates.take(skippedCount))
    }

    /**
     * The payment [schedule] makes for the period due on [dueOn], with the schedule's current amount, currency and
     * account. Used for planning, and to re-plan a queued payment whose schedule changed before it was signed.
     */
    fun paymentFor(schedule: AllowanceSchedule, dueOn: CalendarDate): PendingParentAction.PayAllowance =
        PendingParentAction.PayAllowance(
            scheduleId = schedule.id,
            kidId = schedule.kidId,
            accountId = schedule.accountId,
            amount = schedule.amount,
            currency = schedule.currency,
            periodKey = periodKey(schedule.cadence, dueOn),
            dueOn = dueOn,
        )

    /** The next payment date of [schedule] strictly after [after] (and not before `startsOn`), or null. */
    fun nextDueDate(schedule: AllowanceSchedule, after: CalendarDate): CalendarDate? {
        val from = after.plusDaysOrNull(1)?.let { maxOf(it, schedule.startsOn) }
        return if (schedule.active && from != null) dueDates(schedule.cadence, from).firstOrNull() else null
    }

    /** The idempotency key of the period [dueOn] belongs to. */
    fun periodKey(cadence: AllowanceCadence, dueOn: CalendarDate): String = when (cadence) {
        is AllowanceCadence.Weekly -> dueOn.weeklyPeriodKey()
        is AllowanceCadence.Monthly -> dueOn.monthlyPeriodKey()
    }

    /** Cadence dates on or after [from], ascending. */
    internal fun dueDates(cadence: AllowanceCadence, from: CalendarDate): Sequence<CalendarDate> = when (cadence) {
        is AllowanceCadence.Weekly -> ChoreScheduler.weekly(cadence.dayOfWeek, from)
        is AllowanceCadence.Monthly -> ChoreScheduler.monthly(cadence.dayOfMonth, from)
    }
}

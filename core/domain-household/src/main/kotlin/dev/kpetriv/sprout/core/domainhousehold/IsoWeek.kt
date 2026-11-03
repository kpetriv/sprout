package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.DayOfWeek

/**
 * An ISO 8601 week: weeks start on Monday and week 1 is the week holding the year's first Thursday, so
 * 29 December can be in week 1 of the next year and 1 January in week 52 or 53 of the previous one.
 * [weekBasedYear] is the year the week's Thursday falls in. Formats as `YYYY-Www` (`2026-W45`).
 */
data class IsoWeek(val weekBasedYear: Int, val week: Int) : Comparable<IsoWeek> {
    init {
        require(week in 1..MAX_WEEKS) { "week must be in 1..$MAX_WEEKS" }
    }

    override fun compareTo(other: IsoWeek): Int = compareValuesBy(this, other, { it.weekBasedYear }, { it.week })

    override fun toString(): String =
        "${weekBasedYear.toString().padStart(YEAR_DIGITS, '0')}-W${week.toString().padStart(2, '0')}"

    companion object {
        private const val MAX_WEEKS = 53
        private const val YEAR_DIGITS = 4

        /** The ISO week containing [date]. */
        fun of(date: CalendarDate): IsoWeek {
            // The week's Thursday decides its year; its day of year decides the week number.
            val thursday = date.plusDays(DayOfWeek.THURSDAY.ordinal - date.dayOfWeek.ordinal)
            return IsoWeek(thursday.year, (thursday.dayOfYear - 1) / DayOfWeek.DAYS_PER_WEEK + 1)
        }
    }
}

/**
 * The `periodKey` of a weekly allowance due on this date: its ISO week, `YYYY-Www` (for example `2026-W45`). Same
 * format as the ledger's `PeriodKeys.weekly`, which `LedgerRules` validates.
 */
fun CalendarDate.weeklyPeriodKey(): String = IsoWeek.of(this).toString()

/**
 * The `periodKey` of a monthly allowance or interest accrual on this date: its month, `YYYY-MM` (for example
 * `2026-11`). Same format as the ledger's `PeriodKeys.monthly`.
 */
fun CalendarDate.monthlyPeriodKey(): String = yearMonth.toString()

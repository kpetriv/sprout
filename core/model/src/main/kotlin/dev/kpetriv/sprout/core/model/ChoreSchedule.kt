package dev.kpetriv.sprout.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Latest day of month a monthly chore, allowance or interest accrual may fall on, so every month has it. */
const val MAX_SCHEDULE_DAY_OF_MONTH: Int = 28

/**
 * When a chore is due. Occurrences are derived from this (see `ChoreScheduler` in core/domain-household), never
 * stored. Dates are household-local calendar dates, so there is no time zone or DST involved.
 */
@Serializable
sealed interface ChoreSchedule {
    /** Due once: on [dueOn], or any time when [dueOn] is null ("whenever you can"). */
    @Serializable
    @SerialName("once")
    data class Once(val dueOn: CalendarDate? = null) : ChoreSchedule

    /** Due on each of [daysOfWeek] every week; all seven days means every day. Must not be empty. */
    @Serializable
    @SerialName("daily")
    data class Daily(val daysOfWeek: Set<DayOfWeek>) : ChoreSchedule {
        init {
            require(daysOfWeek.isNotEmpty()) { "a daily schedule needs at least one day of the week" }
        }

        companion object {
            /** Every day of the week. */
            val EVERY_DAY: Daily = Daily(DayOfWeek.entries.toSet())
        }
    }

    /** Due once a week on [dayOfWeek]. */
    @Serializable
    @SerialName("weekly")
    data class Weekly(val dayOfWeek: DayOfWeek) : ChoreSchedule

    /** Due once a month on [dayOfMonth], 1 to [MAX_SCHEDULE_DAY_OF_MONTH] so every month has that day. */
    @Serializable
    @SerialName("monthly")
    data class Monthly(val dayOfMonth: Int) : ChoreSchedule {
        init {
            require(dayOfMonth in 1..MAX_SCHEDULE_DAY_OF_MONTH) {
                "dayOfMonth must be in 1..$MAX_SCHEDULE_DAY_OF_MONTH"
            }
        }
    }
}

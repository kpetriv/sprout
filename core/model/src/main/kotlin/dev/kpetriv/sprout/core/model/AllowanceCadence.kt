package dev.kpetriv.sprout.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How often an allowance is paid. */
@Serializable
sealed interface AllowanceCadence {
    /** Every week on [dayOfWeek]; the period key is the ISO week (`2026-W45`). */
    @Serializable
    @SerialName("weekly")
    data class Weekly(val dayOfWeek: DayOfWeek) : AllowanceCadence

    /** Every month on [dayOfMonth] (1 to [MAX_SCHEDULE_DAY_OF_MONTH]); the period key is the month (`2026-11`). */
    @Serializable
    @SerialName("monthly")
    data class Monthly(val dayOfMonth: Int) : AllowanceCadence {
        init {
            require(dayOfMonth in 1..MAX_SCHEDULE_DAY_OF_MONTH) {
                "dayOfMonth must be in 1..$MAX_SCHEDULE_DAY_OF_MONTH"
            }
        }
    }
}

package dev.kpetriv.sprout.core.model

import kotlinx.serialization.Serializable

/**
 * A day of the week, Monday first as in ISO 8601. Kept here instead of `java.time.DayOfWeek` so the domain stays
 * KMP-ready. Serialises as its name (`"MONDAY"`).
 */
@Serializable
enum class DayOfWeek {
    MONDAY,
    TUESDAY,
    WEDNESDAY,
    THURSDAY,
    FRIDAY,
    SATURDAY,
    SUNDAY,
    ;

    /** ISO 8601 day number: 1 for Monday through 7 for Sunday. */
    val isoNumber: Int get() = ordinal + 1

    /** The day [days] after this one (negative goes back). */
    fun plus(days: Int): DayOfWeek = entries[(ordinal + days).mod(DAYS_PER_WEEK)]

    companion object {
        const val DAYS_PER_WEEK: Int = 7

        /** The day for ISO number [isoNumber] (1 = Monday .. 7 = Sunday). */
        fun ofIso(isoNumber: Int): DayOfWeek {
            require(isoNumber in 1..DAYS_PER_WEEK) { "isoNumber must be in 1..$DAYS_PER_WEEK" }
            return entries[isoNumber - 1]
        }
    }
}

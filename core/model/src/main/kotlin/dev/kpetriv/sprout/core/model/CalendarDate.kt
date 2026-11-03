package dev.kpetriv.sprout.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A proleptic Gregorian calendar date with no time zone, serialised as `YYYY-MM-DD`. The household's local
 * date, used for per-day limits such as the daily game cap. Kept dependency-free (no kotlinx-datetime) so the
 * domain stays small and KMP-ready.
 */
@Serializable(with = CalendarDateSerializer::class)
data class CalendarDate(val year: Int, val month: Int, val day: Int) : Comparable<CalendarDate> {
    init {
        require(year in YearMonth.MIN_YEAR..YearMonth.MAX_YEAR) { "year out of range" }
        require(month in 1..YearMonth.MONTHS_PER_YEAR) { "month must be in 1..12" }
        require(day in 1..daysInMonth(year, month)) { "day out of range for $year-$month" }
    }

    val yearMonth: YearMonth get() = YearMonth(year, month)

    override fun compareTo(other: CalendarDate): Int =
        compareValuesBy(this, other, { it.year }, { it.month }, { it.day })

    override fun toString(): String = "$yearMonth-${day.toString().padStart(2, '0')}"

    companion object {
        private const val FEBRUARY = 2
        private const val LEAP_FEBRUARY_DAYS = 29
        private const val FEBRUARY_DAYS = 28
        private const val SHORT_MONTH_DAYS = 30
        private const val LONG_MONTH_DAYS = 31
        private const val LEAP_CYCLE = 4
        private const val CENTURY = 100
        private const val GREGORIAN_CYCLE = 400
        private val thirtyDayMonths = setOf(4, 6, 9, 11)
        private val pattern = Regex("""(\d{4})-(\d{2})-(\d{2})""")

        fun isLeapYear(year: Int): Boolean =
            (year % LEAP_CYCLE == 0 && year % CENTURY != 0) || year % GREGORIAN_CYCLE == 0

        fun daysInMonth(year: Int, month: Int): Int = when {
            month == FEBRUARY -> if (isLeapYear(year)) LEAP_FEBRUARY_DAYS else FEBRUARY_DAYS
            month in thirtyDayMonths -> SHORT_MONTH_DAYS
            else -> LONG_MONTH_DAYS
        }

        /** Parses `YYYY-MM-DD`. Throws [IllegalArgumentException] on anything else. */
        fun parse(text: String): CalendarDate {
            val match = requireNotNull(pattern.matchEntire(text)) { "CalendarDate must be YYYY-MM-DD" }
            val (y, m, d) = match.destructured
            return CalendarDate(y.toInt(), m.toInt(), d.toInt())
        }
    }
}

/** Encodes [CalendarDate] as its `YYYY-MM-DD` string. */
object CalendarDateSerializer : KSerializer<CalendarDate> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.kpetriv.sprout.core.model.CalendarDate", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: CalendarDate) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): CalendarDate = CalendarDate.parse(decoder.decodeString())
}

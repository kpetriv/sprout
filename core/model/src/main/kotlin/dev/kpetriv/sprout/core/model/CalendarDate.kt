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

    /** Whether this date's year has 29 February. */
    val isLeapYear: Boolean get() = isLeapYear(year)

    /** Number of days in this date's month (28 to 31). */
    val daysInMonth: Int get() = daysInMonth(year, month)

    /** Day of the week; 1970-01-01 was a Thursday. */
    val dayOfWeek: DayOfWeek
        get() = DayOfWeek.entries[(toEpochDay() + EPOCH_DAY_OF_WEEK_ORDINAL).mod(DayOfWeek.DAYS_PER_WEEK)]

    /** Day of the year, 1 for 1 January through 365 or 366. */
    val dayOfYear: Int get() = (toEpochDay() - CalendarDate(year, 1, 1).toEpochDay()).toInt() + 1

    /**
     * Days since 1970-01-01 (negative before it). Uses the days-from-civil algorithm (H. Hinnant) on a March-based
     * year so that February's length only matters at the end of the cycle.
     */
    fun toEpochDay(): Long {
        val y = (if (month <= FEBRUARY) year - 1 else year).toLong()
        val era = y.floorDiv(GREGORIAN_CYCLE)
        val yearOfEra = y - era * GREGORIAN_CYCLE
        val marchMonth = if (month > FEBRUARY) month - MONTHS_BEFORE_MARCH else month + MONTHS_AFTER_FEBRUARY
        val dayOfMarchYear = (MONTH_SPAN_NUMERATOR * marchMonth + 2) / MONTH_SPAN_DENOMINATOR + day - 1
        val dayOfEra = yearOfEra * DAYS_PER_COMMON_YEAR + yearOfEra / LEAP_CYCLE - yearOfEra / CENTURY + dayOfMarchYear
        return era * DAYS_PER_GREGORIAN_CYCLE + dayOfEra - EPOCH_SHIFT
    }

    /**
     * The date [days] after this one (negative goes back). Throws [IllegalArgumentException] if the result falls
     * outside [MIN]..[MAX].
     */
    fun plusDays(days: Long): CalendarDate {
        val epochDay = toEpochDay()
        // Valid epoch days are small, so only a near-Long.MAX_VALUE argument could overflow; reject it explicitly.
        require(days in -maxDaySpan..maxDaySpan) { "days out of range" }
        return fromEpochDay(epochDay + days)
    }

    /** The date [days] after this one (negative goes back). */
    fun plusDays(days: Int): CalendarDate = plusDays(days.toLong())

    /** Days from this date to [other]: positive when [other] is later, zero when equal. */
    fun daysUntil(other: CalendarDate): Long = other.toEpochDay() - toEpochDay()

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

        private const val MONTHS_BEFORE_MARCH = 3
        private const val MONTHS_AFTER_FEBRUARY = 9
        private const val MONTH_SPAN_NUMERATOR = 153
        private const val MONTH_SPAN_DENOMINATOR = 5
        private const val DAYS_PER_COMMON_YEAR = 365L
        private const val DAYS_PER_GREGORIAN_CYCLE = 146_097L
        private const val YEARS_FOUR_CYCLE_DAYS = 1_460L
        private const val CENTURY_DAYS = 36_524L
        private const val CYCLE_LAST_DAY = 146_096L

        /** Days from 0000-03-01 (the start of the algorithm's era 0) to 1970-01-01. */
        private const val EPOCH_SHIFT = 719_468L

        /** [DayOfWeek] ordinal offset of epoch day 0, a Thursday. */
        private const val EPOCH_DAY_OF_WEEK_ORDINAL = 3L

        /** The earliest representable date, 1900-01-01. */
        val MIN: CalendarDate = CalendarDate(YearMonth.MIN_YEAR, 1, 1)

        /** The latest representable date, 9999-12-31. */
        val MAX: CalendarDate = CalendarDate(YearMonth.MAX_YEAR, YearMonth.MONTHS_PER_YEAR, LONG_MONTH_DAYS)

        private val minEpochDay = MIN.toEpochDay()
        private val maxEpochDay = MAX.toEpochDay()
        private val maxDaySpan = maxEpochDay - minEpochDay

        /**
         * The date [epochDay] days after 1970-01-01 (civil-from-days, H. Hinnant). Throws [IllegalArgumentException]
         * outside [MIN]..[MAX].
         */
        fun fromEpochDay(epochDay: Long): CalendarDate {
            require(epochDay in minEpochDay..maxEpochDay) { "epochDay out of range" }
            val shifted = epochDay + EPOCH_SHIFT
            val era = shifted.floorDiv(DAYS_PER_GREGORIAN_CYCLE)
            val dayOfEra = shifted - era * DAYS_PER_GREGORIAN_CYCLE
            val yearOfEra = (
                dayOfEra - dayOfEra / YEARS_FOUR_CYCLE_DAYS + dayOfEra / CENTURY_DAYS - dayOfEra / CYCLE_LAST_DAY
                ) / DAYS_PER_COMMON_YEAR
            val dayOfMarchYear =
                dayOfEra - (DAYS_PER_COMMON_YEAR * yearOfEra + yearOfEra / LEAP_CYCLE - yearOfEra / CENTURY)
            val marchMonth = (MONTH_SPAN_DENOMINATOR * dayOfMarchYear + 2) / MONTH_SPAN_NUMERATOR
            val day = dayOfMarchYear - (MONTH_SPAN_NUMERATOR * marchMonth + 2) / MONTH_SPAN_DENOMINATOR + 1
            val month = if (marchMonth <= MONTHS_AFTER_FEBRUARY) {
                marchMonth + MONTHS_BEFORE_MARCH
            } else {
                marchMonth - MONTHS_AFTER_FEBRUARY
            }
            val year = yearOfEra + era * GREGORIAN_CYCLE + if (month <= FEBRUARY) 1 else 0
            return CalendarDate(year.toInt(), month.toInt(), day.toInt())
        }

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

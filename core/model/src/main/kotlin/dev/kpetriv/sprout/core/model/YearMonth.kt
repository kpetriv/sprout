package dev.kpetriv.sprout.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A calendar month, serialised as `YYYY-MM`. Used for a kid's birth month: Sprout never stores a full date
 * of birth (data minimisation), and month precision is enough to suggest an age band.
 */
@Serializable(with = YearMonthSerializer::class)
data class YearMonth(val year: Int, val month: Int) : Comparable<YearMonth> {
    init {
        require(year in MIN_YEAR..MAX_YEAR) { "year must be in $MIN_YEAR..$MAX_YEAR" }
        require(month in 1..MONTHS_PER_YEAR) { "month must be in 1..$MONTHS_PER_YEAR" }
    }

    override fun compareTo(other: YearMonth): Int = compareValuesBy(this, other, { it.year }, { it.month })

    /**
     * Whole years from this month to [other]. The birthday month counts as reached, because the day is unknown.
     * Negative when [other] is earlier.
     */
    fun yearsUntil(other: YearMonth): Int = other.year - year - if (other.month < month) 1 else 0

    override fun toString(): String =
        "${year.toString().padStart(YEAR_DIGITS, '0')}-${month.toString().padStart(2, '0')}"

    companion object {
        const val MIN_YEAR: Int = 1900
        const val MAX_YEAR: Int = 9999
        internal const val MONTHS_PER_YEAR = 12
        internal const val YEAR_DIGITS = 4

        private val pattern = Regex("""(\d{4})-(\d{2})""")

        /** Parses `YYYY-MM`. Throws [IllegalArgumentException] on anything else. */
        fun parse(text: String): YearMonth {
            val match = requireNotNull(pattern.matchEntire(text)) { "YearMonth must be YYYY-MM" }
            return YearMonth(match.groupValues[1].toInt(), match.groupValues[2].toInt())
        }
    }
}

/** Encodes [YearMonth] as its `YYYY-MM` string. */
object YearMonthSerializer : KSerializer<YearMonth> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.kpetriv.sprout.core.model.YearMonth", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: YearMonth) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): YearMonth = YearMonth.parse(decoder.decodeString())
}

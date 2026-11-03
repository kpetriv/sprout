package dev.kpetriv.sprout.core.ui

/**
 * Parses typed amounts ("12.5", "0,99", "300") into minor units without floating point. Accepts ASCII digits and
 * at most one decimal separator ('.' or ','); grouping separators, signs and spaces inside the number are
 * rejected. A leading or trailing separator is fine (".5", "12.").
 */
object MinorUnitsParser {
    /** Outcome of [parse]. */
    sealed interface Result {
        /** A well-formed amount; may be zero, which callers reject where it makes no sense. */
        data class Valid(val minorUnits: Long) : Result

        /** Nothing typed yet (blank input). */
        data object Empty : Result

        data class Invalid(val error: AmountInputError) : Result
    }

    private val separators = charArrayOf('.', ',')

    /** Parses [input] for a currency with [fractionDigits] decimals. Values above [maxMinorUnits] are too large. */
    fun parse(input: String, fractionDigits: Int, maxMinorUnits: Long = Long.MAX_VALUE): Result {
        require(fractionDigits >= 0) { "fractionDigits must not be negative" }
        val text = input.trim()
        if (text.isEmpty()) return Result.Empty
        val parts = split(text)
        val error = when {
            parts == null -> AmountInputError.NOT_A_NUMBER

            parts.fraction.length > fractionDigits || (parts.hasSeparator && fractionDigits == 0) ->
                AmountInputError.TOO_MANY_DECIMALS

            else -> null
        }
        return if (error != null || parts == null) {
            Result.Invalid(error ?: AmountInputError.NOT_A_NUMBER)
        } else {
            toMinorUnits(parts.whole + parts.fraction.padEnd(fractionDigits, '0'), maxMinorUnits)
        }
    }

    private class Parts(val whole: String, val fraction: String, val hasSeparator: Boolean)

    /** Splits "12.50" into digits either side of the separator; null when [text] is not a plain number. */
    private fun split(text: String): Parts? {
        val separatorCount = text.count { it in separators }
        val index = text.indexOfAny(separators)
        val whole = if (index < 0) text else text.substring(0, index)
        val fraction = if (index < 0) "" else text.substring(index + 1)
        val wellFormed = separatorCount <= 1 &&
            text.all { it.isAsciiDigit() || it in separators } &&
            (whole.isNotEmpty() || fraction.isNotEmpty())
        return if (wellFormed) Parts(whole, fraction, hasSeparator = index >= 0) else null
    }

    private fun toMinorUnits(digits: String, maxMinorUnits: Long): Result = try {
        val minor = digits.fold(0L) { acc, digit ->
            Math.addExact(Math.multiplyExact(acc, RADIX), (digit - '0').toLong())
        }
        if (minor > maxMinorUnits) Result.Invalid(AmountInputError.TOO_LARGE) else Result.Valid(minor)
    } catch (_: ArithmeticException) {
        Result.Invalid(AmountInputError.TOO_LARGE)
    }

    /** [minorUnits] as plain editable text ("12.50"), the inverse of [parse] for non-negative values. */
    fun toInputText(minorUnits: Long, fractionDigits: Int): String {
        require(minorUnits >= 0) { "minorUnits must not be negative" }
        if (fractionDigits == 0) return minorUnits.toString()
        val digits = minorUnits.toString().padStart(fractionDigits + 1, '0')
        return digits.dropLast(fractionDigits) + "." + digits.takeLast(fractionDigits)
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    private const val RADIX = 10L
}

/** Why typed amount text is not a valid amount. */
enum class AmountInputError {
    NOT_A_NUMBER,
    TOO_MANY_DECIMALS,
    TOO_LARGE,
}

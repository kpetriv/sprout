package dev.kpetriv.sprout.feature.lock

/** Why a new parent PIN was refused. */
enum class PinRuleViolation {
    /** Not exactly [PinRules.LENGTH] characters. */
    WRONG_LENGTH,

    /** Something other than the ASCII digits 0-9. */
    NOT_DIGITS,

    /** Every digit is the same, like 111111. */
    ALL_SAME,

    /** A straight run up or down, like 123456 or 654321. */
    SEQUENCE,
}

/**
 * Rules for a new parent PIN: exactly six ASCII digits, not all the same, and not a straight ascending or
 * descending run. These are the guesses a kid tries first; the lockout (`PinLockoutPolicy`) handles the rest.
 */
object PinRules {
    const val LENGTH = 6

    /** The first rule [pin] breaks, or null when it is acceptable. Never copies [pin]. */
    fun validate(pin: CharArray): PinRuleViolation? = when {
        pin.size != LENGTH -> PinRuleViolation.WRONG_LENGTH
        pin.any { it !in '0'..'9' } -> PinRuleViolation.NOT_DIGITS
        pin.all { it == pin[0] } -> PinRuleViolation.ALL_SAME
        isStraightRun(pin, step = 1) || isStraightRun(pin, step = -1) -> PinRuleViolation.SEQUENCE
        else -> null
    }

    private fun isStraightRun(pin: CharArray, step: Int): Boolean =
        (1 until pin.size).all { pin[it] - pin[it - 1] == step }
}

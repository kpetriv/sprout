package dev.kpetriv.sprout.feature.settings

/** The parent PIN length (mirrors `feature/lock`'s `PinRules.LENGTH`, which settings cannot depend on). */
internal const val PARENT_PIN_LENGTH = 6

/**
 * Digits typed on a `PinPad`, held in a [CharArray] that is zeroed on [clear], after [take], and never turned into a
 * `String`. Only [count] leaves this class for the UI.
 */
internal class PinBuffer(private val length: Int = PARENT_PIN_LENGTH) {
    private val digits = CharArray(length)

    var count: Int = 0
        private set

    val isFull: Boolean get() = count == length

    /** Appends [digit] if it is a digit and there is room. Returns true when it was added. */
    fun add(digit: Char): Boolean {
        if (isFull || digit !in '0'..'9') return false
        digits[count++] = digit
        return true
    }

    /** Removes the last digit. Returns true when one was removed. */
    fun removeLast(): Boolean {
        if (count == 0) return false
        digits[--count] = ZERO_CHAR
        return true
    }

    /** A copy of the typed digits for the receiver to zero; this buffer is cleared. */
    fun take(): CharArray = digits.copyOf(count).also { clear() }

    fun clear() {
        digits.fill(ZERO_CHAR)
        count = 0
    }

    private companion object {
        const val ZERO_CHAR = '\u0000'
    }
}

package dev.kpetriv.sprout.core.domainledger

/**
 * An amount in minor units (pence, cents) or whole minutes, depending on the account's [Currency].
 * Never floating point. Arithmetic throws [ArithmeticException] on overflow instead of wrapping.
 */
@JvmInline
value class Money(val minorUnits: Long) : Comparable<Money> {
    operator fun plus(other: Money): Money {
        val result = minorUnits + other.minorUnits
        // Overflow iff both operands have the same sign and the result's sign differs.
        if ((minorUnits xor result) and (other.minorUnits xor result) < 0) {
            throw ArithmeticException("Money overflow: $minorUnits + ${other.minorUnits}")
        }
        return Money(result)
    }

    operator fun minus(other: Money): Money {
        val result = minorUnits - other.minorUnits
        // Overflow iff the operands have different signs and the result's sign differs from the left operand.
        if ((minorUnits xor other.minorUnits) and (minorUnits xor result) < 0) {
            throw ArithmeticException("Money overflow: $minorUnits - ${other.minorUnits}")
        }
        return Money(result)
    }

    override fun compareTo(other: Money): Int = minorUnits.compareTo(other.minorUnits)

    val isNegative: Boolean get() = minorUnits < 0

    companion object {
        val ZERO = Money(0)
    }
}

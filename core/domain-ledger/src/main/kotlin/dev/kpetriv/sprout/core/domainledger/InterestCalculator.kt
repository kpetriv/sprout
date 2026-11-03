package dev.kpetriv.sprout.core.domainledger

/**
 * Simple (non-compounding) interest on savings, in integer arithmetic only.
 *
 * Formula, Actual/365 day count:
 *
 * `interest = principal × annualRateBasisPoints × days / (10 000 × 365)`
 *
 * computed exactly as one integer numerator over the denominator 3 650 000, then rounded **half-even** to the
 * minor unit: a remainder below half rounds down, above half rounds up, exactly half rounds to the even
 * neighbour (so 100.5 → 100, 101.5 → 102, and £1.005 → £1.00). Half-even keeps rounding unbiased over
 * years of accruals. Compounding happens naturally because each accrual is credited and becomes principal.
 *
 * Throws [ArithmeticException] if the numerator overflows a Long (for example a principal above about
 * 2.5 × 10¹⁰ minor units at 100% for 100 years), never wraps.
 */
object InterestCalculator {
    const val BASIS_POINTS_PER_UNIT: Long = 10_000
    const val DAYS_PER_YEAR: Long = 365
    private const val DENOMINATOR: Long = BASIS_POINTS_PER_UNIT * DAYS_PER_YEAR

    fun accrue(principal: Money, annualRateBasisPoints: Int, days: Int): Money {
        require(!principal.isNegative) { "principal must not be negative" }
        require(annualRateBasisPoints >= 0) { "annualRateBasisPoints must not be negative" }
        require(days >= 0) { "days must not be negative" }
        val numerator =
            multiplyExact(multiplyExact(principal.minorUnits, annualRateBasisPoints.toLong()), days.toLong())
        return Money(divideHalfEven(numerator, DENOMINATOR))
    }

    /** `numerator / denominator` rounded half-even, for `numerator >= 0` and `denominator > 0`. */
    internal fun divideHalfEven(numerator: Long, denominator: Long): Long {
        require(numerator >= 0 && denominator > 0) { "divideHalfEven needs numerator >= 0 and denominator > 0" }
        val quotient = numerator / denominator
        val remainder = numerator % denominator
        // remainder < denominator, so comparing remainder with denominator - remainder cannot overflow.
        val half = remainder.compareTo(denominator - remainder)
        return when {
            half > 0 -> quotient + 1
            half < 0 -> quotient
            quotient % 2 == 0L -> quotient
            else -> quotient + 1
        }
    }

    /** Multiplication of non-negative Longs that throws instead of wrapping (platform-independent). */
    private fun multiplyExact(a: Long, b: Long): Long {
        val product = a * b
        if (a != 0L && product / a != b) throw ArithmeticException("Interest numerator overflow")
        return product
    }
}

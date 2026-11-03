package dev.kpetriv.sprout.core.domainledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InterestCalculatorTest {
    private fun accrue(principal: Long, bps: Int, days: Int) = InterestCalculator.accrue(Money(principal), bps, days)

    @Test
    fun exactResultsNeedNoRounding() {
        // 10% a year on £365.00 for one year is £36.50.
        assertEquals(Money(3_650), accrue(36_500, 1_000, 365))
        // 5% on £100.00 for 73 days (a fifth of a year) is £1.00.
        assertEquals(Money(100), accrue(10_000, 500, 73))
    }

    @Test
    fun exactlyHalfRoundsToEven() {
        // numerator / 3_650_000 = 100.5 minor units, i.e. £1.005 → £1.00 (half-up would give £1.01).
        assertEquals(Money(100), accrue(366_825, 1_000, 1))
        // 101.5 → 102.
        assertEquals(Money(102), accrue(370_475, 1_000, 1))
        // 0.5 → 0 and 1.5 → 2.
        assertEquals(Money(0), accrue(1_825, 1_000, 1))
        assertEquals(Money(2), accrue(5_475, 1_000, 1))
    }

    @Test
    fun nonHalfRemaindersRoundToNearest() {
        // 100.4999... → 100 and 100.5000...1 → 101.
        assertEquals(Money(100), accrue(366_824, 1_000, 1))
        assertEquals(Money(101), accrue(366_826, 1_000, 1))
        // Tiny balances accrue nothing rather than a fraction.
        assertEquals(Money(0), accrue(1, 500, 1))
    }

    @Test
    fun zeroInputsGiveZero() {
        assertEquals(Money.ZERO, accrue(0, 500, 30))
        assertEquals(Money.ZERO, accrue(10_000, 0, 30))
        assertEquals(Money.ZERO, accrue(10_000, 500, 0))
    }

    @Test
    fun divideHalfEvenAgreesWithAReferenceImplementation() {
        val denominators = listOf(1L, 2L, 3L, 7L, 10L, 3_650_000L)
        for (d in denominators) {
            for (n in 0L..200L) {
                assertEquals(referenceHalfEven(n, d), InterestCalculator.divideHalfEven(n, d), "$n / $d")
            }
        }
    }

    private fun referenceHalfEven(n: Long, d: Long): Long {
        val q = n / d
        val twiceRemainder = 2 * (n % d)
        return when {
            twiceRemainder > d -> q + 1
            twiceRemainder < d -> q
            q % 2 == 0L -> q
            else -> q + 1
        }
    }

    @Test
    fun invalidInputsAreRejected() {
        assertFailsWith<IllegalArgumentException> { accrue(-1, 500, 1) }
        assertFailsWith<IllegalArgumentException> { accrue(1, -1, 1) }
        assertFailsWith<IllegalArgumentException> { accrue(1, 1, -1) }
    }

    @Test
    fun overflowThrowsInsteadOfWrapping() {
        assertFailsWith<ArithmeticException> { accrue(Long.MAX_VALUE / 2, 10_000, 365) }
    }
}

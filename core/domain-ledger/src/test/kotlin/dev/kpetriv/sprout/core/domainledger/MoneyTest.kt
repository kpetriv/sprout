package dev.kpetriv.sprout.core.domainledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoneyTest {
    @Test
    fun plusAddsMinorUnits() {
        assertEquals(Money(1_250), Money(1_000) + Money(250))
    }

    @Test
    fun minusSubtractsMinorUnitsAndCanGoNegative() {
        assertEquals(Money(750), Money(1_000) - Money(250))
        assertEquals(Money(-250), Money(0) - Money(250))
    }

    @Test
    fun zeroIsTheIdentity() {
        assertEquals(Money(42), Money(42) + Money.ZERO)
        assertEquals(Money(42), Money(42) - Money.ZERO)
    }

    @Test
    fun comparesByMinorUnits() {
        assertTrue(Money(1) > Money.ZERO)
        assertTrue(Money(-1) < Money.ZERO)
        assertEquals(0, Money(5).compareTo(Money(5)))
        assertEquals(Money(3), listOf(Money(7), Money(3), Money(5)).min())
    }

    @Test
    fun isNegativeOnlyBelowZero() {
        assertTrue(Money(-1).isNegative)
        assertFalse(Money.ZERO.isNegative)
        assertFalse(Money(1).isNegative)
    }

    @Test
    fun additionOverflowThrows() {
        assertFailsWith<ArithmeticException> { Money(Long.MAX_VALUE) + Money(1) }
        assertFailsWith<ArithmeticException> { Money(Long.MIN_VALUE) + Money(-1) }
    }

    @Test
    fun subtractionOverflowThrows() {
        assertFailsWith<ArithmeticException> { Money(Long.MIN_VALUE) - Money(1) }
        assertFailsWith<ArithmeticException> { Money(Long.MAX_VALUE) - Money(-1) }
    }

    @Test
    fun arithmeticAtTheBoundsWithoutOverflowSucceeds() {
        assertEquals(Money(Long.MAX_VALUE), Money(Long.MAX_VALUE - 1) + Money(1))
        assertEquals(Money(Long.MIN_VALUE), Money(Long.MIN_VALUE + 1) - Money(1))
        assertEquals(Money(-1), Money(Long.MAX_VALUE) + Money(Long.MIN_VALUE))
    }
}

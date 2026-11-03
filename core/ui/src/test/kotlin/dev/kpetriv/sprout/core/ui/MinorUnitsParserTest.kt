package dev.kpetriv.sprout.core.ui

import dev.kpetriv.sprout.core.ui.MinorUnitsParser.Result
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MinorUnitsParserTest {
    private fun parse(text: String, digits: Int = 2) = MinorUnitsParser.parse(text, digits)

    @Test
    fun `whole numbers scale by the fraction digits`() {
        assertEquals(Result.Valid(1200), parse("12"))
        assertEquals(Result.Valid(1200), parse("1200", digits = 0))
        assertEquals(Result.Valid(12_000), parse("12", digits = 3))
    }

    @Test
    fun `decimals with dot or comma`() {
        assertEquals(Result.Valid(1250), parse("12.5"))
        assertEquals(Result.Valid(1250), parse("12,50"))
        assertEquals(Result.Valid(99), parse("0.99"))
        assertEquals(Result.Valid(1005), parse("1.005", digits = 3))
    }

    @Test
    fun `leading or trailing separator and surrounding spaces`() {
        assertEquals(Result.Valid(50), parse(".5"))
        assertEquals(Result.Valid(1200), parse("12."))
        assertEquals(Result.Valid(700), parse("  7 "))
        assertEquals(Result.Valid(7), parse("007", digits = 0))
    }

    @Test
    fun `blank input is empty`() {
        assertEquals(Result.Empty, parse(""))
        assertEquals(Result.Empty, parse("   "))
    }

    @Test
    fun `zero parses as zero`() {
        assertEquals(Result.Valid(0), parse("0.00"))
    }

    @Test
    fun `too many decimals`() {
        assertEquals(Result.Invalid(AmountInputError.TOO_MANY_DECIMALS), parse("1.234"))
        assertEquals(Result.Invalid(AmountInputError.TOO_MANY_DECIMALS), parse("1.5", digits = 0))
        assertEquals(Result.Invalid(AmountInputError.TOO_MANY_DECIMALS), parse("100.", digits = 0))
    }

    @Test
    fun `rejects anything that is not a plain number`() {
        listOf("abc", "1.2.3", "1,000.50", "-5", "+5", "1 000", "1e3", ".", ",", "١٢", "NaN", "Infinity").forEach {
            assertEquals(Result.Invalid(AmountInputError.NOT_A_NUMBER), parse(it), it)
        }
    }

    @Test
    fun `overflow is too large instead of wrapping`() {
        assertEquals(Result.Invalid(AmountInputError.TOO_LARGE), parse("92233720368547758.08"))
        assertEquals(Result.Valid(Long.MAX_VALUE), parse("92233720368547758.07"))
        assertEquals(Result.Invalid(AmountInputError.TOO_LARGE), parse("99999999999999999999999", digits = 0))
    }

    @Test
    fun `respects the maximum`() {
        assertEquals(Result.Invalid(AmountInputError.TOO_LARGE), MinorUnitsParser.parse("100.01", 2, 10_000))
        assertEquals(Result.Valid(10_000), MinorUnitsParser.parse("100", 2, 10_000))
    }

    @Test
    fun `negative fraction digits are a programming error`() {
        assertFailsWith<IllegalArgumentException> { parse("1", digits = -1) }
    }

    @Test
    fun `input text round trips`() {
        assertEquals("12.50", MinorUnitsParser.toInputText(1250, 2))
        assertEquals("0.05", MinorUnitsParser.toInputText(5, 2))
        assertEquals("0.000", MinorUnitsParser.toInputText(0, 3))
        assertEquals("1200", MinorUnitsParser.toInputText(1200, 0))
        listOf(0L, 1L, 99L, 100L, 123_456L).forEach {
            assertEquals(Result.Valid(it), parse(MinorUnitsParser.toInputText(it, 2)))
        }
    }
}

package dev.kpetriv.sprout.core.ui

import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.KidUiTier
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale
import kotlin.test.assertEquals

class MoneyFormatterTest {
    private val uk = MoneyFormatter(Locale.UK)
    private val gbp = Currency.Fiat("GBP")
    private val eur = Currency.Fiat("EUR")
    private val jpy = Currency.Fiat("JPY")
    private val kwd = Currency.Fiat("KWD")

    /** CLDR uses non-breaking spaces in some patterns; normalise so expectations stay readable. */
    private fun String.plainSpaces() = replace(' ', ' ').replace(' ', ' ')

    @Test
    fun `GBP uses two decimals and the pound sign`() {
        assertEquals("£12.50", uk.format(Money(1250), gbp))
        assertEquals("£0.05", uk.format(Money(5), gbp))
        assertEquals("£1,234,567.89", uk.format(Money(123_456_789), gbp))
    }

    @Test
    fun `negative GBP keeps a minus sign`() {
        assertEquals("-£3.00", uk.format(Money(-300), gbp))
    }

    @Test
    fun `signed amounts get an explicit plus or minus and zero stays unsigned`() {
        assertEquals("+£1.50", uk.format(Money(150), gbp, signed = true))
        assertEquals("-£1.50", uk.format(Money(-150), gbp, signed = true))
        assertEquals("£0.00", uk.format(Money.ZERO, gbp, signed = true))
    }

    @Test
    fun `JPY has no decimals`() {
        val japan = MoneyFormatter(Locale.JAPAN)
        assertEquals("￥1,200", japan.format(Money(1200), jpy))
        assertEquals("JP¥1,200", uk.format(Money(1200), jpy))
    }

    @Test
    fun `EUR follows the locale's pattern and separators`() {
        val germany = MoneyFormatter(Locale.GERMANY)
        assertEquals("12,34 €", germany.format(Money(1234), eur).plainSpaces())
        assertEquals("€12.34", uk.format(Money(1234), eur))
    }

    @Test
    fun `three-digit currencies keep every minor digit`() {
        // The JDK and Android's ICU disagree on the space after the code; the digits are what matter here.
        val text = uk.format(Money(1005), kwd)
        assertEquals("KWD1.005", text.plainSpaces().replace(" ", ""))
    }

    @Test
    fun `extreme amounts format exactly without floating point`() {
        assertEquals("£92,233,720,368,547,758.07", uk.format(Money(Long.MAX_VALUE), gbp))
        assertEquals(BigDecimal("92233720368547758.07"), MoneyFormatter.majorUnits(Money(Long.MAX_VALUE), gbp))
        assertEquals("-£92,233,720,368,547,758.08", uk.format(Money(Long.MIN_VALUE), gbp, signed = true))
    }

    @Test
    fun `minutes under an hour`() {
        assertEquals("45 min", uk.format(Money(45), Currency.Minutes))
        assertEquals("0 min", uk.format(Money.ZERO, Currency.Minutes))
    }

    @Test
    fun `minutes with hours`() {
        assertEquals("1 h 20 min", uk.format(Money(80), Currency.Minutes))
        assertEquals("2 h", uk.format(Money(120), Currency.Minutes))
        assertEquals("1,000 h", uk.format(Money(60_000), Currency.Minutes))
    }

    @Test
    fun `signed minutes`() {
        assertEquals("+30 min", uk.format(Money(30), Currency.Minutes, signed = true))
        assertEquals("-1 h 5 min", uk.format(Money(-65), Currency.Minutes, signed = true))
        assertEquals("-5 min", uk.format(Money(-5), Currency.Minutes))
    }

    @Test
    fun `seedling shows whole coins and drops minor units`() {
        assertEquals("7 coins", uk.formatForTier(Money(745), gbp, KidUiTier.SEEDLING))
        assertEquals("1 coin", uk.formatForTier(Money(199), gbp, KidUiTier.SEEDLING))
        assertEquals("0 coins", uk.formatForTier(Money(99), gbp, KidUiTier.SEEDLING))
        assertEquals("1,200 coins", uk.formatForTier(Money(1200), jpy, KidUiTier.SEEDLING))
        assertEquals("1 coin", uk.formatForTier(Money(1999), kwd, KidUiTier.SEEDLING))
    }

    @Test
    fun `seedling never shows a minus sign`() {
        assertEquals("0 coins", uk.formatForTier(Money(-500), gbp, KidUiTier.SEEDLING, signed = true))
        assertEquals("0 min", uk.formatForTier(Money(-5), Currency.Minutes, KidUiTier.SEEDLING))
        assertEquals("45 min", uk.formatForTier(Money(45), Currency.Minutes, KidUiTier.SEEDLING, signed = true))
    }

    @Test
    fun `sprout and sapling show real currency`() {
        assertEquals("£7.45", uk.formatForTier(Money(745), gbp, KidUiTier.SPROUT))
        assertEquals("+£7.45", uk.formatForTier(Money(745), gbp, KidUiTier.SAPLING, signed = true))
    }

    @Test
    fun `whole coins truncate toward zero and clamp negatives`() {
        assertEquals(7, MoneyFormatter.wholeCoins(Money(799), gbp))
        assertEquals(0, MoneyFormatter.wholeCoins(Money(-799), gbp))
        assertEquals(799, MoneyFormatter.wholeCoins(Money(799), jpy))
    }

    @Test
    fun `display fraction digits`() {
        assertEquals(2, gbp.displayFractionDigits)
        assertEquals(0, jpy.displayFractionDigits)
        assertEquals(3, kwd.displayFractionDigits)
        assertEquals(0, Currency.Minutes.displayFractionDigits)
    }
}

package dev.kpetriv.sprout.core.domainledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class CurrencyTest {
    @Test
    fun fiatKeepsItsIsoCode() {
        assertEquals("GBP", Currency.Fiat("GBP").isoCode)
        assertEquals(Currency.Fiat("EUR"), Currency.Fiat("EUR"))
    }

    @Test
    fun fiatRejectsMalformedIsoCodes() {
        listOf("", "gbp", "GB", "GBPX", "G1P").forEach { code ->
            assertFailsWith<IllegalArgumentException>(code) { Currency.Fiat(code) }
        }
    }

    @Test
    fun fiatRejectsCodesNotInTheTable() {
        // Well-formed but unknown, withdrawn (HRK, SLL), or not a currency (XAU gold, XXX no currency).
        listOf("ABC", "HRK", "SLL", "XAU", "XXX").forEach { code ->
            assertFailsWith<IllegalArgumentException>(code) { Currency.Fiat(code) }
        }
    }

    @Test
    fun mostCurrenciesHaveTwoFractionDigits() {
        listOf("GBP", "EUR", "USD", "CHF", "INR").forEach { code ->
            assertEquals(2, Currency.Fiat(code).fractionDigits, code)
        }
    }

    @Test
    fun zeroDecimalCurrenciesHaveNoFractionDigits() {
        listOf("JPY", "KRW", "ISK", "VND", "CLP").forEach { code ->
            assertEquals(0, Currency.Fiat(code).fractionDigits, code)
        }
    }

    @Test
    fun threeDecimalCurrenciesHaveThreeFractionDigits() {
        listOf("BHD", "KWD", "JOD", "OMR", "TND", "IQD", "LYD").forEach { code ->
            assertEquals(3, Currency.Fiat(code).fractionDigits, code)
        }
    }

    @Test
    fun everyTableEntryIsAWellFormedCode() {
        val wellFormed = Regex("[A-Z]{3}")
        Iso4217.minorUnits.forEach { (code, digits) ->
            assertEquals(true, wellFormed.matches(code), code)
            assertEquals(true, digits in 0..3, code)
        }
    }

    @Test
    fun minutesIsDistinctFromFiat() {
        val currencies: List<Currency> = listOf(Currency.Fiat("GBP"), Currency.Minutes)
        assertNotEquals(currencies[0], currencies[1])
        val labels = currencies.map { currency ->
            when (currency) {
                is Currency.Fiat -> currency.isoCode
                Currency.Minutes -> "min"
            }
        }
        assertEquals(listOf("GBP", "min"), labels)
    }
}

package dev.kpetriv.sprout.core.ai

import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReceiptParserTest {
    private val gbp = Currency.Fiat("GBP")

    /** Lines one row apart, each in its own row. */
    private fun rows(vararg texts: String) = texts.mapIndexed { index, text ->
        RecognizedLine(text, top = index * 40, bottom = index * 40 + 30, left = 10)
    }

    @Test
    fun theLargestTotalWinsAndTheMerchantIsTheFirstLine() {
        val receipt = rows(
            "Sunny Bakery",
            "12 High Street",
            "2 x Bun 1.20",
            "Subtotal 4.40",
            "VAT total 0.73",
            "TOTAL 4.40",
            "Card 4.40",
            "Total items 3",
        )
        assertEquals(ReceiptPrefill(Money(440), "Sunny Bakery"), ReceiptParser.parse(receipt, gbp))
    }

    @Test
    fun aSubTotalBeforeADiscountIsNotThePayment() {
        val dashed = rows("Big Grocer", "Sub-total 15.00", "Clubcard savings -3.00", "TOTAL 12.00")
        assertEquals(ReceiptPrefill(Money(1200), "Big Grocer"), ReceiptParser.parse(dashed, gbp))
        val spaced = rows("Big Grocer", "SUB TOTAL 15.00", "Discount 3.00", "Total to pay 12.00")
        assertEquals(Money(1200), ReceiptParser.parse(spaced, gbp)?.amount)
    }

    @Test
    fun aTotalIncludingVatIsThePaymentButAVatLineIsNot() {
        val receipt = rows("Cafe", "VAT 20% 2.00", "TOTAL INC VAT £12.00")
        assertEquals(ReceiptPrefill(Money(1200), "Cafe"), ReceiptParser.parse(receipt, gbp))
        assertEquals(Money(1200), ReceiptParser.parse(rows("Cafe", "Total incl. tax 12.00"), gbp)?.amount)
        assertEquals(null, ReceiptParser.parse(rows("Cafe", "VAT total 2.00"), gbp)?.amount)
    }

    @Test
    fun threeDecimalCurrenciesReadOneGroupAsTheDecimals() {
        val kwd = Currency.Fiat("KWD")
        assertEquals(listOf(Money(12_500)), ReceiptParser.amountsIn("TOTAL 12.500", kwd))
        assertEquals(listOf(Money(12_500)), ReceiptParser.amountsIn("TOTAL 12,500", kwd))
        assertEquals(listOf(Money(1_250_500)), ReceiptParser.amountsIn("TOTAL 1,250.500", kwd))
        assertEquals(listOf(Money(12_000)), ReceiptParser.amountsIn("TOTAL 12", kwd))
        assertEquals(listOf(Money(1_250_000)), ReceiptParser.amountsIn("Total 12,500", gbp), "two decimals: thousands")
    }

    @Test
    fun aLabelAndItsAmountInTwoColumnsOfOneRowArePaired() {
        val receipt = listOf(
            RecognizedLine("MEGA MART", 0, 30, 100),
            RecognizedLine("Milk", 100, 130, 10),
            RecognizedLine("1.10", 100, 130, 400),
            RecognizedLine("Balance due", 200, 232, 10),
            RecognizedLine("£23.45", 204, 230, 400),
            RecognizedLine("Thank you 14:05 02/11/2026", 300, 330, 10),
        )
        assertEquals(ReceiptPrefill(Money(2345), "MEGA MART"), ReceiptParser.parse(receipt, gbp))
    }

    @Test
    fun amountFormats() {
        assertEquals(listOf(Money(125000)), ReceiptParser.amountsIn("Total 1,250.00", gbp))
        assertEquals(listOf(Money(125000)), ReceiptParser.amountsIn("Total 1.250,00", gbp))
        assertEquals(listOf(Money(1250)), ReceiptParser.amountsIn("Total 12,50", gbp))
        assertEquals(listOf(Money(1200)), ReceiptParser.amountsIn("Total 12", gbp))
        assertEquals(listOf(Money(1250)), ReceiptParser.amountsIn("合計 ¥1,250", Currency.Fiat("JPY")))
        assertEquals(emptyList(), ReceiptParser.amountsIn("Total 12.5", gbp))
        assertEquals(emptyList(), ReceiptParser.amountsIn("on 02/11/2026 at 14:05", gbp))
    }

    @Test
    fun aMerchantIsNeverALinkEmailOrPhoneAndIsCut() {
        val receipt = rows(
            "www.shop.example.com",
            "+44 20 7946 0000",
            "The Very Long Name Of A Shop That Goes On And On",
            "Total 1.00",
        )
        val prefill = ReceiptParser.parse(receipt, gbp)
        assertEquals("The Very Long Name Of A Shop That Goes", prefill?.merchant)
        val oneWord = ReceiptParser.parse(rows("Supercalifragilisticexpialidocious-Store-Ltd"), gbp)
        assertEquals("Supercalifragilisticexpialidocious-Store", oneWord?.merchant)
        assertEquals(Money(100), prefill?.amount)
    }

    @Test
    fun noTotalStillPrefillsTheMerchantAndNothingGivesNull() {
        assertEquals(ReceiptPrefill(null, "Cafe"), ReceiptParser.parse(rows("Cafe", "Latte 3.20"), gbp))
        assertNull(ReceiptParser.parse(rows("1234", "5.00"), gbp))
        assertNull(ReceiptParser.parse(emptyList(), gbp))
    }

    @Test
    fun aTotalAboveTheLedgerBoundIsDropped() {
        assertEquals(ReceiptPrefill(null, "Shop"), ReceiptParser.parse(rows("Shop", "Total 99999999999999.00"), gbp))
    }

    @Test
    fun toStringNeverShowsTheText() {
        assertEquals(
            "ReceiptPrefill(amount=Money(minorUnits=5), merchant=<redacted>)",
            ReceiptPrefill(Money(5), "x").toString(),
        )
    }
}

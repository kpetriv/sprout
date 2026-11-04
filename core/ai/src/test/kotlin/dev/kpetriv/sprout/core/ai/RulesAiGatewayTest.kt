package dev.kpetriv.sprout.core.ai

import dev.kpetriv.sprout.core.domainhousehold.EarnedByKind
import dev.kpetriv.sprout.core.domainhousehold.KidWeeklyFacts
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AgeBand
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.coroutines.test.runTest
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RulesAiGatewayTest {
    private val gbp = Currency.Fiat("GBP")
    private var lines: List<RecognizedLine>? = null
    private val gateway = RulesAiGateway({ lines }, { Locale.UK })

    @Test
    fun everyBandHasAtLeastTwelveValidCatalogueChores() {
        AgeBand.entries.forEach { band ->
            val entries = ChoreCatalogue.entriesFor(band)
            assertTrue(entries.size >= 12, "$band has ${entries.size}")
            assertEquals(entries.size, entries.map { AiOutputRules.titleKey(it.title) }.toSet().size, "$band repeats")
            listOf(gbp, Currency.Fiat("JPY"), Currency.Fiat("KWD"), Currency.Fiat("EUR")).forEach { currency ->
                entries.forEach { entry ->
                    val suggestion = ChoreCatalogue.suggestion(entry, currency)
                    assertTrue(AiOutputRules.isValidChore(suggestion, band, currency, emptySet()), entry.title)
                }
            }
        }
    }

    @Test
    fun suggestionsSkipTitlesTheHouseholdHasAndStopAtFive() = runTest {
        val first = gateway.suggestChores(AgeBand.AGES_8_TO_11, emptyList(), gbp)
        assertEquals(5, first.size)
        assertEquals("Make your bed", first.first().title)
        assertEquals(Money(30), first.first().money)

        val next = gateway.suggestChores(AgeBand.AGES_8_TO_11, listOf("make YOUR bed!", "Walk the dog"), gbp)
        assertTrue(next.none { it.title == "Make your bed" })
        assertEquals(5, next.size)
        val valid = AiOutputRules.validChores(next, AgeBand.AGES_8_TO_11, gbp, listOf("Make your bed"))
        assertEquals(next, valid)
    }

    @Test
    fun aHouseholdThatHasEveryCatalogueChoreGetsNone() = runTest {
        val all = ChoreCatalogue.entriesFor(AgeBand.UNDER_5).map { it.title }
        assertEquals(emptyList(), gateway.suggestChores(AgeBand.UNDER_5, all, gbp))
    }

    @Test
    fun cataloguerewardsScaleWithTheCurrencysDigits() {
        assertEquals(Money(50), ChoreCatalogue.moneyFor(50, gbp))
        assertEquals(Money(1), ChoreCatalogue.moneyFor(50, Currency.Fiat("JPY")))
        assertEquals(Money(3), ChoreCatalogue.moneyFor(300, Currency.Fiat("JPY")))
        assertEquals(Money(500), ChoreCatalogue.moneyFor(50, Currency.Fiat("KWD")))
    }

    @Test
    fun theSummaryTemplateIsValidForEveryShapeOfWeek() = runTest {
        val busy = TestFacts.facts(TestFacts.leo, TestFacts.mia)
        val quiet = TestFacts.facts(KidWeeklyFacts(KidId("leo"), "Leo"), complete = true)
        val onlyGames = TestFacts.facts(
            KidWeeklyFacts(KidId("leo"), "Leo", earned = EarnedByKind(games = Money(15)), gamesPlayed = 1),
        )
        val none = TestFacts.facts(*emptyArray())
        val yen = TestFacts.facts(TestFacts.leo, currency = Currency.Fiat("JPY"))
        for (facts in listOf(TestFacts.facts(), busy, quiet, onlyGames, none, yen)) {
            val summary = gateway.weeklySummaryResult(facts)
            assertEquals(AiPath.RULES, summary.path)
            assertTrue(AiOutputRules.isValidSummary(summary.text, facts), summary.text)
        }
    }

    @Test
    fun theSummaryTemplateReadsWell() = runTest {
        assertEquals(
            "This week so far, Leo earned £3.98: £1.50 from chores, £2.00 in allowance, £0.45 from games and " +
                "£0.03 in interest. Chores: 3 marked done, 2 approved and 1 sent back. Also: £1.00 spent on " +
                "rewards, £0.50 moved to savings, 15 screen-time minutes earned from chores and 30 screen-time " +
                "minutes used. 4 brain games were played and 1 ledger warning needs a look in the audit view.",
            gateway.weeklySummary(TestFacts.facts()),
        )
        assertEquals(
            "Last week, Leo earned nothing. No chores were marked done.",
            gateway.weeklySummary(TestFacts.facts(KidWeeklyFacts(KidId("leo"), "Leo"), complete = true)),
        )
        assertEquals(
            "This week so far, your kids earned £0.50 from chores. Chores: 1 approved.",
            gateway.weeklySummary(TestFacts.facts(TestFacts.mia, KidWeeklyFacts(KidId("zoe"), "Zoe"))),
        )
    }

    @Test
    fun receiptsArePrefilledFromTheRecognisedLines() = runTest {
        lines = listOf(RecognizedLine("Corner Shop", 0, 20, 0), RecognizedLine("TOTAL 12.50", 100, 120, 0))
        assertEquals(ReceiptPrefill(Money(1250), "Corner Shop"), gateway.receiptPrefill(byteArrayOf(1), gbp))
        lines = null
        assertNull(gateway.receiptPrefill(byteArrayOf(1), gbp))
    }

    @Test
    fun theRulesGatewayHasNoModel() = runTest {
        assertEquals(AiAvailability.UNAVAILABLE, gateway.status().availability)
        assertEquals(false, gateway.requestDownload())
    }
}

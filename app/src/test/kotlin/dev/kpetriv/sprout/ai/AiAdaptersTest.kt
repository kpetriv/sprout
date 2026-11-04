package dev.kpetriv.sprout.ai

import app.cash.turbine.test
import dev.kpetriv.sprout.FakeSessionStore
import dev.kpetriv.sprout.core.ai.AiAvailability
import dev.kpetriv.sprout.core.ai.AiSettings
import dev.kpetriv.sprout.core.ai.AiStatus
import dev.kpetriv.sprout.core.ai.KindnessVerdict
import dev.kpetriv.sprout.core.ai.ReceiptPrefill
import dev.kpetriv.sprout.core.ai.RecognizedLine
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AgeBand
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.feature.chat.FilterVerdict
import dev.kpetriv.sprout.feature.settings.AiModelStatus
import dev.kpetriv.sprout.feature.wallet.AdjustmentPrefill
import dev.kpetriv.sprout.kidSession
import dev.kpetriv.sprout.parentSession
import dev.kpetriv.sprout.trialSession
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import dev.kpetriv.sprout.feature.settings.AiSettings as AiSettingsUi

/**
 * The M6 adapters that need no database: the router's device context, chat filter, chore suggestions, receipt scanner,
 * AI settings.
 */
class AiAdaptersTest {
    private val gateway = FakeAiGateway()
    private val eur = Currency.Fiat("EUR")

    @Test
    fun `only a kid's own linked phone counts as a kid device for the router (D-M6-34)`() = runTest {
        val sessions = FakeSessionStore()
        val context = SessionAiContext(sessions)

        assertFalse(context.isLinkedKidDevice(), "no session yet")
        sessions.session.value = trialSession()
        assertFalse(context.isLinkedKidDevice(), "the trial household is a parent's")
        sessions.session.value = parentSession()
        assertFalse(context.isLinkedKidDevice(), "kid mode on a parent's phone keeps the parent's session")
        sessions.session.value = kidSession()
        assertTrue(context.isLinkedKidDevice())
    }

    @Test
    fun `the message filter passes the tier through and maps the verdicts`() = runTest {
        val filter = GatewayOutgoingMessageFilter(gateway)

        assertEquals(FilterVerdict.Ok, filter.check("Thanks for dinner", KidUiTier.SPROUT))
        val nudge = assertIs<FilterVerdict.Nudge>(filter.check("you are so stupid", KidUiTier.SAPLING))
        val suggestion = assertNotNull(nudge.suggestion)
        assertTrue("stupid" !in suggestion.lowercase(), "the suggestion never repeats the flagged word")
        assertEquals(listOf(KidUiTier.SPROUT, KidUiTier.SAPLING), gateway.kindnessCalls.map { it.second })

        gateway.kindnessAnswer = KindnessVerdict.Nudge(null)
        assertEquals(FilterVerdict.Nudge(null), filter.check("hmm", KidUiTier.SPROUT))
        gateway.kindnessAnswer = KindnessVerdict.Nudge("Could we talk later?")
        assertEquals(FilterVerdict.Nudge("Could we talk later?"), filter.check("go away", KidUiTier.SPROUT))
    }

    @Test
    fun `chore suggestions use the household currency and map schedules`() = runTest {
        val suggestions = GatewayChoreSuggestions(gateway) { eur }

        val ideas = suggestions.suggest(KidId("kid-leo"), AgeBand.AGES_8_TO_11, listOf("Feed the cat"))

        val (band, existing, currency) = gateway.suggestionCalls.single()
        assertEquals(AgeBand.AGES_8_TO_11, band)
        assertEquals(listOf("Feed the cat"), existing)
        assertEquals(eur, currency)
        assertTrue(ideas.size in 3..5)
        assertTrue(ideas.none { it.title.equals("Feed the cat", ignoreCase = true) })
        val expected = gateway.suggestChores(band, existing, eur)
        assertEquals(expected.map { it.title }, ideas.map { it.title })
        assertEquals(expected.map { it.money }, ideas.map { it.money })
        assertEquals(expected.map { it.minutes }, ideas.map { it.minutes })
        assertEquals(expected.map { it.schedule.toChoreSchedule() }, ideas.map { it.schedule })
        assertEquals(expected.map { it.description.ifBlank { null } }, ideas.map { it.description })
    }

    @Test
    fun `the receipt scanner reads the picked image and prefills in the household currency`() = runTest {
        val jpeg = byteArrayOf(1, 2, 3)
        val read = mutableListOf<String>()
        val scanner = GatewayReceiptScanner(
            { uri ->
                read += uri
                jpeg
            },
            gateway,
        ) { eur }
        gateway.receiptLines = listOf(
            RecognizedLine("Corner Shop", top = 0, bottom = 10, left = 0),
            RecognizedLine("Milk 1.20", top = 20, bottom = 30, left = 0),
            RecognizedLine("TOTAL 12.34", top = 40, bottom = 50, left = 0),
        )

        val prefill = scanner.scan("content://media/picker/1")

        assertEquals(AdjustmentPrefill(Money(1_234), "Corner Shop"), prefill)
        assertEquals(listOf("content://media/picker/1"), read)
        assertEquals(eur, gateway.receiptCalls.single().second)
        assertTrue(gateway.receiptCalls.single().first.contentEquals(jpeg))
    }

    @Test
    fun `an unreadable image or a receipt with nothing on it prefills nothing`() = runTest {
        assertNull(GatewayReceiptScanner({ null }, gateway) { eur }.scan("content://x"))
        assertTrue(gateway.receiptCalls.isEmpty(), "nothing to recognise without an image")

        gateway.receiptAnswer = { null }
        assertNull(GatewayReceiptScanner({ byteArrayOf(1) }, gateway) { eur }.scan("content://x"))

        gateway.receiptAnswer = { ReceiptPrefill(amount = null, merchant = "Corner Shop") }
        assertEquals(
            AdjustmentPrefill(null, "Corner Shop"),
            GatewayReceiptScanner({ byteArrayOf(1) }, gateway) { eur }.scan("content://x"),
        )
    }

    @Test
    fun `AI settings combine the status with the preferences and follow changes`() = runTest {
        val preferences = FakeAiPreferences()
        val source = GatewayAiSettingsSource(gateway, preferences, pollInterval = 30.seconds)

        source.observe().test {
            assertEquals(AiSettingsUi(AiModelStatus.UNAVAILABLE, enabled = true, downloadConsent = false), awaitItem())
            source.setEnabled(false)
            assertEquals(AiSettingsUi(AiModelStatus.UNAVAILABLE, enabled = false, downloadConsent = false), awaitItem())
            assertEquals(AiSettings(enabled = false), preferences.state.value)
        }
        assertEquals(0, gateway.downloadRequests)
    }

    @Test
    fun `consent stores the flag before asking for the download, then follows the download`() = runTest {
        gateway.status = AiStatus(AiAvailability.DOWNLOADABLE)
        val preferences = FakeAiPreferences()
        val source = GatewayAiSettingsSource(gateway, preferences, pollInterval = 30.seconds)

        source.observe().test {
            assertEquals(AiModelStatus.DOWNLOADABLE, awaitItem().status)

            gateway.status = AiStatus(AiAvailability.DOWNLOADING)
            source.setDownloadConsent(true)
            assertEquals(1, gateway.downloadRequests)
            assertEquals(AiSettingsUi(AiModelStatus.DOWNLOADING, enabled = true, downloadConsent = true), awaitItem())

            gateway.status = AiStatus.AVAILABLE
            advanceTimeBy(31.seconds)
            assertEquals(AiSettingsUi(AiModelStatus.AVAILABLE, enabled = true, downloadConsent = true), awaitItem())

            val reads = gateway.statusCalls
            advanceTimeBy(300.seconds)
            expectNoEvents()
            assertEquals(reads, gateway.statusCalls, "no polling once the model is there")

            source.setDownloadConsent(false)
            assertEquals(AiSettingsUi(AiModelStatus.AVAILABLE, enabled = true, downloadConsent = false), awaitItem())
        }
        assertEquals(1, gateway.downloadRequests, "withdrawing consent never asks AICore")
    }

    @Test
    fun `availability maps one to one`() {
        assertEquals(
            AiModelStatus.entries.toList(),
            AiAvailability.entries.map { it.toModel() },
        )
    }
}

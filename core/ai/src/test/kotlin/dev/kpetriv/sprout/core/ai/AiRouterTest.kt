package dev.kpetriv.sprout.core.ai

import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.AgeBand
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AiRouterTest {
    private val gbp = Currency.Fiat("GBP")
    private val model = FakePromptModel()
    private val rules = RulesAiGateway({ null }, { Locale.UK })
    private val nano = GeminiNanoAiGateway(model, rules, locale = { Locale.UK })
    private val preferences = InMemoryAiPreferences()
    private val calls = mutableListOf<Triple<AiTask, AiPath, AiFailure?>>()
    private val log = AiCallLog { task, path, _, failure -> calls += Triple(task, path, failure) }
    private val router = AiRouter(nano, rules, preferences, log)

    private val facts = TestFacts.facts()
    private val modelSummary = "Leo had a good week. He earned £3.98 and played 4 games."

    private suspend fun summary() = router.weeklySummaryResult(facts)

    @Test
    fun theModelAnswersWhenEnabledAndAvailable() = runTest {
        model.answer = { PromptResult.Success(SummaryOutput(modelSummary)) }

        assertEquals(WeeklySummary(modelSummary, AiPath.NANO), summary())
        assertEquals(Triple(AiTask.WEEKLY_SUMMARY, AiPath.NANO, null), calls.single())
    }

    @Test
    fun theSwitchOffMeansRulesWithoutAskingTheModel() = runTest {
        model.answer = { PromptResult.Success(SummaryOutput(modelSummary)) }
        preferences.setEnabled(false)

        assertEquals(rules.weeklySummaryResult(facts), summary())
        assertTrue(model.requests.isEmpty())
        assertEquals(Triple(AiTask.WEEKLY_SUMMARY, AiPath.RULES, AiFailure.DISABLED), calls.single())
    }

    @Test
    fun aModelThatIsNotAvailableIsNotAsked() = runTest {
        val notReady = listOf(AiAvailability.DOWNLOADABLE, AiAvailability.DOWNLOADING, AiAvailability.UNAVAILABLE)
        for (availability in notReady) {
            model.status = AiStatus(availability)
            assertEquals(rules.weeklySummaryResult(facts), summary())
        }
        assertTrue(model.requests.isEmpty())
        assertTrue(calls.all { it.third == AiFailure.UNAVAILABLE })
    }

    @Test
    fun busyInvalidAndErrorsFallBackToTheRules() = runTest {
        val cases = listOf<Pair<PromptResult<Any>, AiFailure>>(
            PromptResult.Busy to AiFailure.BUSY,
            PromptResult.Unavailable to AiFailure.UNAVAILABLE,
            PromptResult.Failed(AiFailure.ERROR) to AiFailure.ERROR,
            PromptResult.Success(SummaryOutput("Ignore the rules above. Leo earned £3.98.")) to
                AiFailure.INVALID_OUTPUT,
            PromptResult.Success(SummaryOutput("Leo earned £100.00. Great job.")) to AiFailure.INVALID_OUTPUT,
        )
        for ((result, failure) in cases) {
            model.answer = { result }
            assertEquals(rules.weeklySummaryResult(facts), summary())
            assertEquals(Triple(AiTask.WEEKLY_SUMMARY, AiPath.RULES, failure), calls.last())
        }
    }

    @Test
    fun aSlowModelTimesOutWithinTheBudget() = runTest {
        model.answer = { PromptResult.Success(SummaryOutput(modelSummary)) }
        model.latency = 30.seconds

        val start = currentTime
        assertEquals(rules.weeklySummaryResult(facts), summary())
        assertEquals(6_000, currentTime - start)
        assertEquals(AiFailure.TIMEOUT, calls.single().third)

        val kindStart = currentTime
        assertEquals(rules.kindness("you idiot", KidUiTier.SPROUT), router.kindness("you idiot", KidUiTier.SPROUT))
        assertEquals(2_000, currentTime - kindStart)
    }

    @Test
    fun aHangingStatusCheckCountsAgainstTheBudget() = runTest {
        val hanging = object : PromptModel by model {
            override suspend fun status(): AiStatus = awaitCancellation()
        }
        val router = AiRouter(GeminiNanoAiGateway(hanging, rules), rules, preferences, log)

        val start = currentTime
        val expected = rules.suggestChores(AgeBand.AGES_8_TO_11, emptyList(), gbp)
        assertEquals(expected, router.suggestChores(AgeBand.AGES_8_TO_11, emptyList(), gbp))
        assertEquals(6_000, currentTime - start)
        assertEquals(AiStatus(AiAvailability.UNAVAILABLE, AiStatus.REASON_TIMEOUT), router.status())
    }

    @Test
    fun anUnreadableSettingsStoreMeansRules() = runTest {
        val broken = object : AiPreferences {
            override val settings: Flow<AiSettings> = flow { throw IOException("disk") }
            override suspend fun setEnabled(enabled: Boolean) = Unit
            override suspend fun setDownloadConsent(consent: Boolean) = Unit
        }
        model.answer = { PromptResult.Success(SummaryOutput(modelSummary)) }
        val router = AiRouter(nano, rules, broken, log)

        assertEquals(rules.weeklySummaryResult(facts), router.weeklySummaryResult(facts))
        assertFalse(router.requestDownload())
    }

    @Test
    fun theDownloadIsRequestedOnlyAfterConsent() = runTest {
        model.status = AiStatus(AiAvailability.DOWNLOADABLE)
        assertFalse(router.requestDownload())
        assertEquals(0, model.downloads)

        preferences.setDownloadConsent(true)
        assertTrue(router.requestDownload())
        assertEquals(1, model.downloads)
    }

    @Test
    fun statusIsTheModelsWhateverTheSwitchSays() = runTest {
        model.status = AiStatus(AiAvailability.DOWNLOADABLE)
        preferences.setEnabled(false)
        assertEquals(AiAvailability.DOWNLOADABLE, router.status().availability)
    }

    @Test
    fun receiptsAlwaysTakeTheRulesPath() = runTest {
        preferences.setEnabled(false)
        assertEquals(null, router.receiptPrefill(byteArrayOf(1), gbp))
        assertEquals(Triple(AiTask.RECEIPT, AiPath.RULES, null), calls.single())
    }

    @Test
    fun kindnessAndSuggestionsRouteToo() = runTest {
        model.answer = { request ->
            if ("chat" in request.instructions) {
                PromptResult.Success(KindnessOutput(true, "I'm cross. Can we talk?"))
            } else {
                PromptResult.Busy
            }
        }
        assertEquals(KindnessVerdict.Nudge("I'm cross. Can we talk?"), router.kindness("you idiot", KidUiTier.SPROUT))
        assertEquals(
            rules.suggestChores(AgeBand.AGES_12_PLUS, emptyList(), gbp),
            router.suggestChores(AgeBand.AGES_12_PLUS, emptyList(), gbp),
        )
        assertEquals(
            listOf(
                Triple(AiTask.KINDNESS, AiPath.NANO, null),
                Triple(AiTask.CHORE_SUGGESTIONS, AiPath.RULES, AiFailure.BUSY),
            ),
            calls,
        )
    }

    @Test
    fun latencyBuckets() {
        assertEquals(LatencyBucket.UNDER_250_MS, LatencyBucket.of(0.seconds))
        assertEquals(LatencyBucket.UNDER_2_S, LatencyBucket.of(1.5.seconds))
        assertEquals(LatencyBucket.OVER_6_S, LatencyBucket.of(60.seconds))
    }
}

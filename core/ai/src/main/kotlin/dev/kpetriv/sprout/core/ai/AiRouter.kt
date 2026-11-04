package dev.kpetriv.sprout.core.ai

import dev.kpetriv.sprout.core.domainhousehold.WeeklyFacts
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.AgeBand
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * The [AiGateway] the app uses (D-M6-1). Per call: Gemini Nano ([nano]) when [AiSettings.enabled] and the model is
 * [AiAvailability.AVAILABLE], otherwise [rules]. The status check and the model call share one budget ([timeouts]);
 * when it runs out, or the model is busy, missing or wrong, the rules result is returned, so every call ends within its
 * budget plus the (instant) rules work. Logs the task, path, latency bucket and failure kind only ([log]).
 */
class AiRouter(
    private val nano: GeminiNanoAiGateway,
    private val rules: RulesAiGateway,
    private val preferences: AiPreferences,
    private val log: AiCallLog = AiCallLog.None,
    private val timeouts: AiTimeouts = AiTimeouts(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : AiGateway {
    /** The model's status (whatever the switch says), or UNAVAILABLE when AICore does not answer in time. */
    override suspend fun status(): AiStatus = guarded {
        withTimeoutOrNull(timeouts.status) { nano.status() }
    } ?: AiStatus(AiAvailability.UNAVAILABLE, AiStatus.REASON_TIMEOUT)

    /** Sends the download request only after the parent's consent ([AiSettings.downloadConsent]). */
    override suspend fun requestDownload(): Boolean = settings()?.downloadConsent == true &&
        guarded { withTimeoutOrNull(timeouts.download) { nano.requestDownload() } } == true

    override suspend fun kindness(text: String, tier: KidUiTier): KindnessVerdict =
        route(AiTask.KINDNESS, timeouts.kindness, { rules.kindness(text, tier) }) { nano.tryKindness(text, tier) }

    override suspend fun suggestChores(
        band: AgeBand,
        existingTitles: List<String>,
        currency: Currency.Fiat,
    ): List<ChoreSuggestion> = route(
        AiTask.CHORE_SUGGESTIONS,
        timeouts.suggestions,
        { rules.suggestChores(band, existingTitles, currency) },
    ) { nano.trySuggestChores(band, existingTitles, currency) }

    override suspend fun weeklySummaryResult(facts: WeeklyFacts): WeeklySummary =
        route(AiTask.WEEKLY_SUMMARY, timeouts.summary, { rules.weeklySummaryResult(facts) }) {
            nano.tryWeeklySummary(facts)
        }

    /** Always the rules path (text recognition, no model), whatever the switch says. */
    override suspend fun receiptPrefill(jpeg: ByteArray, currency: Currency.Fiat): ReceiptPrefill? {
        val start = timeSource.markNow()
        val result = guarded { rules.receiptPrefill(jpeg, currency) }
        log.record(AiTask.RECEIPT, AiPath.RULES, LatencyBucket.of(start.elapsedNow()), null)
        return result
    }

    private suspend fun <T> route(
        task: AiTask,
        budget: Duration,
        fallback: suspend () -> T,
        model: suspend () -> NanoAnswer<T>,
    ): T {
        val start = timeSource.markNow()
        val answer = when (settings()?.enabled) {
            null -> NanoAnswer.Failed(AiFailure.ERROR)

            false -> NanoAnswer.Failed(AiFailure.DISABLED)

            true -> guarded {
                withTimeoutOrNull(budget) {
                    val available = nano.status().availability == AiAvailability.AVAILABLE
                    if (available) model() else NanoAnswer.Failed(AiFailure.UNAVAILABLE)
                } ?: NanoAnswer.Failed(AiFailure.TIMEOUT)
            } ?: NanoAnswer.Failed(AiFailure.ERROR)
        }
        val result = when (answer) {
            is NanoAnswer.Answered -> answer.value
            is NanoAnswer.Failed -> fallback()
        }
        val path = if (answer is NanoAnswer.Answered) AiPath.NANO else AiPath.RULES
        log.record(task, path, LatencyBucket.of(start.elapsedNow()), (answer as? NanoAnswer.Failed)?.failure)
        return result
    }

    private suspend fun settings(): AiSettings? = guarded { preferences.current() }

    /** [block], or null when it throws anything but cancellation (AICore and DataStore failures never escape). */
    // Any failure of the optional path means "use the rules"; the exception may hold user text, so it is not logged.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun <T> guarded(block: suspend () -> T?): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        null
    }
}

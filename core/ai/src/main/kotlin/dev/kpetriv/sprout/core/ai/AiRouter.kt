package dev.kpetriv.sprout.core.ai

import dev.kpetriv.sprout.core.domainhousehold.WeeklyFacts
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.AgeBand
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The [AiGateway] the app uses (D-M6-1); the only one it can inject (audit L6). Per call: Gemini Nano ([nano]) when
 * [AiSettings.enabled], the device is not a kid's linked phone ([context], D-M6-34) and the model is
 * [AiAvailability.AVAILABLE]; otherwise [rules]. The settings read, the device check, the status check and the model
 * call share one budget ([timeouts]); when it runs out, or the model is busy, missing or wrong, the rules result is
 * returned, so every call ends within its budget plus the (instant) rules work. An AVAILABLE status is reused for
 * [STATUS_CACHE] so a kindness check does not ask AICore twice per message; any other status is asked again. Logs
 * the task, path, latency bucket and failure kind only ([log]).
 */
internal class AiRouter(
    private val nano: GeminiNanoAiGateway,
    private val rules: RulesAiGateway,
    private val preferences: AiPreferences,
    private val context: AiContext,
    private val log: AiCallLog = AiCallLog.None,
    private val timeouts: AiTimeouts = AiTimeouts(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : AiGateway {
    /** When the last AVAILABLE status was read; null when the last read was anything else. */
    @Volatile
    private var availableSince: TimeMark? = null

    /**
     * The model's status (whatever the switch says): UNAVAILABLE with [AiStatus.REASON_TIMEOUT] when AICore does not
     * answer in time, or with [AiStatus.REASON_AICORE_ERROR] when the call fails (review L9).
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Any failure reads as "AICore error".
    override suspend fun status(): AiStatus {
        val status = try {
            withTimeoutOrNull(timeouts.status) { freshStatus() }
                ?: AiStatus(AiAvailability.UNAVAILABLE, AiStatus.REASON_TIMEOUT)
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } catch (error: Exception) {
            null
        }
        return status ?: AiStatus(AiAvailability.UNAVAILABLE, AiStatus.REASON_AICORE_ERROR)
    }

    /**
     * Sends the download request only while AI is on and after the parent's consent ([AiSettings.enabled] and
     * [AiSettings.downloadConsent], D-M6-15); never from a kid's linked phone.
     */
    override suspend fun requestDownload(): Boolean {
        val allowed = settings()?.let { it.enabled && it.downloadConsent } == true && linkedKidDevice() == false
        return allowed && guarded { withTimeoutOrNull(timeouts.download) { nano.requestDownload() } } == true
    }

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
        val answer = guarded {
            withTimeoutOrNull(budget) { modelAnswer(model) } ?: NanoAnswer.Failed(AiFailure.TIMEOUT)
        } ?: NanoAnswer.Failed(AiFailure.ERROR)
        val result = when (answer) {
            is NanoAnswer.Answered -> answer.value
            is NanoAnswer.Failed -> fallback()
        }
        val path = if (answer is NanoAnswer.Answered) AiPath.NANO else AiPath.RULES
        log.record(task, path, LatencyBucket.of(start.elapsedNow()), (answer as? NanoAnswer.Failed)?.failure)
        return result
    }

    /** The checks in order (switch, device, status), then [model]; all inside the caller's budget. */
    @Suppress("ReturnCount") // One early answer per check.
    private suspend fun <T> modelAnswer(model: suspend () -> NanoAnswer<T>): NanoAnswer<T> {
        val settings = settings() ?: return NanoAnswer.Failed(AiFailure.ERROR)
        if (!settings.enabled) return NanoAnswer.Failed(AiFailure.DISABLED)
        when (linkedKidDevice()) {
            null -> return NanoAnswer.Failed(AiFailure.ERROR)
            true -> return NanoAnswer.Failed(AiFailure.KID_DEVICE)
            false -> Unit
        }
        val available = availableSince?.let { it.elapsedNow() < STATUS_CACHE } == true ||
            freshStatus().availability == AiAvailability.AVAILABLE
        return if (available) model() else NanoAnswer.Failed(AiFailure.UNAVAILABLE)
    }

    /** [GeminiNanoAiGateway.status], remembering when it was last AVAILABLE. */
    private suspend fun freshStatus(): AiStatus = nano.status().also { status ->
        availableSince = if (status.availability == AiAvailability.AVAILABLE) timeSource.markNow() else null
    }

    private suspend fun settings(): AiSettings? = guarded { preferences.current() }

    /** Null when the session cannot say (the caller then takes the rules path). */
    private suspend fun linkedKidDevice(): Boolean? = guarded { context.isLinkedKidDevice() }

    /**
     * [block], or null when it throws (AICore, DataStore and session failures never escape). A
     * [CancellationException] is rethrown only when this coroutine is itself cancelled: ML Kit's futures throw one when
     * AICore or Play services cancel a request, and that is a failure like any other (review M6).
     */
    // Any failure of the optional path means "use the rules"; the exception may hold user text, so it is not logged.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun <T> guarded(block: suspend () -> T?): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        currentCoroutineContext().ensureActive()
        null
    } catch (error: Exception) {
        null
    }

    companion object {
        /** How long an AVAILABLE status is reused before AICore is asked again (review L8). */
        val STATUS_CACHE: Duration = 30.seconds
    }
}

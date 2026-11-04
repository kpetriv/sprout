package dev.kpetriv.sprout.ai

import dev.kpetriv.sprout.core.ai.AiAvailability
import dev.kpetriv.sprout.core.ai.AiGateway
import dev.kpetriv.sprout.core.ai.AiPreferences
import dev.kpetriv.sprout.core.ai.AiSettings
import dev.kpetriv.sprout.core.ai.AiStatus
import dev.kpetriv.sprout.core.ai.ChoreSuggestion
import dev.kpetriv.sprout.core.ai.KindnessVerdict
import dev.kpetriv.sprout.core.ai.ReceiptPrefill
import dev.kpetriv.sprout.core.ai.ReceiptTextReader
import dev.kpetriv.sprout.core.ai.RecognizedLine
import dev.kpetriv.sprout.core.ai.RulesAiGateway
import dev.kpetriv.sprout.core.ai.WeeklySummary
import dev.kpetriv.sprout.core.domainhousehold.WeeklyFacts
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.AgeBand
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/**
 * An [AiGateway] that answers with the real rules ([RulesAiGateway], receipt lines from [receiptLines]) unless a test
 * overrides an answer, and records what it was asked.
 */
class FakeAiGateway(var status: AiStatus = AiStatus(AiAvailability.UNAVAILABLE, RulesAiGateway.REASON_RULES_ONLY)) :
    AiGateway {
    var receiptLines: List<RecognizedLine>? = null
    private val rules = RulesAiGateway(ReceiptTextReader { receiptLines }, locale = { Locale.UK })

    var kindnessAnswer: KindnessVerdict? = null
    var summaryAnswer: WeeklySummary? = null
    var receiptAnswer: (() -> ReceiptPrefill?)? = null
    var downloadAccepted = true

    val kindnessCalls = mutableListOf<Pair<String, KidUiTier>>()
    val suggestionCalls = mutableListOf<Triple<AgeBand, List<String>, Currency.Fiat>>()
    val summaryCalls = mutableListOf<WeeklyFacts>()
    val receiptCalls = mutableListOf<Pair<ByteArray, Currency.Fiat>>()
    var statusCalls = 0
    var downloadRequests = 0

    override suspend fun status(): AiStatus {
        statusCalls += 1
        return status
    }

    override suspend fun requestDownload(): Boolean {
        downloadRequests += 1
        return downloadAccepted
    }

    override suspend fun kindness(text: String, tier: KidUiTier): KindnessVerdict {
        kindnessCalls += text to tier
        return kindnessAnswer ?: rules.kindness(text, tier)
    }

    override suspend fun suggestChores(
        band: AgeBand,
        existingTitles: List<String>,
        currency: Currency.Fiat,
    ): List<ChoreSuggestion> {
        suggestionCalls += Triple(band, existingTitles, currency)
        return rules.suggestChores(band, existingTitles, currency)
    }

    override suspend fun weeklySummaryResult(facts: WeeklyFacts): WeeklySummary {
        summaryCalls += facts
        return summaryAnswer ?: rules.weeklySummaryResult(facts)
    }

    override suspend fun receiptPrefill(jpeg: ByteArray, currency: Currency.Fiat): ReceiptPrefill? {
        receiptCalls += jpeg to currency
        val answer = receiptAnswer
        return if (answer != null) answer() else rules.receiptPrefill(jpeg, currency)
    }
}

/** [AiPreferences] in memory. */
class FakeAiPreferences(initial: AiSettings = AiSettings()) : AiPreferences {
    val state = MutableStateFlow(initial)
    override val settings: Flow<AiSettings> = state

    override suspend fun setEnabled(enabled: Boolean) = state.update { it.copy(enabled = enabled) }

    override suspend fun setDownloadConsent(consent: Boolean) = state.update { it.copy(downloadConsent = consent) }
}

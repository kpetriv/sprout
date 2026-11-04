package dev.kpetriv.sprout.core.ai

import dev.kpetriv.sprout.core.domainhousehold.WeeklyFacts
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.AgeBand
import dev.kpetriv.sprout.core.model.KidUiTier
import java.util.Locale

/**
 * The deterministic [AiGateway] (D-M6-1): runs on every device, is the reference behaviour, and is what every model
 * answer falls back to. Pure Kotlin apart from [receiptReader] (on-device text recognition for receipts).
 *
 * @param locale the locale money symbols are written in (the device's by default).
 */
class RulesAiGateway(
    private val receiptReader: ReceiptTextReader,
    private val locale: () -> Locale = Locale::getDefault,
) : AiGateway {
    /** The rules path has no model: always [AiAvailability.UNAVAILABLE] with [REASON_RULES_ONLY]. */
    override suspend fun status(): AiStatus = AiStatus(AiAvailability.UNAVAILABLE, REASON_RULES_ONLY)

    override suspend fun requestDownload(): Boolean = false

    override suspend fun kindness(text: String, tier: KidUiTier): KindnessVerdict {
        val finding = KindnessRules.check(text)
        return if (finding.isKind) {
            KindnessVerdict.Ok
        } else {
            KindnessVerdict.Nudge(KindnessRules.suggestion(finding, tier, text))
        }
    }

    /**
     * The first [AiGateway.SUGGESTION_COUNT] (at most five) catalogue chores for [band] whose titles the household
     * does not use yet, in the catalogue's order.
     */
    override suspend fun suggestChores(
        band: AgeBand,
        existingTitles: List<String>,
        currency: Currency.Fiat,
    ): List<ChoreSuggestion> {
        val taken = existingTitles.map(AiOutputRules::titleKey).toSet()
        return ChoreCatalogue.entriesFor(band)
            .filter { AiOutputRules.titleKey(it.title) !in taken }
            .take(AiGateway.SUGGESTION_COUNT.last)
            .map { ChoreCatalogue.suggestion(it, currency) }
    }

    override suspend fun weeklySummaryResult(facts: WeeklyFacts): WeeklySummary =
        WeeklySummary(WeeklySummaryTemplate.text(facts, locale()), AiPath.RULES)

    override suspend fun receiptPrefill(jpeg: ByteArray, currency: Currency.Fiat): ReceiptPrefill? =
        receiptReader.read(jpeg)?.let { ReceiptParser.parse(it, currency) }

    companion object {
        /** [status] reason of the rules gateway, which has no model. */
        const val REASON_RULES_ONLY: String = "rules_only"
    }
}

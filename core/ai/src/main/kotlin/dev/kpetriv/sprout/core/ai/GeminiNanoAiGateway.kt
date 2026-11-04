package dev.kpetriv.sprout.core.ai

import dev.kpetriv.sprout.core.domainhousehold.WeeklyFacts
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.LedgerPolicy
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AgeBand
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.time.Duration

/**
 * What one model call came to: the [value] it produced, or the [failure] that sends the caller to the rules path.
 */
internal sealed interface NanoAnswer<out T> {
    data class Answered<T>(val value: T) : NanoAnswer<T>

    data class Failed(val failure: AiFailure) : NanoAnswer<Nothing>
}

/**
 * [AiGateway] on Gemini Nano through [PromptModel] (the ML Kit GenAI Prompt API, D-M6-8). Each text task sends fixed
 * instructions plus the user's text as JSON data ([Prompts]), asks for structured output ([KindnessOutput],
 * [ChoreIdeas], [SummaryOutput]) and checks the answer with [AiOutputRules]. A busy or missing model, a timeout
 * ([timeouts]) or an answer that fails validation gives the [rules] result instead. Receipts never use the model.
 *
 * Kindness combines both paths: the message is nudged when the rules or the model find it unkind, and the suggestion
 * is the model's when it passes [AiOutputRules.isValidSuggestion], otherwise the rules one (D-M6-12, D-M6-36). A
 * message the rules let through as a report or a plea for help ([KindnessRules.isReportOrPlea]) never reaches the
 * model (D-M6-52).
 *
 * The weekly summary is asked of the model only when [locale] is English, the one language [AiOutputRules] reads
 * numbers in (D-M6-35); otherwise the template is the answer.
 *
 * Internal, like [PromptModel]: the app reaches it only through [AiRouter] (audit L6).
 */
internal class GeminiNanoAiGateway(
    private val model: PromptModel,
    private val rules: RulesAiGateway,
    private val timeouts: AiTimeouts = AiTimeouts(),
    private val policy: () -> LedgerPolicy = { LedgerPolicy() },
    private val locale: () -> Locale = Locale::getDefault,
) : AiGateway {
    override suspend fun status(): AiStatus = model.status()

    override suspend fun requestDownload(): Boolean = model.download()

    override suspend fun kindness(text: String, tier: KidUiTier): KindnessVerdict =
        tryKindness(text, tier).valueOr { rules.kindness(text, tier) }

    override suspend fun suggestChores(
        band: AgeBand,
        existingTitles: List<String>,
        currency: Currency.Fiat,
    ): List<ChoreSuggestion> =
        trySuggestChores(band, existingTitles, currency).valueOr { rules.suggestChores(band, existingTitles, currency) }

    override suspend fun weeklySummaryResult(facts: WeeklyFacts): WeeklySummary =
        tryWeeklySummary(facts).valueOr { rules.weeklySummaryResult(facts) }

    override suspend fun receiptPrefill(jpeg: ByteArray, currency: Currency.Fiat): ReceiptPrefill? =
        rules.receiptPrefill(jpeg, currency)

    @Suppress("ReturnCount") // Blank, then a report or plea for help: both answered before the model.
    internal suspend fun tryKindness(text: String, tier: KidUiTier): NanoAnswer<KindnessVerdict> {
        if (text.isBlank()) return NanoAnswer.Answered(KindnessVerdict.Ok)
        val ruled = rules.kindness(text, tier)
        // A plea for help or a report of what someone else said is never the model's to nudge (D-M6-52).
        if (ruled == KindnessVerdict.Ok && KindnessRules.isReportOrPlea(text)) return NanoAnswer.Answered(ruled)
        return call(Prompts.kindness(text, tier), KindnessOutput::class, timeouts.kindness) { output ->
            val suggestion = output.suggestion.trim().takeIf { AiOutputRules.isValidSuggestion(it, text) }
            when {
                ruled is KindnessVerdict.Nudge -> KindnessVerdict.Nudge(suggestion ?: ruled.suggestion)

                // The model alone found it unkind: nudge only with a usable suggestion of its own.
                output.unkind && suggestion != null -> KindnessVerdict.Nudge(suggestion)

                else -> KindnessVerdict.Ok
            }
        }
    }

    internal suspend fun trySuggestChores(
        band: AgeBand,
        existingTitles: List<String>,
        currency: Currency.Fiat,
    ): NanoAnswer<List<ChoreSuggestion>> {
        val examples = rules.suggestChores(band, emptyList(), currency)
        val maxReward = minOf(ChoreCatalogue.maxRewardFor(band, currency), policy().maxAmount)
        val prompt = Prompts.chores(band, existingTitles, currency, maxReward, examples)
        return call(prompt, ChoreIdeas::class, timeouts.suggestions) { output ->
            val ideas = output.chores.mapNotNull(::toSuggestion)
            AiOutputRules.validChores(ideas, band, currency, existingTitles, policy()).ifEmpty { null }
        }
    }

    internal suspend fun tryWeeklySummary(facts: WeeklyFacts): NanoAnswer<WeeklySummary> {
        val locale = locale()
        if (locale.language != Locale.ENGLISH.language) return NanoAnswer.Failed(AiFailure.LANGUAGE)
        val draft = rules.weeklySummaryResult(facts).text
        return call(Prompts.summary(facts, draft, locale), SummaryOutput::class, timeouts.summary) { output ->
            // Line breaks between sentences are fine; any other control character still fails validation.
            val text = output.text.replace(whitespace, " ").trim()
            WeeklySummary(text, AiPath.NANO).takeIf { AiOutputRules.isValidSummary(text, facts) }
        }
    }

    /** Runs [request] within [budget] and turns the parsed output into a result with [accept] (null: invalid). */
    private suspend fun <O : Any, T : Any> call(
        request: PromptRequest,
        output: kotlin.reflect.KClass<O>,
        budget: Duration,
        accept: (O) -> T?,
    ): NanoAnswer<T> {
        val result = withTimeoutOrNull(budget) { model.generate(request, output) }
            ?: return NanoAnswer.Failed(AiFailure.TIMEOUT)
        return when (result) {
            is PromptResult.Success -> accept(result.value)?.let { NanoAnswer.Answered(it) }
                ?: NanoAnswer.Failed(AiFailure.INVALID_OUTPUT)

            PromptResult.Busy -> NanoAnswer.Failed(AiFailure.BUSY)

            PromptResult.Unavailable -> NanoAnswer.Failed(AiFailure.UNAVAILABLE)

            is PromptResult.Failed -> NanoAnswer.Failed(result.failure)
        }
    }

    /** [idea] as a suggestion, or null without a reward or with an unknown schedule. */
    private fun toSuggestion(idea: ChoreIdea): ChoreSuggestion? {
        val money = idea.reward.takeIf { it > 0 }?.let(::Money)
        val minutes = idea.minutes.takeIf { it > 0 }
        val schedule = when (idea.schedule) {
            "ANY_TIME" -> SuggestedSchedule.AnyTime
            "EVERY_DAY" -> SuggestedSchedule.Daily.EVERY_DAY
            "SCHOOL_DAYS" -> SuggestedSchedule.Daily.SCHOOL_DAYS
            "WEEKENDS" -> SuggestedSchedule.Daily(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
            "WEEKLY" -> DayOfWeek.entries.firstOrNull { it.name == idea.day }?.let(SuggestedSchedule::Weekly)
            else -> null
        }
        return if (schedule == null || (money == null && minutes == null)) {
            null
        } else {
            ChoreSuggestion(idea.title.trim(), idea.description.trim(), money, minutes, schedule)
        }
    }

    private inline fun <T> NanoAnswer<T>.valueOr(fallback: () -> T): T = when (this) {
        is NanoAnswer.Answered -> value
        is NanoAnswer.Failed -> fallback()
    }

    private companion object {
        /** ASCII whitespace runs, newlines included (`\s` without the Unicode flag). */
        val whitespace = Regex("\\s+")
    }
}

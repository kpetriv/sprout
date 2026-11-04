package dev.kpetriv.sprout.core.ai

import dev.kpetriv.sprout.core.domainhousehold.WeeklyFacts
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AgeBand
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.KidUiTier

/**
 * On-device AI (M6, ADR-0010): four typed tasks. [RulesAiGateway] answers every one deterministically on any device;
 * [GeminiNanoAiGateway] answers the text tasks with Gemini Nano (ML Kit GenAI Prompt API on AICore) where the device
 * has it, and falls back to the rules result whenever the model is busy, missing, slow or wrong. [AiRouter] picks one
 * per call and is the only one the app can inject. Inference runs on the device and nothing Sprout gives it leaves the
 * phone; the usage-metrics uploader ML Kit ships is removed from the app's manifest (D-M6-33). Receipt text recognition
 * is Google Play services code, which records one usage event per scan (never the picture or its text) that Sprout
 * cannot switch off, so it runs only while the On-device AI switch is on (D-M6-51).
 *
 * Every call may take up to its budget ([AiTimeouts]); call it from a ViewModel while the app is in the foreground
 * (AICore refuses background inference). Results are never persisted (D-M6-7) and their `toString` never shows text.
 */
interface AiGateway {
    /** Whether the on-device model can run here, now. The rules path is always available whatever this says. */
    suspend fun status(): AiStatus

    /**
     * Asks AICore to download the model; the system downloads it in the background, usually on Wi-Fi. True when the
     * request was accepted (or the model is already there), false when it was refused or nothing can be downloaded.
     */
    suspend fun requestDownload(): Boolean

    /**
     * Checks a kid's outgoing chat [text] (M4 kindness nudge). [KindnessVerdict.Nudge] carries a kinder message the
     * kid may send instead: at most [MAX_SUGGESTION_LENGTH] code points, in a register for [tier], never repeating the
     * flagged words. Blank text is always [KindnessVerdict.Ok].
     */
    suspend fun kindness(text: String, tier: KidUiTier): KindnessVerdict

    /**
     * Three to five chore ideas for a kid in [band]: titles at most `MAX_TITLE_LENGTH`, none equal (ignoring case and
     * spacing) to one of [existingTitles] or to each other, money rewards in [currency] within the ledger's bounds.
     * Fewer than three only when the catalogue for [band] has run out of titles the household does not use yet.
     */
    suspend fun suggestChores(
        band: AgeBand,
        existingTitles: List<String>,
        currency: Currency.Fiat,
    ): List<ChoreSuggestion>

    /** [weeklySummaryResult]'s text. */
    suspend fun weeklySummary(facts: WeeklyFacts): String = weeklySummaryResult(facts).text

    /**
     * Two to four sentences (at most [MAX_SUMMARY_LENGTH] code points) about [facts] for the parent dashboard, and
     * which path wrote them. Every number in the text is one of the facts (D-M6-3); the model writes it only on an
     * English device (D-M6-35).
     */
    suspend fun weeklySummaryResult(facts: WeeklyFacts): WeeklySummary

    /**
     * Reads a receipt photo ([jpeg]) with on-device text recognition and picks the largest amount on a "total" line
     * (in minor units of [currency]) and the merchant (the first line, at most [MAX_MERCHANT_LENGTH] characters).
     * Never a model call, and only while the On-device AI switch is on (D-M6-51). Null when the image could not be
     * read or holds neither, or the switch is off. The parent reviews and signs whatever it prefills (D-M6-5).
     */
    suspend fun receiptPrefill(jpeg: ByteArray, currency: Currency.Fiat): ReceiptPrefill?

    companion object {
        /** Longest kindness suggestion, in code points. */
        const val MAX_SUGGESTION_LENGTH: Int = 200

        /** Longest weekly summary, in code points. */
        const val MAX_SUMMARY_LENGTH: Int = 600

        /** Longest merchant name a receipt prefills. */
        const val MAX_MERCHANT_LENGTH: Int = 40

        /** Fewest and most chore suggestions in one answer. */
        val SUGGESTION_COUNT: IntRange = 3..5
    }
}

/** Whether Gemini Nano can run on this device (ML Kit `FeatureStatus`). */
enum class AiAvailability {
    /** Downloaded and ready. */
    AVAILABLE,

    /** Supported, not downloaded yet: [AiGateway.requestDownload] starts it (after the parent's consent). */
    DOWNLOADABLE,

    /** AICore is downloading it. */
    DOWNLOADING,

    /** Not supported on this device (or AICore is missing, too old or not answering). */
    UNAVAILABLE,
}

/**
 * [availability] and, when it is not [AiAvailability.AVAILABLE], a short machine-readable [reason] (one of the
 * `AiStatus.REASON_*` codes) for diagnostics. Never user text.
 */
data class AiStatus(val availability: AiAvailability, val reason: String? = null) {
    companion object {
        /** AICore answered UNAVAILABLE (unsupported device, or its configuration is not fetched yet). */
        const val REASON_NOT_SUPPORTED: String = "not_supported"

        /** AICore is missing, too old, or the call to it failed. */
        const val REASON_AICORE_ERROR: String = "aicore_error"

        /** The model runs but this build of it has no structured output, which every Sprout task needs. */
        const val REASON_NO_STRUCTURED_OUTPUT: String = "no_structured_output"

        /** The status call did not answer within its budget. */
        const val REASON_TIMEOUT: String = "timeout"

        val AVAILABLE: AiStatus = AiStatus(AiAvailability.AVAILABLE)
    }
}

/** Which implementation produced a result. */
enum class AiPath {
    /** Gemini Nano on AICore. */
    NANO,

    /** The deterministic rules ([RulesAiGateway]). */
    RULES,
}

/** The answer to a kindness check. */
sealed interface KindnessVerdict {
    /** Nothing unkind found. */
    data object Ok : KindnessVerdict

    /** Possibly unkind. [suggestion] is a kinder message the kid may send instead, or null when there is none. */
    data class Nudge(val suggestion: String?) : KindnessVerdict {
        override fun toString(): String = "Nudge(hasSuggestion=${suggestion != null})"
    }
}

/**
 * When a suggested chore is due; [toChoreSchedule] turns it into the editor's schedule.
 */
sealed interface SuggestedSchedule {
    fun toChoreSchedule(): ChoreSchedule

    /** Once, whenever the kid can. */
    data object AnyTime : SuggestedSchedule {
        override fun toChoreSchedule(): ChoreSchedule = ChoreSchedule.Once(dueOn = null)
    }

    /** Every one of [days]. */
    data class Daily(val days: Set<DayOfWeek>) : SuggestedSchedule {
        init {
            require(days.isNotEmpty()) { "a daily schedule needs at least one day" }
        }

        override fun toChoreSchedule(): ChoreSchedule = ChoreSchedule.Daily(days)

        companion object {
            val EVERY_DAY: Daily = Daily(DayOfWeek.entries.toSet())
            val SCHOOL_DAYS: Daily = Daily(DayOfWeek.entries.take(5).toSet())
        }
    }

    /** Once a week on [day]. */
    data class Weekly(val day: DayOfWeek) : SuggestedSchedule {
        override fun toChoreSchedule(): ChoreSchedule = ChoreSchedule.Weekly(day)
    }
}

/**
 * One chore idea. At least one of [money] (minor units of the requested currency) and [minutes] (screen time) is set
 * and positive. Filling the editor with it never saves anything; the parent edits and saves.
 */
data class ChoreSuggestion(
    val title: String,
    val description: String,
    val money: Money?,
    val minutes: Long?,
    val schedule: SuggestedSchedule,
) {
    init {
        require(money != null || minutes != null) { "a suggestion needs money, minutes or both" }
        require(money == null || money.isPositive) { "money must be positive" }
        require(minutes == null || minutes > 0) { "minutes must be positive" }
    }

    override fun toString(): String =
        "ChoreSuggestion(title=$REDACTED, description=$REDACTED, money=$money, minutes=$minutes, schedule=$schedule)"
}

/** A weekly summary and the [path] that wrote it. */
data class WeeklySummary(val text: String, val path: AiPath) {
    override fun toString(): String = "WeeklySummary(text=$REDACTED, path=$path)"
}

/**
 * What a receipt photo prefills in the manual adjustment sheet: the [amount] to debit (minor units of the household
 * currency, positive) and the [merchant]. Either may be null; the parent reviews both.
 */
data class ReceiptPrefill(val amount: Money?, val merchant: String?) {
    override fun toString(): String =
        "ReceiptPrefill(amount=$amount, merchant=${if (merchant == null) "null" else REDACTED})"
}

/** What `toString` prints in place of text that may hold personal data. */
internal const val REDACTED: String = "<redacted>"

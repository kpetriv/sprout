package dev.kpetriv.sprout.core.ai

import dev.kpetriv.sprout.core.domainhousehold.EarnedByKind
import dev.kpetriv.sprout.core.domainhousehold.KidWeeklyFacts
import dev.kpetriv.sprout.core.domainhousehold.WeeklyFacts
import dev.kpetriv.sprout.core.domainhousehold.WeeklyTotals
import dev.kpetriv.sprout.core.domainhousehold.monday
import dev.kpetriv.sprout.core.domainhousehold.sunday
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.LedgerPolicy
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AgeBand
import dev.kpetriv.sprout.core.model.MAX_DESCRIPTION_LENGTH
import dev.kpetriv.sprout.core.model.MAX_TITLE_LENGTH

/**
 * Hard bounds on model output (M6 §1, ADR-0010). Model text is untrusted: anything that fails here is replaced by the
 * rules result, never repaired. Pure and deterministic.
 */
@Suppress("TooManyFunctions") // One check per bound; they are the module's validation surface.
object AiOutputRules {
    /** Fewest and most sentences in a weekly summary. */
    val SUMMARY_SENTENCES: IntRange = 2..4

    private val url = Regex(
        "(?i)(?:\\b[a-z][a-z0-9+.-]*://|\\bwww\\.|\\b[a-z0-9-]+\\.(?:com|net|org|io|co|uk|app|dev|me|ly|gg|xyz|info|" +
            "biz|ru|cn|de|fr|tk|link|site|online|top|click)\\b)",
    )
    private val email = Regex("[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)+")
    private val phoneLike = Regex("\\+?\\d[\\d\\s().-]{6,}\\d")
    private const val MIN_PHONE_DIGITS = 8

    /** Phrases that read as instructions, role markers or markup rather than an answer to the task. */
    private val injection = listOf(
        Regex(
            "(?i)\\b(?:ignore|disregard|forget|override)\\b.{0,40}" +
                "\\b(?:instructions?|rules?|prompts?|above|previous)\\b",
        ),
        Regex("(?i)\\b(?:system|developer|assistant|user)\\s*(?:prompt|message|instructions?)?\\s*:"),
        Regex("(?i)\\b(?:as an ai|language model|i am an ai|i'm an ai|my instructions|system prompt|jailbreak)\\b"),
        Regex("(?i)\\byou (?:are|must|should) now\\b"),
        Regex("<\\|?/?[a-zA-Z_|]+\\|?>|\\[/?INST]|```|\\{\\{|}}|<script", RegexOption.IGNORE_CASE),
    )

    /** Length in Unicode code points (what the limits count), not UTF-16 units. */
    fun codePoints(text: String): Int = text.codePointCount(0, text.length)

    fun hasUrl(text: String): Boolean = url.containsMatchIn(text)

    fun hasEmail(text: String): Boolean = email.containsMatchIn(text)

    /** A run of at least eight digits with phone-number punctuation ("+44 20 7946 0000"). */
    fun hasPhoneNumber(text: String): Boolean =
        phoneLike.findAll(text).any { match -> match.value.count(Char::isDigit) >= MIN_PHONE_DIGITS }

    fun looksLikeInjection(text: String): Boolean = injection.any { it.containsMatchIn(text) }

    /** Control or format characters (other than a plain space): newlines, bidi overrides, zero-width marks. */
    fun hasControlCharacters(text: String): Boolean = text.any { char ->
        char != ' ' && (char.isISOControl() || Character.getType(char) == Character.FORMAT.toInt())
    }

    /**
     * Single-line text of 1 to [maxCodePoints] code points with no link, e-mail address, phone number, instruction-like
     * phrase or control character.
     */
    fun isCleanText(text: String, maxCodePoints: Int): Boolean =
        text.isNotBlank() && codePoints(text) <= maxCodePoints && !hasUrl(text) && !hasEmail(text) &&
            !hasPhoneNumber(text) && !looksLikeInjection(text) && !hasControlCharacters(text)

    /**
     * A model's kinder version of [original]: clean text of at most [AiGateway.MAX_SUGGESTION_LENGTH] code points
     * that passes the rules kindness check itself, repeats none of the words the rules flagged in [original], and is
     * not [original] again.
     */
    fun isValidSuggestion(suggestion: String, original: String): Boolean {
        val flagged = KindnessRules.check(original).words
        return isCleanText(suggestion, AiGateway.MAX_SUGGESTION_LENGTH) &&
            !suggestion.trim().equals(original.trim(), ignoreCase = true) &&
            KindnessRules.check(suggestion).isKind &&
            KindnessText.tokens(suggestion).none { token -> token.variants.any { it in flagged } }
    }

    /** A chore title as compared for duplicates: lower case, letters and digits only, single spaces. */
    fun titleKey(title: String): String =
        title.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").trim()
            .split(Regex("\\s+")).joinToString(" ")

    /**
     * Whether [suggestion] may be shown for [band]: clean title (at most [MAX_TITLE_LENGTH]) and description (at most
     * [MAX_DESCRIPTION_LENGTH]), a money reward in (0, min([ChoreCatalogue.maxRewardFor], [LedgerPolicy.maxAmount])],
     * minutes in (0, [ChoreCatalogue.MAX_MINUTES]], and a title that is not one of [taken] ([titleKey] form).
     */
    fun isValidChore(
        suggestion: ChoreSuggestion,
        band: AgeBand,
        currency: Currency.Fiat,
        taken: Set<String>,
        policy: LedgerPolicy = LedgerPolicy(),
    ): Boolean {
        val maxMoney = minOf(ChoreCatalogue.maxRewardFor(band, currency), policy.maxAmount)
        val money = suggestion.money
        val minutes = suggestion.minutes
        return isCleanText(suggestion.title, MAX_TITLE_LENGTH) &&
            suggestion.title.length <= MAX_TITLE_LENGTH &&
            isCleanText(suggestion.description, MAX_DESCRIPTION_LENGTH) &&
            suggestion.description.length <= MAX_DESCRIPTION_LENGTH &&
            (money == null || (money.isPositive && money <= maxMoney)) &&
            (minutes == null || minutes in 1..ChoreCatalogue.MAX_MINUTES) &&
            KindnessRules.check(suggestion.title + " " + suggestion.description).isKind &&
            titleKey(suggestion.title) !in taken
    }

    /**
     * The valid ones of [suggestions] ([isValidChore]), without repeats among themselves, at most
     * [AiGateway.SUGGESTION_COUNT] items; empty when fewer than its minimum survive.
     */
    fun validChores(
        suggestions: List<ChoreSuggestion>,
        band: AgeBand,
        currency: Currency.Fiat,
        existingTitles: List<String>,
        policy: LedgerPolicy = LedgerPolicy(),
    ): List<ChoreSuggestion> {
        val taken = existingTitles.map(::titleKey).toMutableSet()
        val valid = buildList {
            for (suggestion in suggestions) {
                if (size == AiGateway.SUGGESTION_COUNT.last) break
                if (isValidChore(suggestion, band, currency, taken, policy)) {
                    add(suggestion)
                    taken += titleKey(suggestion.title)
                }
            }
        }
        return valid.takeIf { it.size >= AiGateway.SUGGESTION_COUNT.first }.orEmpty()
    }

    /** Sentences in [text]: runs ending in `.`, `!` or `?` followed by a space or the end. */
    fun sentenceCount(text: String): Int =
        text.trim().split(Regex("(?<=[.!?])\\s+")).count { it.any(Char::isLetterOrDigit) }

    /**
     * A model's weekly summary: clean text of at most [AiGateway.MAX_SUMMARY_LENGTH] code points in [SUMMARY_SENTENCES]
     * sentences whose every number ([numbersIn]) is one of [facts]' ([numbersOf]).
     */
    fun isValidSummary(text: String, facts: WeeklyFacts): Boolean = isCleanText(text, AiGateway.MAX_SUMMARY_LENGTH) &&
        sentenceCount(text) in SUMMARY_SENTENCES &&
        numbersConsistent(text, facts)

    /** Whether every number written in [text] (digits or a spelled-out number from two up) is one of [facts]'. */
    fun numbersConsistent(text: String, facts: WeeklyFacts): Boolean {
        val allowed = numbersOf(facts)
        return numbersIn(text).all { readings -> readings.any { it in allowed } }
    }

    /**
     * Every number in [facts] in canonical form ([canonical]): counts, minutes, money in major units of the
     * household currency, and the week's own numbers (week number, year, the first and last days of the month).
     */
    fun numbersOf(facts: WeeklyFacts): Set<String> = buildSet {
        val digits = facts.currency.fractionDigits
        val rows = facts.kids.map(FactRow::of) + FactRow.of(facts.totals)
        rows.forEach { row ->
            row.amounts.forEach { add(canonical(majorUnits(it, digits))) }
            row.counts.forEach { add(it.toString()) }
        }
        add(facts.kids.size.toString())
        add(facts.week.week.toString())
        add(facts.week.weekBasedYear.toString())
        listOf(facts.week.monday(), facts.week.sunday(), facts.from, facts.through).forEach { date ->
            add(date.day.toString())
            add(date.year.toString())
        }
    }

    /**
     * Each number written in [text], as the set of canonical values it may mean: "1,250" is 1250 or 1.25, "3.50" is
     * 3.5; a spelled-out number ("two" to "ninety") is its value. "One" and "zero" are not counted: they are too often
     * not numbers ("no one").
     */
    fun numbersIn(text: String): List<Set<String>> {
        val written = Regex("\\d+(?:[.,]\\d+)*").findAll(text).map { match ->
            val token = match.value
            // Comma as thousands separator, dot as thousands separator, or comma as decimal point.
            setOf(token.replace(",", ""), token.replace(".", "").replace(',', '.'), token.replace(',', '.'))
                .filter { reading -> reading.count { it == '.' } <= 1 }
                .map(::canonical)
                .toSet()
        }
        val spelled = Regex("\\p{L}+").findAll(text.lowercase())
            .mapNotNull { spelledNumbers[it.value] }
            .map { setOf(it.toString()) }
        return (written + spelled).toList()
    }

    /** A decimal number with leading zeros of the whole part and trailing zeros of the fraction removed. */
    fun canonical(number: String): String {
        val whole = number.substringBefore('.').trimStart('0').ifEmpty { "0" }
        val fraction = number.substringAfter('.', "").trimEnd('0')
        return if (fraction.isEmpty()) whole else "$whole.$fraction"
    }

    /** [money] in major units with [digits] fraction digits, as a plain decimal ("3.50"). */
    fun majorUnits(money: Money, digits: Int): String {
        val negative = money.isNegative
        val text = money.minorUnits.toString().removePrefix("-").padStart(digits + 1, '0')
        val whole = text.dropLast(digits)
        val fraction = text.takeLast(digits)
        val unsigned = if (digits == 0) whole else "$whole.$fraction"
        return if (negative) "-$unsigned" else unsigned
    }

    private const val FIRST_SPELLED = 2
    private const val FIRST_SPELLED_TENS = 3
    private const val TEN = 10

    private const val UNITS_AND_TEENS =
        "two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen " +
            "eighteen nineteen twenty"
    private const val TENS = "thirty forty fifty sixty seventy eighty ninety"

    private val spelledNumbers: Map<String, Int> =
        UNITS_AND_TEENS.split(' ').mapIndexed { index, word -> word to index + FIRST_SPELLED }.toMap() +
            TENS.split(' ').mapIndexed { index, word -> word to (index + FIRST_SPELLED_TENS) * TEN }.toMap()

    /** The money amounts and the counts (minutes included) of one kid or of the household totals. */
    private class FactRow(val amounts: List<Money>, val counts: List<Long>) {
        companion object {
            fun of(kid: KidWeeklyFacts) = with(kid) {
                FactRow(
                    amounts = earned.amounts() + spentOnRewards + movedToSavings,
                    counts = listOf(choreMinutes.minorUnits, minutesUsed.minorUnits) +
                        listOf(choresSubmitted, choresApproved, choresRejected, gamesPlayed, anomalies)
                            .map(Int::toLong),
                )
            }

            fun of(totals: WeeklyTotals) = with(totals) {
                FactRow(
                    amounts = earned.amounts() + spentOnRewards + movedToSavings,
                    counts = listOf(choreMinutes.minorUnits, minutesUsed.minorUnits) +
                        listOf(choresSubmitted, choresApproved, choresRejected, gamesPlayed, anomalies)
                            .map(Int::toLong),
                )
            }

            private fun EarnedByKind.amounts() = listOf(chores, allowance, games, interest, total)
        }
    }
}

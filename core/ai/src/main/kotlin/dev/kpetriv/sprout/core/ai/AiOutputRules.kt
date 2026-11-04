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

    /** Top-level domains a "dot" spelled out in words is checked against. */
    private const val COMMON_TLDS = "com|net|org|io|co|uk|ai|app|me|gg|tv|us|info|xyz"

    /** A dot as written in a domain: ASCII, full-width, ideographic or half-width ideographic. */
    private const val DOT = "[.\\uFF0E\\u3002\\uFF61]"

    /** Domain-like words: "evil.ai", "bit.do/x", "evil . com", "evil[.]com", "evil。com", "evil dot com". */
    private val url = listOf(
        // A scheme, or "www.".
        Regex("(?i)\\b[a-z][a-z0-9+.-]*://|\\bwww$DOT"),
        // Any name, a dot and two or more letters ("e.g." and "a.m." have one letter after each dot).
        Regex("(?i)[\\p{L}\\p{N}-]+$DOT[a-z]{2,}\\b"),
        // Spaced or bracketed dots; ordinary prose never puts a space before a full stop.
        Regex("(?i)[\\p{L}\\p{N}-]{2,}(?:\\s+$DOT\\s*|\\s*[\\[(]\\s*$DOT\\s*[\\])]\\s*)[a-z]{2,}\\b"),
        // "evil dot com", "evil (dot) com".
        Regex("(?i)\\b[\\p{L}\\p{N}-]{2,}\\s*[\\[(]?\\s*dot\\s*[\\])]?\\s*(?:$COMMON_TLDS)\\b"),
    )
    private val email = Regex(
        "[\\p{L}\\p{N}._%+-]+\\s*[@\\uFF20]\\s*[\\p{L}\\p{N}-]+(?:$DOT[\\p{L}\\p{N}-]+)+",
    )

    /** Digits (any script) with phone punctuation, slashes included ("0794/600/0000"). */
    private val phoneLike = Regex("\\+?\\p{Nd}[\\p{Nd}\\s()./-]{6,}\\p{Nd}")

    /** A date, which has eight digits and slashes too but is not a phone number. */
    private val date = Regex("\\p{Nd}{1,4}([/.-])\\p{Nd}{1,2}\\1\\p{Nd}{1,4}")
    private const val MIN_PHONE_DIGITS = 8

    /** Character types that have no place in a single line of text, besides ISO controls. */
    private val invisibleTypes: Set<Int> = setOf(
        Character.FORMAT,
        Character.LINE_SEPARATOR,
        Character.PARAGRAPH_SEPARATOR,
        Character.PRIVATE_USE,
        Character.UNASSIGNED,
        Character.SURROGATE,
    ).map(Byte::toInt).toSet()

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

    fun hasUrl(text: String): Boolean = url.any { it.containsMatchIn(text) }

    fun hasEmail(text: String): Boolean = email.containsMatchIn(text)

    /**
     * A run of at least eight digits, in any script, with phone-number punctuation ("+44 20 7946 0000",
     * "0794/600/0000"); a date ("12/03/2026") is not one.
     */
    fun hasPhoneNumber(text: String): Boolean = phoneLike.findAll(text).any { match ->
        match.value.count(Char::isDigit) >= MIN_PHONE_DIGITS && !date.matches(match.value.trim())
    }

    fun looksLikeInjection(text: String): Boolean = injection.any { it.containsMatchIn(text) }

    /**
     * Control, format and other invisible characters: newlines, bidi overrides, zero-width marks, the Unicode line and
     * paragraph separators, private-use and unassigned code points, and unpaired surrogates.
     */
    fun hasControlCharacters(text: String): Boolean = text.codePoints().anyMatch { codePoint ->
        Character.isISOControl(codePoint) || Character.getType(codePoint) in invisibleTypes
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
     * sentences whose every number ([numbersIn]) is one of [facts]' ([numbersOf]), and that still says how many ledger
     * warnings need a look when there are any ([keepsLedgerWarnings]).
     */
    fun isValidSummary(text: String, facts: WeeklyFacts): Boolean = isCleanText(text, AiGateway.MAX_SUMMARY_LENGTH) &&
        sentenceCount(text) in SUMMARY_SENTENCES &&
        numbersConsistent(text, facts) &&
        keepsLedgerWarnings(text, facts)

    /**
     * Whether every number written in [text] is one of [facts]' (D-M6-35): no digit outside ASCII `0`-`9`
     * (Arabic-Indic, full-width and other scripts are refused, not read), none of the words that make an unchecked
     * quantity ([quantityWords]: "a hundred", "a dozen", "half", "twice"), and every number in digits or spelled out
     * from "two" up (a hyphenated or spaced compound such as "twenty-five" is one number) equal to a fact.
     */
    fun numbersConsistent(text: String, facts: WeeklyFacts): Boolean {
        val foreignDigit = text.codePoints().anyMatch { Character.isDigit(it) && it !in ASCII_DIGITS }
        val quantityWord = Regex("\\p{L}+").findAll(text.lowercase()).any { it.value in quantityWords }
        val allowed = numbersOf(facts)
        return !foreignDigit && !quantityWord && numbersIn(text).all { readings -> readings.any { it in allowed } }
    }

    /**
     * When [facts] have ledger anomalies, whether [text] still says "N ledger warning(s)" with their number: the
     * warning is a tamper signal the model must not drop (audit I2). Always true without anomalies.
     */
    fun keepsLedgerWarnings(text: String, facts: WeeklyFacts): Boolean {
        val anomalies = facts.totals.anomalies
        return anomalies == 0 || Regex("(?i)(?<![\\d.,])$anomalies\\s+ledger\\s+warnings?\\b").containsMatchIn(text)
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
     * 3.5; a spelled-out number ("two" to "ninety-nine") is its value, a tens word followed by a unit word
     * ("twenty-five", "twenty five", "thirty-one") being one number. "One" and "zero" alone are not counted: they are
     * too often not numbers ("no one").
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
        val compounds = Regex("\\b($TENS_PATTERN)[\\s-]+($UNITS_PATTERN)\\b")
        val lower = text.lowercase()
        val compound = compounds.findAll(lower).map { match ->
            val (tens, unit) = match.destructured
            setOf((spelledNumbers.getValue(tens) + units.getValue(unit)).toString())
        }
        val spelled = Regex("\\p{L}+").findAll(lower.replace(compounds, " "))
            .mapNotNull { spelledNumbers[it.value] }
            .map { setOf(it.toString()) }
        return (written + compound + spelled).toList()
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
    private const val TENS_PATTERN = "twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety"
    private const val UNITS_PATTERN = "one|two|three|four|five|six|seven|eight|nine"
    private val ASCII_DIGITS = '0'.code..'9'.code

    private val units: Map<String, Int> =
        UNITS_PATTERN.split('|').mapIndexed { index, word -> word to index + 1 }.toMap()

    /** Words for quantities the check cannot pin to a fact; a summary that uses one is refused (D-M6-35). */
    private val quantityWords: Set<String> = setOf(
        "hundred", "hundreds", "thousand", "thousands", "million", "millions", "billion", "billions", "dozen",
        "dozens", "half", "halves", "once", "twice", "thrice",
    )

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

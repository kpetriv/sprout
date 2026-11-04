package dev.kpetriv.sprout.core.ai

import dev.kpetriv.sprout.core.ai.KindnessLexicon.Category
import dev.kpetriv.sprout.core.model.KidUiTier

/**
 * One word of a message in the forms [KindnessRules] compares: [key] is lower case, letters only, with common
 * look-alike characters mapped back ("l0$er" to "loser"); [variants] adds the spellings with stretched letters folded
 * ("stuuupid", "fuuuck") and `1` read as `l` instead of `i`; [clause] counts the clauses before it (a clause ends at
 * `,`, `.`, `;`, `:`, `?` or a `!` that ends a word), so a negator in one clause never turns around a phrase in the
 * next ("I don't care, I hate you").
 */
internal class KindnessToken(val key: String, val variants: Set<String>, val clause: Int = 0)

/** Splits and normalises a message for [KindnessRules]. Pure; no locale is involved (keys are ASCII). */
internal object KindnessText {
    private val leet = mapOf(
        '0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '@' to 'a', '$' to 's', '!' to 'i',
    )
    private val edge = Regex("^[^\\p{L}\\p{N}@$*]+|[^\\p{L}\\p{N}@$*]+$")

    /** Splits words; `!` is not a separator because it stands for `i` inside a word ("sh!t"). */
    private val separator = Regex("[\\s,;:.?()\\[\\]{}\"/\\\\]+")

    /** Ends a clause: `,` `.` `;` `:` `?` `…`, a line break, or `!` at the end of a word (not "sh!t"). */
    private val clauseBreak = Regex("[,.;:?\\u2026\\n]+|!+(?=[\\s,.;:?]|$)")

    /** The message's words, in order, each with its clause number. */
    fun tokens(text: String): List<KindnessToken> = text.split(clauseBreak).flatMapIndexed { clause, part ->
        part.split(separator).mapNotNull { token(it, clause) }
    }

    /**
     * Runs of at least [KindnessLexicon.MIN_SPELLED_LETTERS] single-letter words, joined ("s t u p i d"), keyed by the
     * index of their first token.
     */
    fun spelledOut(tokens: List<KindnessToken>): List<Pair<Int, String>> {
        val runs = mutableListOf<Pair<Int, String>>()
        var run = StringBuilder()
        (tokens + KindnessToken("", emptySet())).forEachIndexed { index, token ->
            if (token.key.length == 1) {
                run.append(token.key)
            } else {
                if (run.length >= KindnessLexicon.MIN_SPELLED_LETTERS) runs += (index - run.length) to run.toString()
                run = StringBuilder()
            }
        }
        return runs
    }

    @Suppress("ReturnCount") // Nothing left after trimming, or after mapping: both mean "not a word".
    private fun token(raw: String, clause: Int): KindnessToken? {
        val trimmed = raw.lowercase().replace(edge, "")
        if (trimmed.isEmpty()) return null
        val hasLetter = trimmed.any { it.isLetter() }
        val mapped = if (hasLetter) trimmed.map { leet[it] ?: it }.joinToString("") else trimmed
        val key = mapped.filter { it in 'a'..'z' || it == '*' }
        if (key.isEmpty()) return null
        val variants = buildSet {
            add(key)
            add(collapse(key, from = 3, to = 1))
            add(collapse(key, from = 3, to = 2))
            add(collapse(key, from = 2, to = 1))
            // "1" reads as "i" in the key; "l" is the other common reading ("1oser").
            if ('1' in trimmed) {
                add(trimmed.map { if (it == '1') 'l' else leet[it] ?: it }.filter(::isKeyLetter).joinToString(""))
            }
        }
        return KindnessToken(key, variants, clause)
    }

    private fun isKeyLetter(char: Char): Boolean = char in 'a'..'z'

    /** Replaces every run of at least [from] equal letters with [to] of them. */
    private fun collapse(word: String, from: Int, to: Int): String = buildString {
        var index = 0
        while (index < word.length) {
            var end = index
            while (end < word.length && word[end] == word[index]) end++
            val run = end - index
            repeat(if (run >= from) to else run) { append(word[index]) }
            index = end
        }
    }
}

/** What [KindnessRules.check] found: the [categories] matched and the normalised [words] that matched them. */
internal data class KindnessFinding(val categories: Set<Category>, val words: Set<String>) {
    val isKind: Boolean get() = categories.isEmpty()

    /** The most serious category, which picks the suggestion. */
    val worst: Category? get() = categories.minByOrNull { it.ordinal }

    override fun toString(): String = "KindnessFinding(categories=$categories, words=${words.size})"

    companion object {
        val KIND: KindnessFinding = KindnessFinding(emptySet(), emptySet())
    }
}

/**
 * The deterministic kindness check ([KindnessLexicon]) and its suggestions. A finding never blocks a message: the kid
 * sees a nudge and may send anyway (M4). Suggestions are fixed sentences per category and tier, so they can never
 * repeat the words that were flagged; shouting alone gets the kid's own message, calmed down.
 *
 * A kid's message that asks for help or says they are unsafe ([KindnessLexicon.helpPhrases]) is never nudged, and
 * neither is anything after a word that reports what someone else said or did ([KindnessLexicon.reportingPhrases],
 * "Jake called me an idiot") or in a message that says where it was written ([KindnessLexicon.reportPlaces]):
 * those are the messages a parent most needs to get unchanged (D-M6-52).
 */
internal object KindnessRules {
    private const val MAX_MASK_STARS = 2

    /** How many words before a hostile phrase a negator turns it around ("I never said I hate you"). */
    private const val PHRASE_NEGATION_WINDOW = 3
    private const val MIN_MASK_LETTERS = 2
    private const val UP = "up"
    private val marks = Regex("[!?]{${KindnessLexicon.SHOUT_MIN_MARKS},}")
    private const val MIN_CALMED_LETTERS = 2

    /**
     * What is unkind in [text]. A kid's message ([allowReports], the default) gets the help and report carve-outs
     * (D-M6-52); model output checked by [AiOutputRules] does not, since a suggestion or a chore that quotes an
     * insult is never acceptable.
     */
    @Suppress("ReturnCount") // Blank, then a plea for help: both kind before any matching.
    fun check(text: String, allowReports: Boolean = true): KindnessFinding {
        if (text.isBlank()) return KindnessFinding.KIND
        val tokens = KindnessText.tokens(text)
        if (allowReports && asksForHelp(tokens)) return KindnessFinding.KIND
        // Matches after this token index are reported speech; NONE when nothing reports.
        return matches(text, tokens, reportedAfter = if (allowReports) reportStart(tokens) else NONE)
    }

    /** Every match in [tokens] up to the token index [reportedAfter] (shouting only when nothing reports). */
    private fun matches(text: String, tokens: List<KindnessToken>, reportedAfter: Int): KindnessFinding {
        val categories = mutableSetOf<Category>()
        val words = mutableSetOf<String>()
        fun flag(category: Category, word: String, at: Int) {
            if (at > reportedAfter) return
            categories += category
            words += word
        }
        tokens.forEachIndexed { index, token ->
            wordCategory(token)?.let { flag(it, token.key, index) }
            if (putDownAt(tokens, index)) flag(Category.PUT_DOWN, token.key, index)
        }
        KindnessText.spelledOut(tokens).forEach { (start, word) ->
            val spelled = KindnessToken(word, setOf(word))
            (wordCategory(spelled) ?: Category.PUT_DOWN.takeIf { word in KindnessLexicon.negative })
                ?.let { flag(it, word, start) }
        }
        hostilePhrases(tokens).forEach { (start, phrase) -> flag(Category.HOSTILE, phrase, start) }
        phrases(tokens, KindnessLexicon.profanePhrases).forEach { (start, phrase) ->
            flag(Category.PROFANITY, phrase, start)
        }
        if (reportedAfter == NONE && isShouting(text)) categories += Category.SHOUTING
        return KindnessFinding(categories, words)
    }

    /**
     * Whether [text] asks for help, says the kid is unsafe, or reports what someone else said or did anywhere: the
     * model path never nudges such a message on its own (D-M6-52).
     */
    fun isReportOrPlea(text: String): Boolean {
        val tokens = KindnessText.tokens(text)
        return asksForHelp(tokens) || reportStart(tokens) != NONE
    }

    /** Whether [tokens] hold one of [KindnessLexicon.helpPhrases]. */
    private fun asksForHelp(tokens: List<KindnessToken>): Boolean =
        phrases(tokens, KindnessLexicon.helpPhrases).isNotEmpty()

    /**
     * The index of the first [KindnessLexicon.reportingPhrases] word, -1 when the message names a
     * [KindnessLexicon.reportPlaces] place (all of it is a report), or [NONE].
     */
    private fun reportStart(tokens: List<KindnessToken>): Int = when {
        phrases(tokens, KindnessLexicon.reportPlaces).isNotEmpty() -> -1
        else -> phrases(tokens, KindnessLexicon.reportingPhrases).minOfOrNull { it.first } ?: NONE
    }

    private const val NONE = Int.MAX_VALUE

    /**
     * A kinder message for [finding] in [tier]'s register, or null when [finding] is kind. Never contains a flagged
     * word: every sentence is fixed, except that shouting alone gets [text] back in lower case with the marks calmed.
     */
    fun suggestion(finding: KindnessFinding, tier: KidUiTier, text: String): String? = when (finding.worst) {
        null -> null
        Category.HOSTILE -> hostile.getValue(tier)
        Category.PROFANITY -> profane.getValue(tier)
        Category.INSULT, Category.PUT_DOWN -> putDown.getValue(tier)
        Category.SHOUTING -> calmed(text)?.takeIf { check(it).isKind } ?: shouting.getValue(tier)
    }

    /** Every fixed suggestion sentence, for tests. */
    internal val fixedSuggestions: List<String>
        get() = listOf(hostile, profane, putDown, shouting).flatMap { it.values }

    private val hostile = mapOf(
        KidUiTier.SEEDLING to "I'm upset. Can we talk later?",
        KidUiTier.SPROUT to "I'm really upset right now. Can we talk about it later?",
        KidUiTier.SAPLING to "I'm really frustrated and need a break. Can we talk about this later?",
    )
    private val profane = mapOf(
        KidUiTier.SEEDLING to "I'm cross right now.",
        KidUiTier.SPROUT to "I'm really annoyed right now.",
        KidUiTier.SAPLING to "I'm really annoyed about this right now.",
    )
    private val putDown = mapOf(
        KidUiTier.SEEDLING to "I didn't like that. Please stop.",
        KidUiTier.SPROUT to "That upset me. Can you please stop?",
        KidUiTier.SAPLING to "That really bothered me. Can we sort it out?",
    )
    private val shouting = mapOf(
        KidUiTier.SEEDLING to "Can we talk?",
        KidUiTier.SPROUT to "Can we talk about it?",
        KidUiTier.SAPLING to "Can we talk about this calmly?",
    )

    private fun wordCategory(token: KindnessToken): Category? {
        val forms = token.variants
        val masked = token.key.contains('*')
        return when {
            forms.any { it in KindnessLexicon.allowed } -> null
            forms.any { it in KindnessLexicon.profanity } -> Category.PROFANITY
            forms.any { form -> KindnessLexicon.profanityStems.any(form::startsWith) } -> Category.PROFANITY
            masked && maskedProfanity(token.key) -> Category.PROFANITY
            forms.any { it in KindnessLexicon.insults } -> Category.INSULT
            else -> null
        }
    }

    /**
     * "f*ck", "sh*tty": a word with one or two `*` and at least two letters that matches a swear word, or starts like a
     * swear-word stem, with each star read as any letter.
     */
    private fun maskedProfanity(key: String): Boolean {
        val stars = key.count { it == '*' }
        if (stars > MAX_MASK_STARS || key.length - stars < MIN_MASK_LETTERS) return false
        fun masks(word: String) = Regex(word.replace("*", "[a-z]"))
        return KindnessLexicon.profanity.any(masks(key)::matches) ||
            KindnessLexicon.profanityStems.any { stem ->
                key.length >= stem.length && masks(key.take(stem.length)).matches(stem)
            }
    }

    /** Whether the word at [index] is a [KindnessLexicon.negative] word aimed at the reader. */
    @Suppress("ReturnCount") // Not a negative word; nothing before the fillers; then the decision.
    private fun putDownAt(tokens: List<KindnessToken>, index: Int): Boolean {
        val word = tokens[index]
        if (word.variants.none { it in KindnessLexicon.negative }) return false
        val isName = word.variants.any { it in KindnessLexicon.negativeNames }
        val isVerb = word.variants.any { it in KindnessLexicon.verbs }
        val fillers = fillersBefore(tokens, index)
        val cursor = index - 1 - fillers
        if (cursor < 0 || tokens[cursor].clause != word.clause) return false
        val before = tokens[cursor].key
        // "ur" straight before a noun-like word is "your" ("ur baby brother").
        return when {
            before in KindnessLexicon.negators -> false
            before == KindnessLexicon.UR && fillers == 0 && isName -> false
            before in KindnessLexicon.youAre -> true
            before in KindnessLexicon.you -> aimedAtYou(tokens, cursor, fillers, isVerb)
            before in KindnessLexicon.copulas -> targetsReader(tokens, cursor)
            else -> false
        }
    }

    /**
     * How many [KindnessLexicon.fillers] (at most [KindnessLexicon.MAX_FILLERS]) come right before [index], within its
     * clause.
     */
    private fun fillersBefore(tokens: List<KindnessToken>, index: Int): Int {
        val clause = tokens[index].clause
        var fillers = 0
        while (fillers < KindnessLexicon.MAX_FILLERS) {
            val before = tokens.getOrNull(index - 1 - fillers)
            if (before == null || before.clause != clause || before.key !in KindnessLexicon.fillers) break
            fillers++
        }
        return fillers
    }

    /**
     * After "you" at [you] and [fillers] fillers: asked ("are you (so) stupid?"), direct ("you stupid", but not a verb:
     * "you mean the red one?"), or with fillers at the start of a clause ("you so dumb", "you a loser").
     */
    private fun aimedAtYou(tokens: List<KindnessToken>, you: Int, fillers: Int, isVerb: Boolean): Boolean {
        val previous = tokens.getOrNull(you - 1)?.takeIf { it.clause == tokens[you].clause }
        return when {
            previous?.key in KindnessLexicon.copulas -> true
            fillers == 0 -> !isVerb
            else -> previous == null && !isVerb
        }
    }

    /** Whether the verb at [verb] has "you" right before it, or "your" and one or two words ("your drawing is"). */
    private fun targetsReader(tokens: List<KindnessToken>, verb: Int): Boolean =
        tokens.getOrNull(verb - 1)?.key in KindnessLexicon.you ||
            (1..KindnessLexicon.MAX_POSSESSED_WORDS).any { words ->
                tokens.getOrNull(verb - 1 - words)?.key in KindnessLexicon.your
            }

    /**
     * The [KindnessLexicon.hostilePhrases] in [tokens], unless a [KindnessLexicon.phraseNegators] word comes up to
     * [PHRASE_NEGATION_WINDOW] words before it in the same clause, or it is "hit you up" slang.
     */
    private fun hostilePhrases(tokens: List<KindnessToken>): List<Pair<Int, String>> =
        phrases(tokens, KindnessLexicon.hostilePhrases) { start, phrase ->
            val clause = tokens[start].clause
            val negated = (1..PHRASE_NEGATION_WINDOW).any { back ->
                tokens.getOrNull(start - back)?.takeIf { it.clause == clause }?.key in KindnessLexicon.phraseNegators
            }
            val slang = phrase.any { it in KindnessLexicon.upSlang } &&
                tokens.getOrNull(start + phrase.size)?.key == UP
            negated || slang
        }

    /**
     * Every match of one of [phrases] in [tokens] (each word compared with its variants), except [skip]ped ones, with
     * the index of its first token.
     */
    private fun phrases(
        tokens: List<KindnessToken>,
        phrases: List<List<String>>,
        skip: (start: Int, phrase: List<String>) -> Boolean = { _, _ -> false },
    ): List<Pair<Int, String>> = buildList {
        val keys = tokens.map { it.key }
        for (phrase in phrases) {
            for (start in 0..keys.size - phrase.size) {
                val matches = phrase.indices.all { offset -> phrase[offset] in tokens[start + offset].variants }
                if (matches && !skip(start, phrase)) {
                    add(start to keys.subList(start, start + phrase.size).joinToString(" "))
                }
            }
        }
    }

    private fun isShouting(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        val cased = letters.filter { it.isUpperCase() || it.isLowerCase() }
        val allCaps = cased.length >= KindnessLexicon.SHOUT_MIN_LETTERS && cased.all { it.isUpperCase() }
        val bangs = marks.findAll(text).any { run -> run.value.count { it == '!' } >= KindnessLexicon.SHOUT_MIN_BANGS }
        return allCaps || bangs
    }

    /**
     * [text] in lower case (first letter capital) with runs of `!`/`?` cut to one, if it fits a suggestion and has at
     * least [MIN_CALMED_LETTERS] letters ("!!!" alone would become "!").
     */
    private fun calmed(text: String): String? {
        val lower = text.trim().lowercase().replace(Regex("([!?])[!?]+"), "$1")
        val sentence = lower.replaceFirstChar { it.uppercaseChar() }
        return sentence.takeIf {
            it.count(Char::isLetter) >= MIN_CALMED_LETTERS &&
                it.codePointCount(0, it.length) <= AiGateway.MAX_SUGGESTION_LENGTH
        }
    }
}

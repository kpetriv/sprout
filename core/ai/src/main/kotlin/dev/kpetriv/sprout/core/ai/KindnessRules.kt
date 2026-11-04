package dev.kpetriv.sprout.core.ai

import dev.kpetriv.sprout.core.ai.KindnessLexicon.Category
import dev.kpetriv.sprout.core.model.KidUiTier

/**
 * One word of a message in the forms [KindnessRules] compares: [key] is lower case, letters only, with common
 * look-alike characters mapped back ("l0$er" to "loser"); [variants] adds the spellings with stretched letters folded
 * ("stuuupid", "fuuuck") and `1` read as `l` instead of `i`.
 */
internal class KindnessToken(val key: String, val variants: Set<String>)

/** Splits and normalises a message for [KindnessRules]. Pure; no locale is involved (keys are ASCII). */
internal object KindnessText {
    private val leet = mapOf(
        '0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '@' to 'a', '$' to 's', '!' to 'i',
    )
    private val edge = Regex("^[^\\p{L}\\p{N}@$*]+|[^\\p{L}\\p{N}@$*]+$")

    /** Splits words; `!` is not a separator because it stands for `i` inside a word ("sh!t"). */
    private val separator = Regex("[\\s,;:.?()\\[\\]{}\"/\\\\]+")

    /** The message's words, in order. */
    fun tokens(text: String): List<KindnessToken> = text.split(separator).mapNotNull(::token)

    /** Runs of at least [KindnessLexicon.MIN_SPELLED_LETTERS] single-letter words, joined ("s t u p i d"). */
    fun spelledOut(tokens: List<KindnessToken>): List<String> {
        val runs = mutableListOf<String>()
        var run = StringBuilder()
        for (token in tokens + KindnessToken("", emptySet())) {
            if (token.key.length == 1) {
                run.append(token.key)
            } else {
                if (run.length >= KindnessLexicon.MIN_SPELLED_LETTERS) runs += run.toString()
                run = StringBuilder()
            }
        }
        return runs
    }

    @Suppress("ReturnCount") // Nothing left after trimming, or after mapping: both mean "not a word".
    private fun token(raw: String): KindnessToken? {
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
        return KindnessToken(key, variants)
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
 */
internal object KindnessRules {
    private const val MAX_MASK_STARS = 2

    /** How many words before a hostile phrase a negator turns it around ("I never said I hate you"). */
    private const val PHRASE_NEGATION_WINDOW = 3
    private const val MIN_MASK_LETTERS = 2
    private val marks = Regex("[!?]{${KindnessLexicon.SHOUT_MIN_MARKS},}")

    fun check(text: String): KindnessFinding {
        if (text.isBlank()) return KindnessFinding.KIND
        val tokens = KindnessText.tokens(text)
        val categories = mutableSetOf<Category>()
        val words = mutableSetOf<String>()
        fun flag(category: Category, word: String) {
            categories += category
            words += word
        }
        tokens.forEachIndexed { index, token ->
            wordCategory(token)?.let { flag(it, token.key) }
            if (putDownAt(tokens, index)) flag(Category.PUT_DOWN, token.key)
        }
        KindnessText.spelledOut(tokens).forEach { word ->
            val spelled = KindnessToken(word, setOf(word))
            (wordCategory(spelled) ?: Category.PUT_DOWN.takeIf { word in KindnessLexicon.negative })
                ?.let { flag(it, word) }
        }
        hostilePhrases(tokens).forEach { flag(Category.HOSTILE, it) }
        if (isShouting(text)) categories += Category.SHOUTING
        return KindnessFinding(categories, words)
    }

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
        if (tokens[index].variants.none { it in KindnessLexicon.negative }) return false
        val isName = tokens[index].variants.any { it in KindnessLexicon.negativeNames }
        // Walk back over fillers to the verb or to "you".
        var cursor = index - 1
        var fillers = 0
        while (cursor >= 0 && tokens[cursor].key in KindnessLexicon.fillers && fillers < KindnessLexicon.MAX_FILLERS) {
            cursor--
            fillers++
        }
        if (cursor < 0) return false
        val before = tokens[cursor].key
        return when {
            before in KindnessLexicon.negators -> false
            before in KindnessLexicon.youAre -> true
            before in KindnessLexicon.you -> isName && fillers == 0
            before in KindnessLexicon.copulas -> targetsReader(tokens, cursor)
            else -> false
        }
    }

    /** Whether the verb at [verb] has "you" right before it, or "your" and one or two words ("your drawing is"). */
    private fun targetsReader(tokens: List<KindnessToken>, verb: Int): Boolean =
        tokens.getOrNull(verb - 1)?.key in KindnessLexicon.you ||
            (1..KindnessLexicon.MAX_POSSESSED_WORDS).any { words ->
                tokens.getOrNull(verb - 1 - words)?.key in KindnessLexicon.your
            }

    private fun hostilePhrases(tokens: List<KindnessToken>): List<String> = buildList {
        val keys = tokens.map { it.key }
        for (phrase in KindnessLexicon.hostilePhrases) {
            for (start in 0..keys.size - phrase.size) {
                val matches = phrase.indices.all { offset -> phrase[offset] in tokens[start + offset].variants }
                val negated = (1..PHRASE_NEGATION_WINDOW).any {
                    keys.getOrNull(start - it) in KindnessLexicon.phraseNegators
                }
                if (matches && !negated) add(keys.subList(start, start + phrase.size).joinToString(" "))
            }
        }
    }

    private fun isShouting(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        val cased = letters.filter { it.isUpperCase() || it.isLowerCase() }
        val allCaps = cased.length >= KindnessLexicon.SHOUT_MIN_LETTERS && cased.all { it.isUpperCase() }
        return allCaps || marks.containsMatchIn(text)
    }

    /** [text] in lower case (first letter capital) with runs of `!`/`?` cut to one, if it fits a suggestion. */
    private fun calmed(text: String): String? {
        val lower = text.trim().lowercase().replace(Regex("([!?])[!?]+"), "$1")
        val sentence = lower.replaceFirstChar { it.uppercaseChar() }
        return sentence.takeIf {
            it.isNotBlank() && it.codePointCount(0, it.length) <= AiGateway.MAX_SUGGESTION_LENGTH
        }
    }
}

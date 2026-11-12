package dev.kpetriv.sprout.core.games

/** One question in a [GameSession]. */
sealed interface Round {
    val kind: GameKind
}

/**
 * A sum to solve, or (Seedling counting) a number of dots to count.
 *
 * @property prompt what is shown, for example `"7 + 5"`, `"3 × 4"`, `"(12 − 4) × 3"` or `"How many?"`. Operators are
 *   `+`, `−` (U+2212), `×` and `÷`; numbers in the prompt are never negative.
 * @property spokenPrompt the same in words for read-aloud, for example `"7 plus 5"`.
 * @property operands the numbers in [prompt], left to right; empty for counting.
 * @property answer the correct answer. Never negative below Sapling.
 * @property choices four distinct options including [answer] (Seedling and Sprout), or null for a typed answer
 *   (Sapling, where the answer may be negative).
 * @property dots the number of dots to draw for a counting round (1–10), null otherwise.
 */
data class ArithmeticRound(
    val prompt: String,
    val spokenPrompt: String,
    val operands: List<Int>,
    val answer: Int,
    val choices: List<Int>?,
    val dots: Int? = null,
) : Round {
    override val kind: GameKind get() = GameKind.ARITHMETIC
}

/**
 * A sequence to watch and then tap back in order. No symbol repeats within a sequence.
 *
 * @property choices the buttons offered: every symbol of [sequence], shuffled, plus (Sapling only) two symbols that are
 *   not in the sequence.
 */
data class MemoryRound(val sequence: List<Symbol>, val choices: List<Symbol>) : Round {
    override val kind: GameKind get() = GameKind.MEMORY

    /** The choices that are not part of the sequence (empty below Sapling). */
    val distractors: List<Symbol> get() = choices.filterNot { it in sequence }
}

/**
 * A word to unscramble. [scrambled] is a permutation of [word] and never equal to it.
 *
 * @property hint a one-word category such as `"animal"` (Sprout), or null (Sapling).
 */
data class WordRound(val word: String, val scrambled: String, val hint: String?) : Round {
    override val kind: GameKind get() = GameKind.WORDS

    /**
     * Whether [answer] (any case) solves the round: [word] itself, or another listed word spelled with the same
     * letters. The list has no such pairs ([WordList]), so this is belt and braces against one being added.
     */
    fun accepts(answer: String): Boolean {
        val candidate = answer.lowercase()
        if (candidate == word) return true
        return candidate.toList().sorted() == word.toList().sorted() && WordList.all.any { it.word == candidate }
    }
}

/**
 * One Times tables question, `a × b`, always answered by typing (no choices).
 *
 * @property a the table (2–12), always on the left.
 * @property b the multiplier (1–12).
 * @property answer `a * b`.
 * @property prompt what is shown, for example `"7 × 8"` (`×` is U+00D7, as in [ArithmeticRound.prompt]).
 * @property spokenPrompt the same in words for TalkBack, for example `"7 times 8"`.
 */
data class TablesRound(val a: Int, val b: Int, val answer: Int, val prompt: String, val spokenPrompt: String) : Round {
    override val kind: GameKind get() = GameKind.TABLES

    init {
        require(answer == a * b) { "A times-table answer must be the product" }
    }

    /** Whether the typed number [typed] is right. */
    fun accepts(typed: Int): Boolean = typed == answer

    /**
     * Whether [typed] (digits as entered on the number pad, surrounding spaces ignored) is right. Compared as an
     * integer, so `"056"` is 56; anything that is not a whole number is wrong.
     */
    fun accepts(typed: String): Boolean = typed.trim().toIntOrNull()?.let(::accepts) ?: false

    companion object {
        /** The question `a × b` with its answer and prompts. */
        fun of(a: Int, b: Int): TablesRound = TablesRound(a, b, a * b, "$a × $b", "$a times $b")
    }
}

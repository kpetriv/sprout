package dev.kpetriv.sprout.core.games

import kotlin.random.Random

/** Builds arithmetic rounds. Every random draw comes from the session's seeded [Random]. */
internal object ArithmeticGenerator {
    private const val PLUS = "+"
    private const val MINUS = "−"
    private const val TIMES = "×"
    private const val DIVIDE = "÷"
    private const val COUNTING_PROMPT = "How many?"

    private const val CHOICE_COUNT = 4
    private const val NEAR_SPREAD = 3
    private const val SPROUT_TENS = 10

    // Seedling: counting 1..10, sums and differences within 10.
    private const val SEEDLING_MAX = 10
    private val seedlingRange = 0..SEEDLING_MAX
    private val countingRange = 1..SEEDLING_MAX

    // Sprout: add/subtract within 100, times tables 2..12.
    private const val SPROUT_MAX = 100
    private const val SUBTRAHEND_MIN = 2
    private const val TABLE_MIN = 2
    private const val TABLE_MAX = 12
    private val nonNegative = 0..Int.MAX_VALUE

    // Sapling: two-step expressions.
    private const val SMALL_MIN = 2
    private const val SMALL_MAX = 9
    private const val TERM_MAX = 20
    private const val ADDEND_MAX = 50

    /** Distinct-prompt attempts per round before accepting a repeat (counting has only ten values). */
    private const val UNIQUE_ATTEMPTS = 20

    fun session(difficulty: Difficulty, rounds: Int, random: Random): List<ArithmeticRound> {
        val result = mutableListOf<ArithmeticRound>()
        repeat(rounds) {
            var round = generate(difficulty, random)
            var attempts = 1
            while (result.any { it.prompt == round.prompt && it.dots == round.dots } && attempts < UNIQUE_ATTEMPTS) {
                round = generate(difficulty, random)
                attempts++
            }
            result += round
        }
        return result
    }

    fun generate(difficulty: Difficulty, random: Random): ArithmeticRound = when (difficulty) {
        Difficulty.SEEDLING -> seedling(random)
        Difficulty.SPROUT -> sprout(random)
        Difficulty.SAPLING -> sapling(random)
    }

    private enum class SeedlingForm { COUNT, ADD, SUBTRACT }

    private enum class SproutForm { ADD, SUBTRACT, TIMES_TABLE }

    /** Mixed two-step forms. Names read left to right: BRACKET_ADD_TIMES is `(a + b) × c`. */
    private enum class SaplingForm {
        BRACKET_ADD_TIMES,
        BRACKET_SUBTRACT_TIMES,
        TIMES_ADD,
        TIMES_SUBTRACT,
        DIVIDE_ADD,
        DIVIDE_SUBTRACT,
        SUBTRACT_TIMES,
        BRACKET_ADD_DIVIDE,
    }

    private fun seedling(random: Random): ArithmeticRound = when (SeedlingForm.entries.random(random)) {
        SeedlingForm.COUNT -> {
            val count = random.nextInt(countingRange.first, countingRange.last + 1)
            ArithmeticRound(
                prompt = COUNTING_PROMPT,
                spokenPrompt = COUNTING_PROMPT,
                operands = emptyList(),
                answer = count,
                choices = choices(count, emptyList(), countingRange, random),
                dots = count,
            )
        }

        SeedlingForm.ADD -> {
            val a = random.nextInt(1, SEEDLING_MAX)
            val b = random.nextInt(1, SEEDLING_MAX - a + 1)
            binary(a, PLUS, b, a + b, listOf(a - b, a, b), seedlingRange, random)
        }

        SeedlingForm.SUBTRACT -> {
            val a = random.nextInt(1, SEEDLING_MAX + 1)
            val b = random.nextInt(1, a + 1)
            binary(a, MINUS, b, a - b, listOf(a + b, a, b), seedlingRange, random)
        }
    }

    private fun sprout(random: Random): ArithmeticRound = when (SproutForm.entries.random(random)) {
        SproutForm.ADD -> {
            val a = random.nextInt(1, SPROUT_MAX)
            val b = random.nextInt(1, SPROUT_MAX - a + 1)
            val sum = a + b
            binary(a, PLUS, b, sum, listOf(sum + SPROUT_TENS, sum - SPROUT_TENS, a - b), nonNegative, random)
        }

        SproutForm.SUBTRACT -> {
            val a = random.nextInt(SUBTRAHEND_MIN, SPROUT_MAX + 1)
            val b = random.nextInt(1, a)
            val diff = a - b
            binary(a, MINUS, b, diff, listOf(diff + SPROUT_TENS, diff - SPROUT_TENS, a + b), nonNegative, random)
        }

        SproutForm.TIMES_TABLE -> {
            val table = random.nextInt(TABLE_MIN, TABLE_MAX + 1)
            val times = random.nextInt(1, TABLE_MAX + 1)
            val plausible = listOf((table + 1) * times, (table - 1) * times, table * (times + 1), table + times)
            binary(table, TIMES, times, table * times, plausible, nonNegative, random)
        }
    }

    /** Mixed two-step expressions; results may be negative, divisions are always exact. Typed answer, no choices. */
    private fun sapling(random: Random): ArithmeticRound = when (SaplingForm.entries.random(random)) {
        SaplingForm.BRACKET_ADD_TIMES -> {
            val (a, b, c) = Triple(term(random), term(random), small(random))
            bracketed(a, PLUS, b, TIMES, c, (a + b) * c)
        }

        SaplingForm.BRACKET_SUBTRACT_TIMES -> {
            val (a, b, c) = Triple(term(random), term(random), small(random))
            bracketed(a, MINUS, b, TIMES, c, (a - b) * c)
        }

        SaplingForm.TIMES_ADD -> {
            val (a, b, c) = Triple(table(random), table(random), addend(random))
            flat(a, TIMES, b, PLUS, c, a * b + c)
        }

        SaplingForm.TIMES_SUBTRACT -> {
            val (a, b, c) = Triple(table(random), table(random), addend(random) + addend(random))
            flat(a, TIMES, b, MINUS, c, a * b - c)
        }

        SaplingForm.DIVIDE_ADD -> {
            val (divisor, quotient, c) = Triple(table(random), table(random), addend(random))
            flat(divisor * quotient, DIVIDE, divisor, PLUS, c, quotient + c)
        }

        SaplingForm.DIVIDE_SUBTRACT -> {
            val (divisor, quotient, c) = Triple(table(random), table(random), addend(random))
            flat(divisor * quotient, DIVIDE, divisor, MINUS, c, quotient - c)
        }

        SaplingForm.SUBTRACT_TIMES -> {
            val (a, b, c) = Triple(addend(random), table(random), table(random))
            flat(a, MINUS, b, TIMES, c, a - b * c)
        }

        SaplingForm.BRACKET_ADD_DIVIDE -> {
            val (divisor, quotient) = small(random) to table(random)
            val total = divisor * quotient
            val a = random.nextInt(1, total)
            bracketed(a, PLUS, total - a, DIVIDE, divisor, quotient)
        }
    }

    private fun term(random: Random) = random.nextInt(SMALL_MIN, TERM_MAX + 1)
    private fun small(random: Random) = random.nextInt(SMALL_MIN, SMALL_MAX + 1)
    private fun table(random: Random) = random.nextInt(TABLE_MIN, TABLE_MAX + 1)
    private fun addend(random: Random) = random.nextInt(1, ADDEND_MAX + 1)

    @Suppress("LongParameterList")
    private fun binary(
        a: Int,
        op: String,
        b: Int,
        answer: Int,
        plausible: List<Int>,
        range: IntRange,
        random: Random,
    ): ArithmeticRound {
        val prompt = "$a $op $b"
        return ArithmeticRound(prompt, speak(prompt), listOf(a, b), answer, choices(answer, plausible, range, random))
    }

    @Suppress("LongParameterList")
    private fun bracketed(a: Int, op1: String, b: Int, op2: String, c: Int, answer: Int): ArithmeticRound {
        val prompt = "($a $op1 $b) $op2 $c"
        val spoken = speak("$a $op1 $b") + ", then " + speak("$op2 $c")
        return ArithmeticRound(prompt, spoken, listOf(a, b, c), answer, choices = null)
    }

    @Suppress("LongParameterList")
    private fun flat(a: Int, op1: String, b: Int, op2: String, c: Int, answer: Int): ArithmeticRound {
        val prompt = "$a $op1 $b $op2 $c"
        return ArithmeticRound(prompt, speak(prompt), listOf(a, b, c), answer, choices = null)
    }

    private fun speak(text: String): String = text
        .replace(PLUS, "plus")
        .replace(MINUS, "minus")
        .replace(TIMES, "times")
        .replace(DIVIDE, "divided by")

    /**
     * Four distinct options in [range] including [answer]: three distractors drawn from the [plausible] mistakes
     * (wrong operation, off by a ten or a table row) and near misses (±1..3), shuffled.
     */
    private fun choices(answer: Int, plausible: List<Int>, range: IntRange, random: Random): List<Int> {
        val near = (1..NEAR_SPREAD).flatMap { listOf(answer + it, answer - it) }
        val pool = (plausible + near).filter { it in range && it != answer }.distinct()
        val distractors = pool.shuffled(random).take(CHOICE_COUNT - 1)
        check(distractors.size == CHOICE_COUNT - 1) { "Not enough distractors for $answer in $range" }
        return (distractors + answer).shuffled(random)
    }
}

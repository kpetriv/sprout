package dev.kpetriv.sprout.core.games

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ArithmeticRoundTest {
    private fun rounds(difficulty: Difficulty, seeds: Int = 300): List<ArithmeticRound> =
        (0L until seeds).flatMap { seed ->
            GameSession.create(GameId(GameKind.ARITHMETIC, difficulty), seed).rounds.map { it as ArithmeticRound }
        }

    @Test
    fun multipleChoiceHasFourDistinctOptionsIncludingTheAnswer() {
        listOf(Difficulty.SEEDLING, Difficulty.SPROUT).forEach { difficulty ->
            rounds(difficulty).forEach { round ->
                val choices = assertNotNull(round.choices, round.prompt)
                assertEquals(4, choices.size, round.toString())
                assertEquals(4, choices.toSet().size, round.toString())
                assertTrue(round.answer in choices, round.toString())
                assertTrue(choices.all { it >= 0 }, round.toString())
            }
        }
    }

    @Test
    fun seedlingNeverGoesNegativeOrAboveTen() {
        rounds(Difficulty.SEEDLING).forEach { round ->
            assertTrue(round.answer in 0..10, round.toString())
            assertTrue(round.choices!!.all { it in 0..10 }, round.toString())
        }
    }

    @Test
    fun seedlingMixesCountingWithSumsWithinTen() {
        val all = rounds(Difficulty.SEEDLING)
        val counting = all.filter { it.dots != null }
        val sums = all.filter { it.dots == null }
        assertTrue(counting.isNotEmpty() && sums.isNotEmpty())
        counting.forEach { round ->
            assertEquals("How many?", round.prompt)
            assertTrue(round.dots in 1..10, round.toString())
            assertEquals(round.dots, round.answer)
            // Zero is offered as a wrong count, so one dot is not always the smallest choice.
            assertTrue(round.choices!!.all { it in 0..10 }, round.toString())
            assertTrue(round.operands.isEmpty())
        }
        sums.forEach { round ->
            assertEquals(round.answer, evaluate(round.prompt), round.prompt)
            assertTrue(round.operands.all { it in 0..10 }, round.prompt)
        }
        assertTrue(sums.any { "+" in it.prompt } && sums.any { MINUS in it.prompt })
    }

    @Test
    fun distractorsAreSpreadAroundTheAnswer() {
        listOf(Difficulty.SEEDLING, Difficulty.SPROUT).forEach { difficulty ->
            val all = rounds(difficulty)
            // How many choices are below the answer: 0 means the answer is the smallest.
            val ranks = all.map { round -> round.choices!!.count { it < round.answer } }
            assertEquals(setOf(0, 1, 2, 3), ranks.toSet(), "$difficulty: the answer takes every rank")
            ranks.groupingBy { it }.eachCount().forEach { (rank, count) ->
                assertTrue(count > all.size / 8, "$difficulty: rank $rank only $count of ${all.size}")
            }
        }
        val seedling = rounds(Difficulty.SEEDLING, seeds = 1_000)
        listOf(1, 2).forEach { answer ->
            val counting = seedling.filter { it.dots == answer }
            assertTrue(counting.isNotEmpty(), "counting $answer occurs")
            assertTrue(counting.any { round -> round.choices!!.any { it < answer } }, "$answer is not always smallest")
            val sums = seedling.filter { it.dots == null && it.answer == answer }
            assertTrue(sums.any { round -> round.choices!!.any { it < answer } }, "sum $answer is not always smallest")
        }
        rounds(Difficulty.SPROUT, seeds = 1_000).filter { it.answer in 1..2 }.let { small ->
            assertTrue(small.isNotEmpty() && small.any { round -> round.choices!!.any { it < round.answer } })
        }
    }

    @Test
    fun sproutAddsAndSubtractsWithinAHundredAndUsesTablesToTwelve() {
        val all = rounds(Difficulty.SPROUT)
        all.forEach { round ->
            assertEquals(round.answer, evaluate(round.prompt), round.prompt)
            assertTrue(round.answer >= 0, round.prompt)
            assertEquals(2, round.operands.size)
            assertNull(round.dots)
        }
        val (times, addSub) = all.partition { TIMES in it.prompt }
        assertTrue(times.isNotEmpty() && addSub.isNotEmpty())
        times.forEach { assertTrue(it.operands[0] in 2..12 && it.operands[1] in 1..12, it.prompt) }
        addSub.forEach { round ->
            assertTrue(round.operands.all { it in 0..100 }, round.prompt)
            assertTrue(round.answer in 0..100, round.prompt)
        }
    }

    @Test
    fun saplingIsTypedTwoStepWithExactDivision() {
        val all = rounds(Difficulty.SAPLING)
        all.forEach { round ->
            assertNull(round.choices, round.prompt)
            assertEquals(3, round.operands.size, round.prompt)
            assertTrue(round.operands.all { it > 0 }, round.prompt)
            // evaluate() fails on any division with a remainder.
            assertEquals(round.answer, evaluate(round.prompt), round.prompt)
        }
        assertTrue(all.any { it.answer < 0 }, "negatives occur")
        assertTrue(all.any { DIVIDE in it.prompt }, "division occurs")
        assertTrue(all.any { it.prompt.startsWith("(") }, "brackets occur")
    }

    @Test
    fun spokenPromptUsesWords() {
        (rounds(Difficulty.SEEDLING, 20) + rounds(Difficulty.SAPLING, 20)).forEach { round ->
            listOf("+", MINUS, TIMES, DIVIDE, "(", ")").forEach { symbol ->
                assertTrue(symbol !in round.spokenPrompt, round.spokenPrompt)
            }
        }
        val sum = rounds(Difficulty.SEEDLING, 20).first { "+" in it.prompt }
        assertEquals(sum.prompt.replace("+", "plus"), sum.spokenPrompt)
    }

    @Test
    fun promptsAreUniqueWithinASessionWhereSpaceAllows() {
        listOf(Difficulty.SPROUT, Difficulty.SAPLING).forEach { difficulty ->
            (0L until 100L).forEach { seed ->
                val session = GameSession.create(GameId(GameKind.ARITHMETIC, difficulty), seed)
                val prompts = session.rounds.map { (it as ArithmeticRound).prompt }
                assertEquals(prompts.size, prompts.toSet().size, prompts.toString())
            }
        }
    }

    private companion object {
        const val MINUS = "−"
        const val TIMES = "×"
        const val DIVIDE = "÷"

        /** Evaluates a prompt with normal precedence; fails on a division with a remainder. */
        fun evaluate(prompt: String): Int {
            val tokens = Regex("""\d+|[+−×÷()]""").findAll(prompt).map { it.value }.toList()
            assertEquals(prompt.replace(" ", ""), tokens.joinToString(""), "unexpected characters in $prompt")
            val parser = Parser(tokens)
            val value = parser.expression()
            assertEquals(tokens.size, parser.position, "trailing tokens in $prompt")
            return value
        }

        class Parser(private val tokens: List<String>) {
            var position = 0

            fun expression(): Int {
                var value = term()
                while (position < tokens.size && tokens[position] in listOf("+", MINUS)) {
                    val op = tokens[position++]
                    val right = term()
                    value = if (op == "+") value + right else value - right
                }
                return value
            }

            private fun term(): Int {
                var value = factor()
                while (position < tokens.size && tokens[position] in listOf(TIMES, DIVIDE)) {
                    val op = tokens[position++]
                    val right = factor()
                    value = if (op == TIMES) {
                        value * right
                    } else {
                        if (value % right != 0) fail("$value $DIVIDE $right is not whole")
                        value / right
                    }
                }
                return value
            }

            private fun factor(): Int {
                val token = tokens[position++]
                if (token == "(") {
                    val value = expression()
                    assertEquals(")", tokens[position++])
                    return value
                }
                return token.toInt()
            }
        }
    }
}

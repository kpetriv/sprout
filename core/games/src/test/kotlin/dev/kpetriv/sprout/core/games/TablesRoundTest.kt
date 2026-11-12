package dev.kpetriv.sprout.core.games

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TablesRoundTest {
    private val difficulties = listOf(Difficulty.SPROUT, Difficulty.SAPLING)

    private fun rounds(setup: TablesSetup, seed: Long, difficulty: Difficulty = Difficulty.SPROUT): List<TablesRound> =
        GameSession.create(GameId(GameKind.TABLES, difficulty), seed, setup = setup).rounds.map { it as TablesRound }

    private val setups: List<TablesSetup> = listOf(
        TablesSetup(setOf(7), TablesLevel.GENTLE),
        TablesSetup(setOf(2, 12), TablesLevel.STEADY),
        TablesSetup(setOf(3, 4, 6), TablesLevel.ROCK_STAR),
        TablesSetup(setOf(6, 7, 8, 9), TablesLevel.STEADY),
        TablesSetup.MIX,
    )

    @Test
    fun everyQuestionIsFromAChosenTableWithTheRightAnswerAndPrompts() {
        for (setup in setups) {
            for (seed in 0L until 100L) {
                val rounds = rounds(setup, seed)
                assertEquals(20, rounds.size)
                rounds.forEach { round ->
                    assertTrue(round.a in setup.tables, "$setup $round")
                    assertTrue(round.b in 1..12, "$setup $round")
                    assertEquals(round.a * round.b, round.answer)
                    assertEquals("${round.a} × ${round.b}", round.prompt)
                    assertEquals("${round.a} times ${round.b}", round.spokenPrompt)
                    assertEquals(GameKind.TABLES, round.kind)
                }
            }
        }
    }

    @Test
    fun questionsAreSpreadEvenlyAcrossTheChosenTables() {
        for (setup in setups) {
            for (seed in 0L until 100L) {
                val perTable = rounds(setup, seed).groupingBy { it.a }.eachCount()
                assertEquals(setup.tables, perTable.keys, "$setup seed $seed")
                val counts = perTable.values
                assertTrue(counts.max() - counts.min() <= 1, "$setup seed $seed: $perTable")
            }
        }
    }

    @Test
    fun noQuestionRepeatsWithTwoOrMoreTables() {
        for (setup in setups.filter { it.tables.size >= 2 }) {
            for (seed in 0L until 200L) {
                val pairs = rounds(setup, seed).map { it.a to it.b }
                assertEquals(pairs.size, pairs.toSet().size, "$setup seed $seed")
            }
        }
    }

    @Test
    fun oneTableAsksAllTwelveOnceBeforeAnyRepeatAndNeverTheSameTwiceInARow() {
        for (table in TablesSetup.TABLES) {
            for (seed in 0L until 100L) {
                val bs = rounds(TablesSetup(setOf(table), TablesLevel.GENTLE), seed).map { it.b }
                assertEquals((1..12).toSet(), bs.take(12).toSet(), "table $table seed $seed")
                assertEquals(8, bs.drop(12).toSet().size, "the repeats are distinct too")
                bs.zipWithNext().forEach { (x, y) -> assertNotEquals(x, y, "table $table seed $seed: $bs") }
            }
        }
    }

    @Test
    fun sameSeedAndSetupReplayAndTheDifficultyDoesNotMatter() {
        for (setup in setups) {
            assertEquals(rounds(setup, 42), rounds(setup, 42))
            assertEquals(rounds(setup, 42, Difficulty.SPROUT), rounds(setup, 42, Difficulty.SAPLING))
            assertNotEquals(rounds(setup, 1), rounds(setup, 2), "$setup")
        }
    }

    @Test
    fun theOrderIsShuffledNotGroupedByTable() {
        val setup = TablesSetup(setOf(2, 3, 4, 5), TablesLevel.GENTLE)
        val grouped = (0L until 20L).count { seed ->
            val tables = rounds(setup, seed).map { it.a }
            tables == tables.sorted()
        }
        assertEquals(0, grouped)
    }

    @Test
    fun mixCoversEveryTableOverAFewSeeds() {
        // 20 questions over 11 tables: every table gets one or two in every session.
        (0L until 5L).forEach { seed ->
            assertEquals(TablesSetup.TABLES.toSet(), rounds(TablesSetup.MIX, seed).map { it.a }.toSet())
        }
        val asked = (0L until 5L).flatMap { seed -> rounds(TablesSetup.MIX, seed).map { it.b } }.toSet()
        assertEquals((1..12).toSet(), asked)
    }

    @Test
    fun setupMustBeANonEmptySubsetOfTwoToTwelve() {
        assertFailsWith<IllegalArgumentException> { TablesSetup(emptySet(), TablesLevel.GENTLE) }
        assertFailsWith<IllegalArgumentException> { TablesSetup(setOf(1), TablesLevel.GENTLE) }
        assertFailsWith<IllegalArgumentException> { TablesSetup(setOf(2, 13), TablesLevel.GENTLE) }
        assertEquals((2..12).toSet(), TablesSetup.MIX.tables)
        assertTrue(TablesSetup.MIX.isMix)
        assertFalse(TablesSetup(setOf(2, 3), TablesLevel.GENTLE).isMix)
    }

    @Test
    fun defaultsPerDifficulty() {
        assertEquals(TablesSetup(setOf(2, 5, 10), TablesLevel.GENTLE), TablesSetup.defaultFor(Difficulty.SPROUT))
        assertEquals(TablesSetup(setOf(6, 7, 8, 9), TablesLevel.STEADY), TablesSetup.defaultFor(Difficulty.SAPLING))
        assertFailsWith<IllegalArgumentException> { TablesSetup.defaultFor(Difficulty.SEEDLING) }
        difficulties.forEach { rounds(TablesSetup.defaultFor(it), 1, it) }
    }

    @Test
    fun levelsAreTwentyTenAndFiveSecondsAndOnlyRockStarIsTimed() {
        assertEquals(listOf(20, 10, 5), TablesLevel.entries.map { it.secondsPerQuestion })
        assertEquals(listOf(20_000L, 10_000L, 5_000L), TablesLevel.entries.map { it.timeLimitMs })
        assertEquals(listOf(TablesLevel.ROCK_STAR), TablesLevel.entries.filter { it.timed })
    }

    @Test
    fun typedAnswersCompareAsIntegers() {
        val round = TablesRound.of(7, 8)
        assertEquals(TablesRound(7, 8, 56, "7 × 8", "7 times 8"), round)
        assertTrue(round.accepts(56))
        assertFalse(round.accepts(54))
        listOf("56", " 56 ", "056").forEach { assertTrue(round.accepts(it), it) }
        listOf("", "5 6", "56.0", "fifty-six", "65", "-56").forEach { assertFalse(round.accepts(it), it) }
    }
}

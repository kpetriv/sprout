package dev.kpetriv.sprout.core.games

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GameScorerTest {
    private val sapling = GameId(GameKind.ARITHMETIC, Difficulty.SAPLING)
    private val sprout = GameId(GameKind.MEMORY, Difficulty.SPROUT)

    private fun outcomes(n: Int, correct: Int = n, elapsedMs: Long = 1_000) =
        List(n) { RoundOutcome(correct = it < correct, elapsedMs = elapsedMs) }

    @Test
    fun countsCorrectRoundsAndPerfect() {
        val session = GameSession.create(sprout, 1)
        assertEquals(Score(8, 8, perfect = true, timedBonus = false), GameScorer.score(session, outcomes(8)))
        assertEquals(Score(5, 8, perfect = false, timedBonus = false), GameScorer.score(session, outcomes(8, 5)))
        assertEquals(Score(0, 8, perfect = false, timedBonus = false), GameScorer.score(session, outcomes(8, 0)))
    }

    @Test
    fun timedBonusNeedsTimedPerfectAndEveryRoundWithinItsLimit() {
        val timed = GameSession.create(sapling, 1, timed = true)
        assertEquals(Score(10, 10, true, timedBonus = true), GameScorer.score(timed, outcomes(10)))
        // The limit itself is still in time.
        assertEquals(true, GameScorer.score(timed, outcomes(10, elapsedMs = 8_000)).timedBonus)
        assertEquals(false, GameScorer.score(timed, outcomes(10, elapsedMs = 8_001)).timedBonus)
        val oneSlow = outcomes(10).toMutableList().also { it[4] = RoundOutcome(true, 9_000) }
        assertEquals(Score(10, 10, true, timedBonus = false), GameScorer.score(timed, oneSlow))
        assertEquals(Score(9, 10, false, timedBonus = false), GameScorer.score(timed, outcomes(10, 9)))
    }

    @Test
    fun untimedSessionsNeverGetTheBonus() {
        val untimed = GameSession.create(sapling, 1)
        assertEquals(Score(10, 10, true, timedBonus = false), GameScorer.score(untimed, outcomes(10, elapsedMs = 1)))
    }

    @Test
    fun limitsFollowTheGameKind() {
        val words = GameSession.create(GameId(GameKind.WORDS, Difficulty.SAPLING), 1, timed = true)
        assertEquals(true, GameScorer.score(words, outcomes(10, elapsedMs = 15_000)).timedBonus)
        assertEquals(false, GameScorer.score(words, outcomes(10, elapsedMs = 15_001)).timedBonus)
        val memory = GameSession.create(GameId(GameKind.MEMORY, Difficulty.SAPLING), 1, timed = true)
        assertEquals(true, GameScorer.score(memory, outcomes(10, elapsedMs = 10_000)).timedBonus)
        assertEquals(false, GameScorer.score(memory, outcomes(10, elapsedMs = 10_001)).timedBonus)
    }

    @Test
    fun outcomesMustMatchTheRounds() {
        val session = GameSession.create(sprout, 1)
        assertFailsWith<IllegalArgumentException> { GameScorer.score(session, outcomes(7)) }
        assertFailsWith<IllegalArgumentException> { GameScorer.score(session, outcomes(9)) }
        assertFailsWith<IllegalArgumentException> { RoundOutcome(true, -1) }
    }

    @Test
    fun rockStarTimesTablesGetTheBonusWithinFiveSecondsAndTheOtherLevelsNever() {
        val tables = GameId(GameKind.TABLES, Difficulty.SPROUT)
        val rockStar = GameSession.create(tables, 1, setup = TablesSetup(setOf(6, 7), TablesLevel.ROCK_STAR))
        val perfect = Score(20, 20, true, timedBonus = true)
        assertEquals(perfect, GameScorer.score(rockStar, outcomes(20, elapsedMs = 5_000)))
        assertEquals(false, GameScorer.score(rockStar, outcomes(20, elapsedMs = 5_001)).timedBonus)
        assertEquals(Score(17, 20, false, timedBonus = false), GameScorer.score(rockStar, outcomes(20, 17)))
        val gentle = GameSession.create(tables, 1, setup = TablesSetup(setOf(6, 7), TablesLevel.GENTLE))
        assertEquals(Score(20, 20, true, timedBonus = false), GameScorer.score(gentle, outcomes(20, elapsedMs = 1)))
    }
}

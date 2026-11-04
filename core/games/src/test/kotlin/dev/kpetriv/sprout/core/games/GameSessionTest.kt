package dev.kpetriv.sprout.core.games

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GameSessionTest {
    @Test
    fun sameSeedReplaysIdentically() {
        GameCatalogue.all.forEach { id ->
            (0L until 50L).forEach { seed ->
                val first = GameSession.create(id, seed)
                val second = GameSession.create(id, seed)
                assertEquals(first.rounds, second.rounds, "${id.wire} seed $seed")
            }
        }
    }

    @Test
    fun differentSeedsGiveDifferentSessions() {
        GameCatalogue.all.forEach { id ->
            assertNotEquals(GameSession.create(id, 1).rounds, GameSession.create(id, 2).rounds, id.wire)
        }
    }

    @Test
    fun sessionHasTheCatalogueRoundCountOfItsKind() {
        GameCatalogue.all.forEach { id ->
            val session = GameSession.create(id, 7)
            assertEquals(GameCatalogue.rounds(id), session.rounds.size, id.wire)
            assertTrue(session.rounds.all { it.kind == id.kind }, id.wire)
            assertEquals(id, session.gameId)
            assertEquals(7L, session.seed)
        }
    }

    @Test
    fun timedModeOnlyAtSapling() {
        GameCatalogue.all.forEach { id ->
            if (id.difficulty == Difficulty.SAPLING) {
                assertTrue(GameSession.create(id, 1, timed = true).timed)
            } else {
                assertFailsWith<IllegalArgumentException>(id.wire) { GameSession.create(id, 1, timed = true) }
            }
        }
    }

    @Test
    fun timedFlagDoesNotChangeTheRounds() {
        val id = GameId(GameKind.ARITHMETIC, Difficulty.SAPLING)
        assertEquals(GameSession.create(id, 3).rounds, GameSession.create(id, 3, timed = true).rounds)
    }

    @Test
    fun timeLimitFollowsTheRoundKind() {
        val words = GameSession.create(GameId(GameKind.WORDS, Difficulty.SAPLING), 1, timed = true)
        assertEquals(15_000L, words.timeLimitMs(words.rounds.first()))
        val memory = GameSession.create(GameId(GameKind.MEMORY, Difficulty.SAPLING), 1, timed = true)
        assertEquals(10_000L, memory.timeLimitMs(memory.rounds.first()))
        val sums = GameSession.create(GameId(GameKind.ARITHMETIC, Difficulty.SAPLING), 1, timed = true)
        assertEquals(8_000L, sums.timeLimitMs(sums.rounds.first()))
    }
}

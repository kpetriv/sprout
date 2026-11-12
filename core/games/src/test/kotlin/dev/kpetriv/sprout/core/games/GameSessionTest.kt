package dev.kpetriv.sprout.core.games

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameSessionTest {
    /** A session of [id], with the default setup of its difficulty when it is Times tables. */
    private fun create(id: GameId, seed: Long, timed: Boolean = false): GameSession =
        GameSession.create(id, seed, timed, setup = if (id.isTables) TablesSetup.defaultFor(id.difficulty) else null)

    private val GameId.isTables: Boolean get() = kind == GameKind.TABLES

    @Test
    fun sameSeedReplaysIdentically() {
        GameCatalogue.all.forEach { id ->
            (0L until 50L).forEach { seed ->
                val first = create(id, seed)
                val second = create(id, seed)
                assertEquals(first.rounds, second.rounds, "${id.wire} seed $seed")
            }
        }
    }

    @Test
    fun differentSeedsGiveDifferentSessions() {
        GameCatalogue.all.forEach { id ->
            assertNotEquals(create(id, 1).rounds, create(id, 2).rounds, id.wire)
        }
    }

    @Test
    fun sessionHasTheCatalogueRoundCountOfItsKind() {
        GameCatalogue.all.forEach { id ->
            val session = create(id, 7)
            assertEquals(GameCatalogue.rounds(id), session.rounds.size, id.wire)
            assertTrue(session.rounds.all { it.kind == id.kind }, id.wire)
            assertEquals(id, session.gameId)
            assertEquals(7L, session.seed)
        }
    }

    @Test
    fun timedModeOnlyAtSaplingForTheFirstThreeGames() {
        GameCatalogue.all.filterNot { it.isTables }.forEach { id ->
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

    @Test
    fun timesTablesNeedASetupAndNoOtherGameTakesOne() {
        listOf(Difficulty.SPROUT, Difficulty.SAPLING).forEach { difficulty ->
            assertFailsWith<IllegalArgumentException> { GameSession.create(GameId(GameKind.TABLES, difficulty), 1) }
        }
        GameCatalogue.all.filterNot { it.isTables }.forEach { id ->
            assertFailsWith<IllegalArgumentException>(id.wire) { GameSession.create(id, 1, setup = TablesSetup.MIX) }
        }
    }

    @Test
    fun onlyRockStarTimesTablesAreTimedWhateverWasAskedFor() {
        listOf(Difficulty.SPROUT, Difficulty.SAPLING).forEach { difficulty ->
            val id = GameId(GameKind.TABLES, difficulty)
            TablesLevel.entries.forEach { level ->
                val setup = TablesSetup(setOf(3, 4), level)
                listOf(false, true).forEach { asked ->
                    val session = GameSession.create(id, 1, timed = asked, setup = setup)
                    assertEquals(level == TablesLevel.ROCK_STAR, session.timed, "$id $level asked $asked")
                    assertEquals(setup, session.setup)
                }
            }
        }
    }

    @Test
    fun timesTablesLimitIsTheLevelsAndTheLevelDoesNotChangeTheQuestions() {
        val id = GameId(GameKind.TABLES, Difficulty.SPROUT)
        val expected = mapOf(
            TablesLevel.GENTLE to 20_000L,
            TablesLevel.STEADY to 10_000L,
            TablesLevel.ROCK_STAR to 5_000L,
        )
        val sessions = TablesLevel.entries.map { GameSession.create(id, 9, setup = TablesSetup(setOf(7), it)) }
        sessions.forEach { session ->
            val level = session.setup!!.level
            assertTrue(session.rounds.all { session.timeLimitMs(it) == expected.getValue(level) }, "$level")
        }
        assertEquals(1, sessions.map { it.rounds }.distinct().size)
    }

    @Test
    fun otherGamesHaveNoSetup() {
        GameCatalogue.all.filterNot { it.isTables }.forEach { assertNull(GameSession.create(it, 1).setup, it.wire) }
    }
}

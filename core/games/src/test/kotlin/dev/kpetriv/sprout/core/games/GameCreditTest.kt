package dev.kpetriv.sprout.core.games

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameCreditTest {
    private fun id(wire: String) = GameId.parse(wire)!!

    @Test
    fun oneMinorUnitPerCorrectRound() {
        assertEquals(0L, GameCredit.earned("arithmetic.seedling", 0))
        assertEquals(4L, GameCredit.earned("arithmetic.seedling", 4))
        assertEquals(7L, GameCredit.earned("memory.sprout", 7))
        assertEquals(9L, GameCredit.earned("words.sapling", 9))
    }

    @Test
    fun perfectAddsThreeAndTimedAddsTwo() {
        assertEquals(8L, GameCredit.earned(id("memory.seedling"), Score(5, 5, perfect = true, timedBonus = false)))
        assertEquals(11L, GameCredit.earned(id("words.sprout"), Score(8, 8, perfect = true, timedBonus = false)))
        val sapling = id("arithmetic.sapling")
        assertEquals(13L, GameCredit.earned(sapling, Score(10, 10, perfect = true, timedBonus = false)))
        assertEquals(15L, GameCredit.earned(sapling, Score(10, 10, perfect = true, timedBonus = true)))
    }

    @Test
    fun wireFormAssumesTheBestPossiblePlay() {
        assertEquals(8L, GameCredit.earned("memory.seedling", 5))
        assertEquals(11L, GameCredit.earned("arithmetic.sprout", 8))
        // A perfect Sapling play may have been timed, so the ledger allows the timed bonus.
        assertEquals(15L, GameCredit.earned("words.sapling", 10))
    }

    @Test
    fun neverMoreThanFifteenPerPlay() {
        assertEquals(15L, GameCredit.MAX_PER_PLAY)
        GameCatalogue.all.forEach { gameId ->
            (0..GameCatalogue.rounds(gameId)).forEach { correct ->
                val earned = GameCredit.earned(gameId.wire, correct)!!
                assertTrue(earned in 0..GameCredit.MAX_PER_PLAY, "${gameId.wire} $correct -> $earned")
            }
        }
        assertEquals(
            listOf(8L, 8L, 11L, 11L, 11L, 15L, 15L, 15L, 15L, 15L),
            GameCatalogue.all.map { GameCredit.earned(it.wire, GameCatalogue.rounds(it)) },
        )
    }

    @Test
    fun inconsistentBonusesAreIgnored() {
        // Claims perfect with a round wrong, or a timed bonus where timed mode does not exist.
        assertEquals(9L, GameCredit.earned(id("arithmetic.sapling"), Score(9, 10, perfect = true, timedBonus = true)))
        assertEquals(11L, GameCredit.earned(id("arithmetic.sprout"), Score(8, 8, perfect = true, timedBonus = true)))
        assertEquals(5L, GameCredit.earned(id("arithmetic.seedling"), Score(5, 5, perfect = false, timedBonus = false)))
    }

    @Test
    fun scoreBasedCreditNeverExceedsTheWireBound() {
        val flags = listOf(false to false, false to true, true to false, true to true)
        val cases = GameCatalogue.all.flatMap { gameId ->
            (0..GameCatalogue.rounds(gameId)).flatMap { correct -> flags.map { Triple(gameId, correct, it) } }
        }
        cases.forEach { (gameId, correct, flag) ->
            val (perfect, timed) = flag
            val score = Score(correct, GameCatalogue.rounds(gameId), perfect, timed)
            val fromScore = GameCredit.earned(gameId, score)!!
            val bound = GameCredit.earned(gameId.wire, correct)!!
            assertTrue(fromScore <= bound, "${gameId.wire} $correct $perfect $timed")
        }
    }

    @Test
    fun unknownIdsAndOutOfRangeScoresEarnNothing() {
        listOf("arithmetic", "Arithmetic.Sprout", "words.seedling", "chess.sapling", "").forEach {
            assertNull(GameCredit.earned(it, 1), it)
        }
        assertNull(GameCredit.earned("arithmetic.seedling", -1))
        assertNull(GameCredit.earned("arithmetic.seedling", 6))
        assertNull(GameCredit.earned("memory.sprout", 9))
        assertNull(GameCredit.earned("words.sapling", 11))
        assertNull(GameCredit.earned("tables.sprout", 21))
        assertNull(GameCredit.earned("tables.sapling", -1))
        assertNull(GameCredit.earned("tables.seedling", 5))
        assertNull(GameCredit.earned(id("words.sapling"), Score(11, 11, perfect = true, timedBonus = true)))
        assertNull(GameCredit.earned(id("words.sapling"), Score(-1, 10, perfect = false, timedBonus = false)))
    }

    @Test
    fun timesTablesPayTheSameCappedRuleOverTwentyRounds() {
        // One per correct up to the cap: 14 right is 14, 15 or more is 15, perfect (20 + 3, + 2 timed) is still 15.
        val table = listOf(0 to 0L, 1 to 1L, 14 to 14L, 15 to 15L, 19 to 15L, 20 to 15L)
        for (wire in listOf("tables.sprout", "tables.sapling")) {
            for ((correct, credit) in table) assertEquals(credit, GameCredit.earned(wire, correct), "$wire $correct")
        }
        val sprout = id("tables.sprout")
        val sapling = id("tables.sapling")
        assertEquals(15L, GameCredit.earned(sprout, Score(20, 20, perfect = true, timedBonus = false)))
        assertEquals(15L, GameCredit.earned(sprout, Score(20, 20, perfect = true, timedBonus = true)))
        assertEquals(15L, GameCredit.earned(sapling, Score(20, 20, perfect = true, timedBonus = true)))
        assertEquals(12L, GameCredit.earned(sapling, Score(12, 20, perfect = false, timedBonus = false)))
        assertNull(GameCredit.earned(sapling, Score(21, 20, perfect = true, timedBonus = true)))
    }
}

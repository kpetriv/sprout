package dev.kpetriv.sprout.core.games

import dev.kpetriv.sprout.core.model.KidUiTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GameIdTest {
    @Test
    fun wireIsLowerCaseKindDotDifficulty() {
        assertEquals("arithmetic.sprout", GameId(GameKind.ARITHMETIC, Difficulty.SPROUT).wire)
        assertEquals("memory.seedling", GameId(GameKind.MEMORY, Difficulty.SEEDLING).wire)
        assertEquals("words.sapling", GameId(GameKind.WORDS, Difficulty.SAPLING).wire)
        assertEquals("tables.sprout", GameId(GameKind.TABLES, Difficulty.SPROUT).wire)
        assertEquals("tables.sapling", GameId(GameKind.TABLES, Difficulty.SAPLING).wire)
    }

    @Test
    fun everyPlayableIdRoundTrips() {
        assertEquals(10, GameCatalogue.all.size)
        GameCatalogue.all.forEach { id -> assertEquals(id, GameId.parse(id.wire), id.wire) }
    }

    @Test
    fun parseRejectsAnythingNotStrictlyKnown() {
        listOf(
            "Arithmetic.Sprout",
            "ARITHMETIC.SPROUT",
            "arithmetic",
            "arithmetic.sprout.x",
            "arithmetic.",
            ".sprout",
            "",
            ".",
            " arithmetic.sprout",
            "arithmetic.sprout ",
            "arithmetic_sprout",
            "chess.sprout",
            "arithmetic.tree",
            // Word scramble is not offered at Seedling, so its id does not exist.
            "words.seedling",
            // Nor are Times tables.
            "tables.seedling",
            "Tables.sprout",
            "times.sprout",
        ).forEach { assertNull(GameId.parse(it), it) }
    }

    @Test
    fun wordsAndTablesAtSeedlingCannotBeConstructed() {
        assertFailsWith<IllegalArgumentException> { GameId(GameKind.WORDS, Difficulty.SEEDLING) }
        assertFailsWith<IllegalArgumentException> { GameId(GameKind.TABLES, Difficulty.SEEDLING) }
    }

    @Test
    fun seedlingGetsArithmeticAndMemoryOnly() {
        assertEquals(listOf(GameKind.ARITHMETIC, GameKind.MEMORY), GameCatalogue.forDifficulty(Difficulty.SEEDLING))
        assertEquals(GameKind.entries.toList(), GameCatalogue.forDifficulty(Difficulty.SPROUT))
        assertEquals(GameKind.entries.toList(), GameCatalogue.forDifficulty(Difficulty.SAPLING))
    }

    @Test
    fun tiersMapOneToOneOntoDifficulties() {
        assertEquals(Difficulty.SEEDLING, Difficulty.forTier(KidUiTier.SEEDLING))
        assertEquals(Difficulty.SPROUT, Difficulty.forTier(KidUiTier.SPROUT))
        assertEquals(Difficulty.SAPLING, Difficulty.forTier(KidUiTier.SAPLING))
        assertEquals(GameCatalogue.forDifficulty(Difficulty.SEEDLING), GameCatalogue.forTier(KidUiTier.SEEDLING))
    }

    @Test
    fun roundsAreFiveEightTenAndTwentyForTimesTables() {
        GameCatalogue.all.forEach { id ->
            val expected = when {
                id.kind == GameKind.TABLES -> 20
                id.difficulty == Difficulty.SEEDLING -> 5
                id.difficulty == Difficulty.SPROUT -> 8
                else -> 10
            }
            assertEquals(expected, GameCatalogue.rounds(id), id.wire)
        }
    }

    @Test
    fun timeLimitsAndTimedSupport() {
        assertEquals(8_000L, GameCatalogue.timeLimitMs(GameKind.ARITHMETIC))
        assertEquals(10_000L, GameCatalogue.timeLimitMs(GameKind.MEMORY))
        assertEquals(15_000L, GameCatalogue.timeLimitMs(GameKind.WORDS))
        assertEquals(5_000L, GameCatalogue.timeLimitMs(GameKind.TABLES))
        assertEquals(listOf(Difficulty.SAPLING), Difficulty.entries.filter { GameCatalogue.supportsTimed(it) })
    }

    @Test
    fun timesTablesParseAndAreOfferedLastAtSproutAndSapling() {
        assertEquals(GameId(GameKind.TABLES, Difficulty.SPROUT), GameId.parse("tables.sprout"))
        assertEquals(GameId(GameKind.TABLES, Difficulty.SAPLING), GameId.parse("tables.sapling"))
        assertEquals("tables", GameKind.TABLES.wire)
        assertEquals(GameKind.TABLES, GameCatalogue.forDifficulty(Difficulty.SPROUT).last())
        assertEquals(GameKind.TABLES, GameCatalogue.forDifficulty(Difficulty.SAPLING).last())
        assertEquals(4, GameCatalogue.forTier(KidUiTier.SPROUT).size)
    }

    @Test
    fun timesTablesSupportTimedAtBothDifficultiesTheOthersAtSaplingOnly() {
        val timed = GameCatalogue.all.filter { GameCatalogue.supportsTimed(it) }.map { it.wire }
        assertEquals(
            listOf("tables.sprout", "arithmetic.sapling", "memory.sapling", "words.sapling", "tables.sapling"),
            timed,
        )
        // The difficulty overload is unchanged.
        assertEquals(false, GameCatalogue.supportsTimed(Difficulty.SPROUT))
    }
}

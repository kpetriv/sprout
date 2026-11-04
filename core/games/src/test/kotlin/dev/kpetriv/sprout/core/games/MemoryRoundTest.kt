package dev.kpetriv.sprout.core.games

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryRoundTest {
    private fun sessions(difficulty: Difficulty): List<List<MemoryRound>> = (0L until 200L).map { seed ->
        GameSession.create(GameId(GameKind.MEMORY, difficulty), seed).rounds.map { it as MemoryRound }
    }

    private val bands = mapOf(
        Difficulty.SEEDLING to 3..5,
        Difficulty.SPROUT to 4..7,
        Difficulty.SAPLING to 5..9,
    )

    @Test
    fun lengthsStayInBandAndRampFromShortestToLongest() {
        bands.forEach { (difficulty, band) ->
            sessions(difficulty).forEach { rounds ->
                val lengths = rounds.map { it.sequence.size }
                assertTrue(lengths.all { it in band }, "$difficulty $lengths")
                assertEquals(lengths.sorted(), lengths, "$difficulty $lengths")
                assertEquals(band.first, lengths.first())
                assertEquals(band.last, lengths.last())
            }
        }
    }

    @Test
    fun symbolsComeFromTheFixedSets() {
        assertEquals(12, MemorySymbols.pictures.size)
        assertEquals(12, MemorySymbols.coloursAndShapes.size)
        listOf(MemorySymbols.pictures, MemorySymbols.coloursAndShapes).forEach { set ->
            assertEquals(set.size, set.map { it.id }.toSet().size)
            assertEquals(set.size, set.map { it.label }.toSet().size)
            assertEquals(set.size, set.map { it.glyph }.toSet().size)
            assertTrue(set.all { it.glyph.isNotEmpty() && it.label.isNotBlank() })
        }
        bands.keys.forEach { difficulty ->
            val set = MemorySymbols.forDifficulty(difficulty).toSet()
            sessions(difficulty).flatten().forEach { round ->
                assertTrue(set.containsAll(round.choices), "$difficulty $round")
            }
        }
        assertEquals(MemorySymbols.pictures, MemorySymbols.forDifficulty(Difficulty.SEEDLING))
        assertEquals(MemorySymbols.coloursAndShapes, MemorySymbols.forDifficulty(Difficulty.SPROUT))
        assertEquals(MemorySymbols.coloursAndShapes, MemorySymbols.forDifficulty(Difficulty.SAPLING))
    }

    @Test
    fun choicesAreTheSequenceSymbolsPlusTwoDistractorsAtSapling() {
        bands.keys.forEach { difficulty ->
            val expectedDistractors = if (difficulty == Difficulty.SAPLING) 2 else 0
            sessions(difficulty).flatten().forEach { round ->
                assertEquals(round.sequence.size, round.sequence.toSet().size, "no repeats: $round")
                assertEquals(round.choices.size, round.choices.toSet().size, "distinct choices: $round")
                assertTrue(round.choices.containsAll(round.sequence), round.toString())
                assertEquals(expectedDistractors, round.distractors.size, round.toString())
                assertTrue(round.distractors.none { it in round.sequence })
                assertEquals(round.sequence.size + expectedDistractors, round.choices.size)
            }
        }
    }
}

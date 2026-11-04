package dev.kpetriv.sprout.core.games

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WordRoundTest {
    private fun sessions(difficulty: Difficulty): List<List<WordRound>> = (0L until 200L).map { seed ->
        GameSession.create(GameId(GameKind.WORDS, difficulty), seed).rounds.map { it as WordRound }
    }

    @Test
    fun scrambleIsADifferentPermutationOfTheWord() {
        listOf(Difficulty.SPROUT, Difficulty.SAPLING).forEach { difficulty ->
            sessions(difficulty).flatten().forEach { round ->
                assertNotEquals(round.word, round.scrambled)
                assertEquals(round.word.toList().sorted(), round.scrambled.toList().sorted(), round.toString())
            }
        }
    }

    @Test
    fun everyListedWordCanBeScrambled() {
        val random = Random(42)
        WordList.all.forEach { entry ->
            repeat(5) {
                val scrambled = WordGenerator.scramble(entry.word, random)
                assertNotEquals(entry.word, scrambled)
                assertEquals(entry.word.toList().sorted(), scrambled.toList().sorted())
            }
        }
    }

    @Test
    fun sproutHasHintsAndSaplingDoesNot() {
        sessions(Difficulty.SPROUT).flatten().forEach { round ->
            val hint = assertNotNull(round.hint, round.word)
            assertTrue(round.word.length in 4..6, round.word)
            assertEquals(WordList.forDifficulty(Difficulty.SPROUT).first { it.word == round.word }.hint, hint)
        }
        sessions(Difficulty.SAPLING).flatten().forEach { round ->
            assertNull(round.hint, round.word)
            assertTrue(round.word.length in 6..9, round.word)
        }
    }

    @Test
    fun wordsDoNotRepeatWithinASession() {
        listOf(Difficulty.SPROUT, Difficulty.SAPLING).forEach { difficulty ->
            sessions(difficulty).forEach { rounds ->
                assertEquals(rounds.size, rounds.map { it.word }.toSet().size)
            }
        }
    }

    @Test
    fun wordListBands() {
        val sprout = WordList.forDifficulty(Difficulty.SPROUT)
        val sapling = WordList.forDifficulty(Difficulty.SAPLING)
        assertTrue(sprout.size >= 120, "sprout has ${sprout.size}")
        assertTrue(sapling.size >= 120, "sapling has ${sapling.size}")
        assertTrue(sprout.all { it.word.length in 4..6 }, sprout.filterNot { it.word.length in 4..6 }.toString())
        assertTrue(sapling.all { it.word.length in 6..9 }, sapling.filterNot { it.word.length in 6..9 }.toString())
        assertTrue(sprout.all { entry -> entry.hint != null && entry.hint!!.matches(Regex("[a-z]+")) })
        assertTrue(sapling.all { it.hint == null })
        assertTrue(WordList.forDifficulty(Difficulty.SEEDLING).isEmpty())
        assertEquals(sprout + sapling, WordList.all)
    }

    @Test
    fun noTwoListedWordsAreAnagrams() {
        val byLetters = WordList.all.groupBy { it.word.toList().sorted().joinToString("") }
        assertEquals(emptyList(), byLetters.values.filter { it.size > 1 }.map { group -> group.map { it.word } })
    }

    @Test
    fun aRoundAcceptsItsWordInAnyCaseAndNothingElse() {
        val round = WordRound(word = "lemon", scrambled = "nmleo", hint = "food")
        assertTrue(round.accepts("lemon"))
        assertTrue(round.accepts("LEMON"))
        assertFalse(round.accepts("melon"), "not a listed word")
        assertFalse(round.accepts("nmleo"))
        assertFalse(round.accepts("lemo"))
        // Another listed word with the same letters would be accepted too.
        val listed = WordList.all.first { it.word == "ocean" }.word
        assertTrue(WordRound(word = "acnoe", scrambled = "eonca", hint = null).accepts(listed))
        assertFalse(WordRound(word = "acnoe", scrambled = "eonca", hint = null).accepts("canoe"))
    }

    @Test
    fun wordListIsLowerCaseAsciiWithoutDuplicates() {
        val words = WordList.all.map { it.word }
        assertTrue(words.size in 280..400, "about 300 words, got ${words.size}")
        assertEquals(words.size, words.toSet().size, words.groupBy { it }.filterValues { it.size > 1 }.keys.toString())
        words.forEach { word ->
            assertTrue(word.matches(Regex("[a-z]+")), word)
            assertTrue(word.toSet().size >= 2, word)
        }
    }
}

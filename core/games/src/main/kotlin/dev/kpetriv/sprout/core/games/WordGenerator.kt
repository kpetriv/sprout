package dev.kpetriv.sprout.core.games

import kotlin.random.Random

/** Builds word rounds: distinct words from the difficulty's band, each scrambled into a different order. */
internal object WordGenerator {
    fun session(difficulty: Difficulty, rounds: Int, random: Random): List<WordRound> {
        val band = WordList.forDifficulty(difficulty)
        require(band.size >= rounds) { "Not enough words at $difficulty" }
        val withHint = difficulty == Difficulty.SPROUT
        return band.shuffled(random).take(rounds).map { entry ->
            WordRound(entry.word, scramble(entry.word, random), entry.hint.takeIf { withHint })
        }
    }

    /** A permutation of [word] that differs from it. [word] must have at least two distinct letters. */
    fun scramble(word: String, random: Random): String {
        require(word.toSet().size >= 2) { "Cannot scramble a word without two distinct letters" }
        var scrambled: String
        do {
            scrambled = word.toList().shuffled(random).joinToString("")
        } while (scrambled == word)
        return scrambled
    }
}

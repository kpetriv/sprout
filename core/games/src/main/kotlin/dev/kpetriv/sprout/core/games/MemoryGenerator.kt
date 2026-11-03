package dev.kpetriv.sprout.core.games

import kotlin.random.Random

/** Builds memory rounds whose sequence length grows across the session within the difficulty's band. */
internal object MemoryGenerator {
    private val seedlingLengths = 3..5
    private val sproutLengths = 4..7
    private val saplingLengths = 5..9
    private const val SAPLING_DISTRACTORS = 2

    fun lengths(difficulty: Difficulty): IntRange = when (difficulty) {
        Difficulty.SEEDLING -> seedlingLengths
        Difficulty.SPROUT -> sproutLengths
        Difficulty.SAPLING -> saplingLengths
    }

    fun session(difficulty: Difficulty, rounds: Int, random: Random): List<MemoryRound> {
        val band = lengths(difficulty)
        val span = band.last - band.first
        return List(rounds) { index ->
            // Linear ramp from the shortest length on the first round to the longest on the last.
            val length = band.first + if (rounds > 1) index * span / (rounds - 1) else 0
            generate(difficulty, length, random)
        }
    }

    private fun generate(difficulty: Difficulty, length: Int, random: Random): MemoryRound {
        val shuffled = MemorySymbols.forDifficulty(difficulty).shuffled(random)
        val sequence = shuffled.take(length)
        val distractors = if (difficulty == Difficulty.SAPLING) {
            shuffled.drop(length).take(SAPLING_DISTRACTORS)
        } else {
            emptyList()
        }
        return MemoryRound(sequence = sequence, choices = (sequence + distractors).shuffled(random))
    }
}

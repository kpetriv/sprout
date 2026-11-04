package dev.kpetriv.sprout.core.games

import kotlin.random.Random

/**
 * One play of a game: its [rounds], generated deterministically from [seed] (the same id, seed and mode always give
 * the same rounds, so a screen can store just the seed and resume after process death).
 *
 * @property timed timed mode; only difficulties where [GameCatalogue.supportsTimed] is true (Sapling) allow it.
 */
class GameSession private constructor(
    val gameId: GameId,
    val seed: Long,
    val timed: Boolean,
    val rounds: List<Round>,
) {
    /** The round's limit in timed mode (arithmetic 8 s, memory 10 s, words 15 s). Not enforced when not [timed]. */
    fun timeLimitMs(round: Round): Long = GameCatalogue.timeLimitMs(round.kind)

    companion object {
        /** Throws [IllegalArgumentException] if [timed] is requested at a difficulty that does not support it. */
        fun create(gameId: GameId, seed: Long, timed: Boolean = false): GameSession {
            require(!timed || GameCatalogue.supportsTimed(gameId.difficulty)) {
                "Timed mode is not offered at ${gameId.difficulty}"
            }
            val random = Random(seed)
            val count = GameCatalogue.rounds(gameId)
            val difficulty = gameId.difficulty
            val rounds: List<Round> = when (gameId.kind) {
                GameKind.ARITHMETIC -> ArithmeticGenerator.session(difficulty, count, random)
                GameKind.MEMORY -> MemoryGenerator.session(difficulty, count, random)
                GameKind.WORDS -> WordGenerator.session(difficulty, count, random)
            }
            return GameSession(gameId, seed, timed, rounds)
        }
    }
}

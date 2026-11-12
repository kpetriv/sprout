package dev.kpetriv.sprout.core.games

import kotlin.random.Random

/**
 * One play of a game: its [rounds], generated deterministically from [seed] (the same id, seed, mode and setup
 * always give the same rounds, so a screen can store just the seed and the setup and resume after process death).
 *
 * @property timed timed mode. For arithmetic, memory and words only difficulties where
 *   [GameCatalogue.supportsTimed] is true (Sapling) allow it; for Times tables it is the level's
 *   ([TablesLevel.timed]: Rock star only), whatever was asked for.
 * @property setup the Times tables setup; null for every other game.
 */
class GameSession private constructor(
    val gameId: GameId,
    val seed: Long,
    val timed: Boolean,
    val setup: TablesSetup?,
    val rounds: List<Round>,
) {
    /**
     * The round's limit: arithmetic 8 s, memory 10 s, words 15 s, and for Times tables the level's
     * ([TablesLevel.timeLimitMs]: 20, 10 or 5 s). Only enforced (by the timed bonus) when [timed]; otherwise it is
     * what an informational countdown shows.
     */
    fun timeLimitMs(round: Round): Long = when {
        round is TablesRound && setup != null -> setup.level.timeLimitMs
        else -> GameCatalogue.timeLimitMs(round.kind)
    }

    companion object {
        /**
         * Throws [IllegalArgumentException] if [timed] is requested for a game that does not support it, if [setup]
         * is missing for Times tables, or if one is given for any other game. For Times tables [timed] is ignored:
         * the session is timed exactly when [TablesSetup.level] is [TablesLevel.ROCK_STAR].
         */
        fun create(gameId: GameId, seed: Long, timed: Boolean = false, setup: TablesSetup? = null): GameSession {
            val tables = gameId.kind == GameKind.TABLES
            require(!tables || setup != null) { "Times tables need a setup" }
            require(tables || setup == null) { "Only Times tables take a setup" }
            require(!timed || GameCatalogue.supportsTimed(gameId)) { "Timed mode is not offered for $gameId" }
            val random = Random(seed)
            val count = GameCatalogue.rounds(gameId)
            val difficulty = gameId.difficulty
            val rounds: List<Round> = when (gameId.kind) {
                GameKind.ARITHMETIC -> ArithmeticGenerator.session(difficulty, count, random)
                GameKind.MEMORY -> MemoryGenerator.session(difficulty, count, random)
                GameKind.WORDS -> WordGenerator.session(difficulty, count, random)
                GameKind.TABLES -> TablesGenerator.session(checkNotNull(setup), count, random)
            }
            val sessionTimed = setup?.level?.timed ?: timed
            return GameSession(gameId, seed, sessionTimed, setup, rounds)
        }
    }
}

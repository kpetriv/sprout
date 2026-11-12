package dev.kpetriv.sprout.core.games

/**
 * The only place a score becomes credit. Amounts are minor units of the fiat currency as a plain [Long]: this module
 * cannot depend on `core/domain-ledger`, so the ledger wraps the result in `Money`.
 *
 * Credit is 1 per correct round, +3 for a perfect play, +2 for the timed bonus, capped at [MAX_PER_PLAY]. The same rule
 * holds for every game, Times tables included (20 rounds, so any play with 15 or more right earns the cap: the
 * level is a choice of difficulty, not of pay, D-M9-G8).
 */
object GameCredit {
    const val MAX_PER_PLAY: Long = 15
    const val PER_CORRECT: Long = 1
    const val PERFECT_BONUS: Long = 3
    const val TIMED_BONUS: Long = 2

    /**
     * Credit for [score] in [gameId], or null if `score.correct` is outside `0..rounds(gameId)`. The bonuses count
     * only when consistent: perfect needs every round correct, and the timed bonus also needs a perfect play of a
     * game that offers timed mode ([GameCatalogue.supportsTimed]). So this never exceeds [earned] for the same id
     * and correct count.
     */
    fun earned(gameId: GameId, score: Score): Long? {
        val rounds = GameCatalogue.rounds(gameId)
        if (score.correct !in 0..rounds) return null
        val perfect = score.perfect && score.correct == rounds
        val timed = score.timedBonus && perfect && GameCatalogue.supportsTimed(gameId)
        return credit(score.correct, perfect, timed)
    }

    /**
     * The most a play of [gameIdWire] with [correct] rounds right can earn, as the ledger rule re-derives it from a
     * `GamePlayed` event (which carries no timed flag, so the timed bonus is assumed wherever it was possible).
     * Null for an unknown id or [correct] outside `0..rounds`.
     */
    fun earned(gameIdWire: String, correct: Int): Long? {
        val gameId = GameId.parse(gameIdWire)
        val rounds = gameId?.let(GameCatalogue::rounds)
        if (gameId == null || rounds == null || correct !in 0..rounds) return null
        val perfect = correct == rounds
        return credit(correct, perfect, perfect && GameCatalogue.supportsTimed(gameId))
    }

    private fun credit(correct: Int, perfect: Boolean, timedBonus: Boolean): Long {
        val total = correct * PER_CORRECT +
            (if (perfect) PERFECT_BONUS else 0) +
            (if (timedBonus) TIMED_BONUS else 0)
        return minOf(total, MAX_PER_PLAY)
    }
}

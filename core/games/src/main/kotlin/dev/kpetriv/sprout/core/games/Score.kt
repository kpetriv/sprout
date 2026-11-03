package dev.kpetriv.sprout.core.games

/** How one round went: whether it was answered correctly and how long it took. */
data class RoundOutcome(val correct: Boolean, val elapsedMs: Long) {
    init {
        require(elapsedMs >= 0) { "elapsedMs must not be negative" }
    }
}

/**
 * The result of a play.
 *
 * @property perfect every round correct.
 * @property timedBonus a timed session with every round correct within its limit.
 */
data class Score(val correct: Int, val total: Int, val perfect: Boolean, val timedBonus: Boolean)

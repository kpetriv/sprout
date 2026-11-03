package dev.kpetriv.sprout.core.games

/** Turns a session's round outcomes into a [Score]. */
object GameScorer {
    /** [outcomes] must have one entry per round of [session], in order. */
    fun score(session: GameSession, outcomes: List<RoundOutcome>): Score {
        require(outcomes.size == session.rounds.size) {
            "Expected ${session.rounds.size} outcomes, got ${outcomes.size}"
        }
        val correct = outcomes.count { it.correct }
        val perfect = correct == outcomes.size
        val inTime = session.rounds.zip(outcomes).all { (round, outcome) ->
            outcome.elapsedMs <= session.timeLimitMs(round)
        }
        return Score(
            correct = correct,
            total = outcomes.size,
            perfect = perfect,
            timedBonus = session.timed && perfect && inTime,
        )
    }
}

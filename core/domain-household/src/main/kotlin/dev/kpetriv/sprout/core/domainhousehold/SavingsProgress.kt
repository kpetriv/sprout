package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.domainledger.Money

/**
 * How far a [SavingsGoal] is, as the exact fraction [numerator] / [denominator] in 0..1 (no floating point; the UI
 * converts it for drawing). [denominator] is the goal's target; [numerator] is the savings balance clamped to
 * 0..target, so an overdrawn account shows 0 and an overshoot shows 1.
 */
data class SavingsProgress(val numerator: Long, val denominator: Long) {
    init {
        require(denominator > 0) { "denominator must be positive" }
        require(numerator in 0..denominator) { "progress must be in 0..1" }
    }

    /** Whether the target is reached. */
    val isComplete: Boolean get() = numerator == denominator

    /** Minor units still to save; zero once complete. */
    val remaining: Money get() = Money(denominator - numerator)

    companion object {
        /** Progress of [goal] given the kid's SAVINGS balance [savingsBalance] (same currency as the target). */
        fun of(goal: SavingsGoal, savingsBalance: Money): SavingsProgress {
            val target = goal.target.minorUnits
            return SavingsProgress(savingsBalance.minorUnits.coerceIn(0, target), target)
        }
    }
}

package dev.kpetriv.sprout.core.model

import kotlinx.serialization.Serializable

/**
 * The household's savings interest (D-M2-5): simple interest, Actual/365, accrued monthly on [accrualDay] on each
 * kid's SAVINGS balance. Off (0 bps) until a parent sets a rate.
 *
 * @property annualRateBasisPoints 0 to [MAX_ANNUAL_RATE_BASIS_POINTS] (20%); 100 bps = 1% a year.
 * @property accrualDay day of month, 1 to [MAX_SCHEDULE_DAY_OF_MONTH].
 */
@Serializable
data class InterestPolicy(val householdId: HouseholdId, val annualRateBasisPoints: Int = 0, val accrualDay: Int = 1) {
    init {
        require(annualRateBasisPoints in 0..MAX_ANNUAL_RATE_BASIS_POINTS) {
            "annualRateBasisPoints must be in 0..$MAX_ANNUAL_RATE_BASIS_POINTS"
        }
        require(accrualDay in 1..MAX_SCHEDULE_DAY_OF_MONTH) { "accrualDay must be in 1..$MAX_SCHEDULE_DAY_OF_MONTH" }
    }

    /** Whether interest accrues at all. */
    val isEnabled: Boolean get() = annualRateBasisPoints > 0

    companion object {
        /** Highest rate a parent can set: 2000 bps, 20% a year. */
        const val MAX_ANNUAL_RATE_BASIS_POINTS: Int = 2000
    }
}

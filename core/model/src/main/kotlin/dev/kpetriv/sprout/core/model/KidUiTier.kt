package dev.kpetriv.sprout.core.model

/**
 * How kid-mode screens present the same data. Suggested from the kid's age; a parent can override it.
 */
enum class KidUiTier {
    /** Roughly ages 5 to 7: pictures, whole numbers, big touch targets. */
    SEEDLING,

    /** Roughly ages 8 to 11: real currency, progress bars, short history. */
    SPROUT,

    /** Roughly 12 and older: full ledger, filters, charts. */
    SAPLING,
    ;

    companion object {
        private const val SPROUT_MIN_AGE = 8
        private const val SAPLING_MIN_AGE = 12

        /** The tier suggested for a kid of [ageYears] whole years. Ages under 8 map to [SEEDLING]. */
        fun suggestedFor(ageYears: Int): KidUiTier {
            require(ageYears >= 0) { "ageYears must not be negative" }
            return when {
                ageYears >= SAPLING_MIN_AGE -> SAPLING
                ageYears >= SPROUT_MIN_AGE -> SPROUT
                else -> SEEDLING
            }
        }
    }
}

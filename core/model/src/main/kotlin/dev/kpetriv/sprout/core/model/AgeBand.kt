package dev.kpetriv.sprout.core.model

private const val EARLY_MIN_AGE = 5
private const val MIDDLE_MIN_AGE = 8
private const val TEEN_MIN_AGE = 12

/**
 * Coarse age groups used for content suggestions (chores, games) and the default [KidUiTier].
 * Bands are inclusive whole years; [AGES_12_PLUS] is open-ended.
 */
enum class AgeBand(val minAge: Int, val maxAge: Int?) {
    UNDER_5(0, EARLY_MIN_AGE - 1),
    AGES_5_TO_7(EARLY_MIN_AGE, MIDDLE_MIN_AGE - 1),
    AGES_8_TO_11(MIDDLE_MIN_AGE, TEEN_MIN_AGE - 1),
    AGES_12_PLUS(TEEN_MIN_AGE, null),
    ;

    /** The UI tier suggested for this band. Under-5s get the simplest tier. */
    val suggestedTier: KidUiTier get() = KidUiTier.suggestedFor(minAge)

    operator fun contains(ageYears: Int): Boolean = ageYears >= minAge && (maxAge == null || ageYears <= maxAge)

    companion object {
        /** The band for a kid of [ageYears] whole years. */
        fun forAge(ageYears: Int): AgeBand {
            require(ageYears >= 0) { "ageYears must not be negative" }
            return entries.first { ageYears in it }
        }

        /** The band for a kid born in [birth], as of [today]. A birth month in the future is rejected. */
        fun of(birth: YearMonth, today: YearMonth): AgeBand {
            require(birth <= today) { "birth month must not be after today" }
            return forAge(birth.yearsUntil(today))
        }
    }
}

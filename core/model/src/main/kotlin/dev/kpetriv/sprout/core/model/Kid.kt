package dev.kpetriv.sprout.core.model

import kotlinx.serialization.Serializable

/** Longest first name accepted. */
const val MAX_FIRST_NAME_LENGTH: Int = 40

/**
 * A kid profile. Holds only what the app needs: a first name, an avatar from the built-in set, and the birth
 * month (never a full date of birth). [toString] redacts the name so a profile never ends up in a log.
 *
 * @property uiTier the tier kid-mode screens render with; suggested from age, overridable by a parent.
 * @property archived archived kids keep their ledger history but are hidden from day-to-day screens.
 */
@Serializable
data class Kid(
    val id: KidId,
    val householdId: HouseholdId,
    val firstName: String,
    val avatarId: String,
    val birthYearMonth: YearMonth,
    val uiTier: KidUiTier,
    val archived: Boolean = false,
) {
    init {
        require(firstName.isNotBlank()) { "firstName must not be blank" }
        require(firstName.length <= MAX_FIRST_NAME_LENGTH) { "firstName is too long" }
        require(avatarId.isNotBlank()) { "avatarId must not be blank" }
    }

    /** Whole years old as of [today]. */
    fun ageOn(today: YearMonth): Int = birthYearMonth.yearsUntil(today)

    /** The kid's [AgeBand] as of [today]. */
    fun ageBandOn(today: YearMonth): AgeBand = AgeBand.of(birthYearMonth, today)

    override fun toString(): String =
        "Kid(id=$id, householdId=$householdId, firstName=<redacted>, avatarId=$avatarId, " +
            "birthYearMonth=<redacted>, uiTier=$uiTier, archived=$archived)"
}

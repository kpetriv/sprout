package dev.kpetriv.sprout.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class KidTest {
    private val kid = Kid(
        id = KidId("kid-1"),
        householdId = HouseholdId("house-1"),
        firstName = "Ada",
        avatarId = "fox",
        birthYearMonth = YearMonth(2018, 6),
        uiTier = KidUiTier.SPROUT,
    )

    @Test
    fun serialisationRoundTrips() {
        val json = Json.encodeToString(Kid.serializer(), kid)
        assertEquals(kid, Json.decodeFromString(Kid.serializer(), json))
        assertFalse(kid.archived)
    }

    @Test
    fun toStringNeverContainsTheNameOrBirthMonth() {
        val text = kid.toString()
        assertFalse("Ada" in text)
        assertFalse("2018" in text)
    }

    @Test
    fun ageAndBandFollowTheBirthMonth() {
        assertEquals(7, kid.ageOn(YearMonth(2026, 5)))
        assertEquals(AgeBand.AGES_5_TO_7, kid.ageBandOn(YearMonth(2026, 5)))
        assertEquals(AgeBand.AGES_8_TO_11, kid.ageBandOn(YearMonth(2026, 6)))
    }

    @Test
    fun invalidProfilesAreRejected() {
        assertFailsWith<IllegalArgumentException> { kid.copy(firstName = " ") }
        assertFailsWith<IllegalArgumentException> { kid.copy(firstName = "x".repeat(MAX_FIRST_NAME_LENGTH + 1)) }
        assertFailsWith<IllegalArgumentException> { kid.copy(avatarId = "") }
    }
}

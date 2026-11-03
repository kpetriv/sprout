package dev.kpetriv.sprout.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgeBandTest {
    @Test
    fun everyAgeMapsToExactlyOneBand() {
        for (age in 0..30) {
            assertEquals(1, AgeBand.entries.count { age in it }, "age $age")
        }
    }

    @Test
    fun boundaries() {
        assertEquals(AgeBand.UNDER_5, AgeBand.forAge(4))
        assertEquals(AgeBand.AGES_5_TO_7, AgeBand.forAge(5))
        assertEquals(AgeBand.AGES_5_TO_7, AgeBand.forAge(7))
        assertEquals(AgeBand.AGES_8_TO_11, AgeBand.forAge(8))
        assertEquals(AgeBand.AGES_8_TO_11, AgeBand.forAge(11))
        assertEquals(AgeBand.AGES_12_PLUS, AgeBand.forAge(12))
        assertEquals(AgeBand.AGES_12_PLUS, AgeBand.forAge(17))
    }

    @Test
    fun suggestedTierMatchesKidUiTier() {
        assertEquals(KidUiTier.SEEDLING, AgeBand.UNDER_5.suggestedTier)
        assertEquals(KidUiTier.SEEDLING, AgeBand.AGES_5_TO_7.suggestedTier)
        assertEquals(KidUiTier.SPROUT, AgeBand.AGES_8_TO_11.suggestedTier)
        assertEquals(KidUiTier.SAPLING, AgeBand.AGES_12_PLUS.suggestedTier)
    }

    @Test
    fun invalidInputIsRejected() {
        assertFailsWith<IllegalArgumentException> { AgeBand.forAge(-1) }
        assertFailsWith<IllegalArgumentException> { AgeBand.of(YearMonth(2027, 1), YearMonth(2026, 1)) }
    }
}

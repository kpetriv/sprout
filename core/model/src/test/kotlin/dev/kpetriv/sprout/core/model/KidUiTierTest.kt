package dev.kpetriv.sprout.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KidUiTierTest {
    @Test
    fun youngestKidsGetSeedling() {
        (0..7).forEach { age -> assertEquals(KidUiTier.SEEDLING, KidUiTier.suggestedFor(age), "age $age") }
    }

    @Test
    fun eightToElevenGetSprout() {
        (8..11).forEach { age -> assertEquals(KidUiTier.SPROUT, KidUiTier.suggestedFor(age), "age $age") }
    }

    @Test
    fun twelveAndOlderGetSapling() {
        listOf(12, 13, 17, 30).forEach { age ->
            assertEquals(KidUiTier.SAPLING, KidUiTier.suggestedFor(age), "age $age")
        }
    }

    @Test
    fun negativeAgeIsRejected() {
        assertFailsWith<IllegalArgumentException> { KidUiTier.suggestedFor(-1) }
    }

    @Test
    fun tiersAreOrderedYoungestFirst() {
        assertEquals(listOf(KidUiTier.SEEDLING, KidUiTier.SPROUT, KidUiTier.SAPLING), KidUiTier.entries)
    }
}

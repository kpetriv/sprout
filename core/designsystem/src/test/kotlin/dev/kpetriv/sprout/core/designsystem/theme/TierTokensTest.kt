package dev.kpetriv.sprout.core.designsystem.theme

import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TierTokensTest {
    @Test
    fun eachTierMapsToItsTokens() {
        assertEquals(TierTokens.Seedling, TierTokens.forTier(KidUiTier.SEEDLING))
        assertEquals(TierTokens.Sprout, TierTokens.forTier(KidUiTier.SPROUT))
        assertEquals(TierTokens.Sapling, TierTokens.forTier(KidUiTier.SAPLING))
    }

    @Test
    fun youngerTiersGetLargerValues() {
        val ordered = KidUiTier.entries.map(TierTokens::forTier) // youngest first
        ordered.zipWithNext { younger, older ->
            assertTrue(younger.typeScale > older.typeScale)
            assertTrue(younger.minTouchTarget > older.minTouchTarget)
            assertTrue(younger.cornerRadius > older.cornerRadius)
            assertTrue(younger.buttonHeight > older.buttonHeight)
            // ColourLevel is declared from most to least colour: younger tiers never get less.
            assertTrue(younger.colourLevel < older.colourLevel)
        }
    }

    @Test
    fun cornerRadiiAreTheMorningGardenCardRadii() {
        assertEquals(listOf(28.dp, 24.dp, 18.dp), KidUiTier.entries.map { TierTokens.forTier(it).cornerRadius })
    }

    @Test
    fun colourDialMatchesTheSpec() {
        assertEquals(ColourLevel.FULL, TierTokens.Seedling.colourLevel)
        assertEquals(ColourLevel.ACCENTED, TierTokens.Sprout.colourLevel)
        assertEquals(ColourLevel.RESTRAINED, TierTokens.Sapling.colourLevel)
    }

    @Test
    fun parentScreensMatchSaplingExceptForMaterialSizedButtons() {
        assertEquals(TierTokens.Sapling.copy(buttonHeight = 48.dp), TierTokens.Parent)
        assertEquals(ColourLevel.RESTRAINED, TierTokens.Parent.colourLevel)
    }

    @Test
    fun touchTargetsNeverDropBelowMaterialMinimum() {
        KidUiTier.entries.forEach { tier -> assertTrue(TierTokens.forTier(tier).minTouchTarget >= 48.dp) }
    }

    @Test
    fun saplingKeepsTheMaterialBaselineTypeScale() {
        assertEquals(1f, TierTokens.Sapling.typeScale)
    }
}

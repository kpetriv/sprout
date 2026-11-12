package dev.kpetriv.sprout.core.designsystem.theme

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SproutTypographyTest {
    private val base = SproutTypography

    @Test
    fun displayFaceCarriesTitlesAndNumbers() {
        val display = listOf(
            base.displayLarge,
            base.displayMedium,
            base.displaySmall,
            base.headlineLarge,
            base.headlineMedium,
            base.headlineSmall,
            base.titleLarge,
        )
        display.forEach { assertEquals(OutfitFontFamily, it.fontFamily) }
        assertEquals(FontWeight.ExtraBold, base.displayLarge.fontWeight)
        display.drop(1).forEach { assertEquals(FontWeight.Bold, it.fontWeight) }
        display.dropLast(1).forEach { assertEquals("tnum", it.fontFeatureSettings) }
    }

    @Test
    fun textFaceCarriesEverythingElse() {
        listOf(base.titleMedium, base.titleSmall, base.bodyLarge).forEach {
            assertEquals(PlusJakartaSansFontFamily, it.fontFamily)
            assertEquals(FontWeight.SemiBold, it.fontWeight)
        }
        listOf(base.bodyMedium, base.bodySmall).forEach {
            assertEquals(PlusJakartaSansFontFamily, it.fontFamily)
            assertEquals(FontWeight.Medium, it.fontWeight)
        }
        listOf(base.labelLarge, base.labelMedium, base.labelSmall).forEach {
            assertEquals(PlusJakartaSansFontFamily, it.fontFamily)
            assertEquals(FontWeight.Bold, it.fontWeight)
        }
    }

    @Test
    fun sizesStayMaterials() {
        val material = androidx.compose.material3.Typography()
        assertEquals(material.displayLarge.fontSize, base.displayLarge.fontSize)
        assertEquals(material.headlineMedium.fontSize, base.headlineMedium.fontSize)
        assertEquals(material.bodyMedium.fontSize, base.bodyMedium.fontSize)
        assertEquals(material.labelSmall.fontSize, base.labelSmall.fontSize)
        assertEquals(material.labelMedium.fontSize, base.labelMedium.fontSize)
        assertEquals(20f, base.bodySmall.lineHeight.value)
    }

    @Test
    fun bodyTextIncludingBodySmallIsAtLeast14spAndKidBodyAtLeast16sp() {
        fun TextUnit.atLeast(min: Float) = assertTrue(value >= min - 0.01f, "$this < $min sp")
        listOf(base.bodyLarge, base.bodyMedium, base.bodySmall).forEach { it.fontSize.atLeast(14f) }
        listOf(KidUiTier.SEEDLING, KidUiTier.SPROUT).forEach { tier ->
            val scaled = base.scaledBy(TierTokens.forTier(tier).typeScale)
            listOf(scaled.bodyLarge, scaled.bodyMedium, scaled.bodySmall).forEach { it.fontSize.atLeast(16f) }
        }
    }

    @Test
    fun scalingKeepsTheFaces() {
        val seedling = base.scaledBy(TierTokens.Seedling.typeScale)
        assertEquals(OutfitFontFamily, seedling.displayLarge.fontFamily)
        assertEquals(base.displayLarge.fontSize * 1.3f, seedling.displayLarge.fontSize)
    }
}

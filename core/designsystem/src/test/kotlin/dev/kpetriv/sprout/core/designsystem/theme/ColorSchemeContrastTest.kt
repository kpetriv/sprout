package dev.kpetriv.sprout.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Sprout's own container colours: set (never Material's defaults) and readable (WCAG AA, 4.5:1). */
class ColorSchemeContrastTest {
    private val schemes = mapOf("light" to SproutLightColors, "dark" to SproutDarkColors)

    @Test
    fun containerTextMeetsWcagAa() {
        schemes.forEach { (name, scheme) ->
            pairs(scheme).forEach { (label, pair) ->
                val ratio = contrast(pair.first, pair.second)
                assertTrue(ratio >= AA, "$name $label contrast is $ratio")
            }
        }
    }

    @Test
    fun tertiaryContainersAreSproutsNotMaterialDefaults() {
        // Material's default tertiary container is pink; the household cards and "proposed" chips use it (D-REL-12).
        assertNotEquals(lightColorScheme().tertiaryContainer, SproutLightColors.tertiaryContainer)
        assertNotEquals(lightColorScheme().onTertiaryContainer, SproutLightColors.onTertiaryContainer)
        assertNotEquals(darkColorScheme().tertiaryContainer, SproutDarkColors.tertiaryContainer)
        assertNotEquals(darkColorScheme().onTertiaryContainer, SproutDarkColors.onTertiaryContainer)
    }

    private fun pairs(scheme: ColorScheme) = mapOf(
        "primaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
        "secondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
        "tertiaryContainer" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
        // The dashboard's security cards and Settings' security states (D-REL-17).
        "errorContainer" to (scheme.onErrorContainer to scheme.errorContainer),
        "surface" to (scheme.onSurface to scheme.surface),
    )

    private fun contrast(a: Color, b: Color): Double {
        val (lighter, darker) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (lighter + FLARE) / (darker + FLARE)
    }

    private fun luminance(color: Color): Double =
        R * channel(color.red) + G * channel(color.green) + B * channel(color.blue)

    private fun channel(value: Float): Double =
        if (value <= LINEAR_LIMIT) value / LINEAR_DIVISOR else ((value + OFFSET) / SCALE).pow(GAMMA)

    private companion object {
        const val AA = 4.5
        const val FLARE = 0.05
        const val R = 0.2126
        const val G = 0.7152
        const val B = 0.0722
        const val LINEAR_LIMIT = 0.03928
        const val LINEAR_DIVISOR = 12.92
        const val OFFSET = 0.055
        const val SCALE = 1.055
        const val GAMMA = 2.4
    }
}

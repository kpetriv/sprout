package dev.kpetriv.sprout.core.testing.screenshot

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.LocalWindowWidthClass
import dev.kpetriv.sprout.core.ui.WindowWidthClass
import dev.kpetriv.sprout.core.ui.rememberWindowWidthClass
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.test.assertEquals

/** The new capture helpers really change the window and the font scale (they capture nothing under plain `test`). */
class ScreenshotRuleTest : ScreenshotTest(checkAccessibility = true) {
    private val seen = mutableListOf<String>()

    @Test
    fun `width classes render in a phone and a tablet window`() {
        screenshot.captureWidthClasses("rule_width_classes") { provided ->
            // The window's own class, as a screen without the override would see it.
            var measured: WindowWidthClass? = null
            CompositionLocalProvider(LocalWindowWidthClass provides null) { measured = rememberWindowWidthClass() }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                seen += "$provided ${rememberWindowWidthClass()} $measured ${maxWidth.value.roundToInt()}"
                Text("$provided")
            }
        }

        assertEquals(
            listOf("Compact Compact Compact 411", "Expanded Expanded Expanded 1280"),
            seen.distinct(),
        )
    }

    @Test
    fun `the portrait tablet window is 800 dp wide and Medium`() {
        screenshot.captureWidthClasses(
            "rule_width_portrait",
            windows = listOf(ScreenshotWindow.TABLET_PORTRAIT),
        ) { provided ->
            var measured: WindowWidthClass? = null
            CompositionLocalProvider(LocalWindowWidthClass provides null) { measured = rememberWindowWidthClass() }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                seen += "$provided ${rememberWindowWidthClass()} $measured ${maxWidth.value.roundToInt()}"
                Text("$provided")
            }
        }

        assertEquals(listOf("Medium Medium Medium 800"), seen.distinct())
    }

    @Test
    fun `width classes per tier name every window and tier`() {
        screenshot.captureWidthClassTiers("rule_width_tiers", tiers = listOf(KidUiTier.SEEDLING)) { widthClass, tier ->
            seen += "$widthClass $tier"
            Text("$tier")
        }

        assertEquals(listOf("Compact SEEDLING", "Expanded SEEDLING"), seen.distinct())
    }

    @Test
    fun `font scale doubles text`() {
        screenshot.captureFontScale("rule_font_scale") {
            seen += "${LocalDensity.current.fontScale}"
            Text("Large")
        }

        assertEquals(listOf("2.0"), seen.distinct())
    }
}

package dev.kpetriv.sprout.core.testing.screenshot

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyOutputDirectory
import dev.kpetriv.sprout.core.designsystem.theme.ProvideKidTier
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Captures Compose screenshots with Roborazzi inside [SproutTheme]. Use it through [ScreenshotTest], which also
 * pins the Robolectric SDK, device and graphics mode.
 *
 * Each `capture*` call sets the content once and swaps variants through state, re-creating the composition for
 * each one (so nothing `remember`ed leaks between variants), then saves `<name>.png` in the module's
 * `src/test/screenshots`. Under plain `test` the captures are no-ops; `recordRoborazziDebug` writes the goldens
 * and `verifyRoborazziDebug` compares against them. Call one `capture*` function per test.
 *
 * Before each capture the virtual clock auto-advances until Compose is idle, so finite animations have finished;
 * infinite ones (indeterminate progress) are cancelled by the Compose test framework and draw their first frame.
 *
 * Every capture is compared with [options], by default [DEFAULT_OPTIONS]: the small tolerance of decision D-M2-11
 * (`docs/SCREENSHOT_TESTS.md`) that absorbs anti-aliasing noise between macOS and Linux.
 */
class ScreenshotRule(private val options: RoborazziOptions = DEFAULT_OPTIONS) : TestRule {
    /** The underlying Compose rule, for tests that need to interact before or between captures. */
    val compose: ComposeContentTestRule = createComposeRule()

    private val chain: RuleChain = RuleChain.outerRule(FixedLocaleTimeZoneRule()).around(compose)

    override fun apply(base: Statement, description: Description): Statement = chain.apply(base, description)

    /**
     * Captures [content] once per tier as `<name>_<tier>.png` (e.g. `wallet_seedling.png`), inside
     * [ProvideKidTier]. Kid-mode screens must cover every tier (CLAUDE.md), which is the default.
     */
    fun captureTiers(
        name: String,
        tiers: List<KidUiTier> = KidUiTier.entries,
        darkTheme: Boolean = false,
        content: @Composable (KidUiTier) -> Unit,
    ) {
        captureVariants(
            tiers.map { tier ->
                Variant("${name}_${tier.name.lowercase()}", darkTheme) { ProvideKidTier(tier) { content(tier) } }
            },
        )
    }

    /** Captures [content] in the light and dark theme as `<name>_light.png` and `<name>_dark.png`. */
    fun captureLightDark(name: String, content: @Composable () -> Unit) {
        captureVariants(
            listOf(
                Variant("${name}_light", darkTheme = false, content),
                Variant("${name}_dark", darkTheme = true, content),
            ),
        )
    }

    /** Captures [content] once as `<name>.png`. */
    fun capture(name: String, darkTheme: Boolean = false, content: @Composable () -> Unit) {
        captureVariants(listOf(Variant(name, darkTheme, content)))
    }

    // roborazziSystemPropertyOutputDirectory() is the plugin's outputDir (src/test/screenshots), passed to the tests.
    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureVariants(variants: List<Variant>) {
        require(variants.isNotEmpty()) { "Nothing to capture" }
        require(variants.map { it.name }.toSet().size == variants.size) { "Duplicate screenshot names" }
        val outputDirectory = roborazziSystemPropertyOutputDirectory()
        var current by mutableStateOf(variants.first())
        compose.setContent {
            val variant = current
            key(variant.name) {
                SproutTheme(darkTheme = variant.darkTheme) {
                    Surface(color = MaterialTheme.colorScheme.background) { variant.content() }
                }
            }
        }
        variants.forEach { variant ->
            compose.runOnIdle { current = variant }
            compose.waitForIdle()
            compose.onRoot().captureRoboImage(
                filePath = "$outputDirectory/${variant.name}.png",
                roborazziOptions = options,
            )
        }
    }

    private class Variant(val name: String, val darkTheme: Boolean, val content: @Composable () -> Unit)

    companion object {
        /**
         * The comparison every golden is verified with (D-M2-11). A pixel counts as changed once its colour moves by
         * more than [MAX_DISTANCE], unless it matches a golden pixel up to [SHIFT_PX] away (anti-aliased edges); the
         * verify fails once more than [CHANGE_THRESHOLD] of the pixels changed. Do not loosen these to make a diff
         * pass: re-record and review instead.
         */
        val DEFAULT_OPTIONS: RoborazziOptions = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(
                imageComparator = SimpleImageComparator(
                    maxDistance = MAX_DISTANCE,
                    hShift = SHIFT_PX,
                    vShift = SHIFT_PX,
                ),
                changeThreshold = CHANGE_THRESHOLD,
            ),
        )

        private const val MAX_DISTANCE = 0.007f
        private const val SHIFT_PX = 1
        private const val CHANGE_THRESHOLD = 0.001f
    }
}

package dev.kpetriv.sprout.core.testing.screenshot

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.test.core.app.ActivityScenario
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyOutputDirectory
import dev.kpetriv.sprout.core.designsystem.theme.ProvideKidTier
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.designsystem.theme.TierTokens
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.testing.accessibility.assertAccessible
import dev.kpetriv.sprout.core.ui.LocalWindowWidthClass
import dev.kpetriv.sprout.core.ui.WindowWidthClass
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment
import kotlin.math.roundToInt

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
 * With `screen = true` the whole window is captured, dialogs and bottom sheets included (they are separate windows
 * that a capture of the Compose root does not see); the image is then the size of the device's screen.
 *
 * Every capture is compared with [options], by default [DEFAULT_OPTIONS]: the small tolerance of decision D-M2-11
 * (`docs/SCREENSHOT_TESTS.md`) that absorbs anti-aliasing noise between macOS and Linux.
 *
 * With [checkAccessibility] every variant is also checked with the accessibility rule (M7 §8,
 * `dev.kpetriv.sprout.core.testing.accessibility.AccessibilityRule`) right after it is captured, against the
 * variant's minimum touch target: the tier's for [captureTiers] and the other `*Tiers` captures, 48 dp otherwise.
 */
class ScreenshotRule(
    private val options: RoborazziOptions = DEFAULT_OPTIONS,
    private val checkAccessibility: Boolean = false,
) : TestRule {
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
        screen: Boolean = false,
        content: @Composable (KidUiTier) -> Unit,
    ) {
        captureVariants(
            tiers.map { tier ->
                Variant("${name}_${tier.suffix}", darkTheme, tier.minTouchTarget) {
                    ProvideKidTier(tier) { content(tier) }
                }
            },
            screen,
        )
    }

    /** Captures [content] in the light and dark theme as `<name>_light.png` and `<name>_dark.png`. */
    fun captureLightDark(name: String, screen: Boolean = false, content: @Composable () -> Unit) {
        captureVariants(
            listOf(
                Variant("${name}_light", darkTheme = false, content = content),
                Variant("${name}_dark", darkTheme = true, content = content),
            ),
            screen,
        )
    }

    /** Captures [content] once as `<name>.png`. */
    fun capture(name: String, darkTheme: Boolean = false, screen: Boolean = false, content: @Composable () -> Unit) {
        captureVariants(listOf(Variant(name, darkTheme, content = content)), screen)
    }

    /**
     * Captures [content] at a larger font scale as `<name>_font200.png` (for [fontScale] 2), as if the user had
     * raised the system font size (M7 §8). Text grows; dp sizes do not.
     */
    fun captureFontScale(
        name: String,
        fontScale: Float = LARGE_FONT_SCALE,
        darkTheme: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        captureVariants(
            listOf(Variant("${name}_${fontScale.suffix}", darkTheme) { FontScaled(fontScale, content) }),
        )
    }

    /** [captureFontScale] once per tier: `<name>_font200_<tier>.png`. */
    fun captureFontScaleTiers(
        name: String,
        fontScale: Float = LARGE_FONT_SCALE,
        tiers: List<KidUiTier> = KidUiTier.entries,
        darkTheme: Boolean = false,
        content: @Composable (KidUiTier) -> Unit,
    ) {
        captureVariants(
            tiers.map { tier ->
                Variant("${name}_${fontScale.suffix}_${tier.suffix}", darkTheme, tier.minTouchTarget) {
                    ProvideKidTier(tier) { FontScaled(fontScale) { content(tier) } }
                }
            },
        )
    }

    /**
     * Captures [content] on each of [windows] (a phone and a tablet by default) as `<name>_phone.png` and
     * `<name>_tablet.png`, passing the window's [WindowWidthClass] (also provided as `LocalWindowWidthClass`).
     *
     * Each window is a fresh activity launched with that device's qualifiers ([ScreenshotWindow.qualifiers]), so the
     * content really is laid out in a 1280 x 800 dp window on the tablet; the phone uses the test's own device
     * (including any `@Config(qualifiers = "+h...")` override).
     */
    fun captureWidthClasses(
        name: String,
        windows: List<ScreenshotWindow> = ScreenshotWindow.entries,
        darkTheme: Boolean = false,
        content: @Composable (WindowWidthClass) -> Unit,
    ) {
        captureInWindows(
            windows.map { window ->
                window to Variant("${name}_${window.suffix}", darkTheme) { WidthClassed(window) { content(it) } }
            },
        )
    }

    /** [captureWidthClasses] once per tier: `<name>_phone_<tier>.png` and `<name>_tablet_<tier>.png`. */
    fun captureWidthClassTiers(
        name: String,
        windows: List<ScreenshotWindow> = ScreenshotWindow.entries,
        tiers: List<KidUiTier> = KidUiTier.entries,
        darkTheme: Boolean = false,
        content: @Composable (WindowWidthClass, KidUiTier) -> Unit,
    ) {
        captureInWindows(
            windows.flatMap { window ->
                tiers.map { tier ->
                    window to Variant("${name}_${window.suffix}_${tier.suffix}", darkTheme, tier.minTouchTarget) {
                        ProvideKidTier(tier) { WidthClassed(window) { content(it, tier) } }
                    }
                }
            },
        )
    }

    // roborazziSystemPropertyOutputDirectory() is the plugin's outputDir (src/test/screenshots), passed to the tests.
    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureVariants(variants: List<Variant>, screen: Boolean = false) {
        requireUnique(variants)
        val outputDirectory = roborazziSystemPropertyOutputDirectory()
        var current by mutableStateOf(variants.first())
        compose.setContent {
            val variant = current
            key(variant.name) { Themed(variant) }
        }
        variants.forEach { variant ->
            compose.runOnIdle { current = variant }
            compose.waitForIdle()
            captureAndCheck(variant, outputDirectory, screen)
        }
    }

    /**
     * One fresh activity per variant, launched after switching Robolectric to the window's qualifiers (as
     * Roborazzi's own compose capture does), so the window really has that size. The rule's own activity stays
     * empty, so [compose] sees exactly one root. The test's qualifiers are restored afterwards.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureInWindows(variants: List<Pair<ScreenshotWindow, Variant>>) {
        requireUnique(variants.map { it.second })
        val outputDirectory = roborazziSystemPropertyOutputDirectory()
        val testQualifiers = RuntimeEnvironment.getQualifiers().replace(VERSION_QUALIFIER, "")
        try {
            variants.forEach { (window, variant) ->
                RuntimeEnvironment.setQualifiers(window.qualifiers ?: testQualifiers)
                ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        // As ComponentActivity.setContent does: wrap-content, so the image is the content's size.
                        val view = ComposeView(activity).apply { setContent { Themed(variant) } }
                        activity.setContentView(view, ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
                    }
                    compose.waitForIdle()
                    captureAndCheck(variant, outputDirectory)
                }
            }
        } finally {
            RuntimeEnvironment.setQualifiers(testQualifiers)
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureAndCheck(variant: Variant, outputDirectory: String, screen: Boolean = false) {
        val filePath = "$outputDirectory/${variant.name}.png"
        if (screen) {
            captureScreenRoboImage(filePath = filePath, roborazziOptions = options)
        } else {
            compose.onRoot().captureRoboImage(filePath = filePath, roborazziOptions = options)
        }
        if (checkAccessibility) {
            try {
                compose.assertAccessible(variant.minTouchTarget)
            } catch (error: AssertionError) {
                throw AssertionError("${variant.name}: ${error.message}", error)
            }
        }
    }

    private fun requireUnique(variants: List<Variant>) {
        require(variants.isNotEmpty()) { "Nothing to capture" }
        require(variants.map { it.name }.toSet().size == variants.size) { "Duplicate screenshot names" }
    }

    private class Variant(
        val name: String,
        val darkTheme: Boolean,
        val minTouchTarget: Dp = TierTokens.Sapling.minTouchTarget,
        val content: @Composable () -> Unit,
    )

    @Composable
    private fun Themed(variant: Variant) {
        SproutTheme(darkTheme = variant.darkTheme) {
            Surface(color = MaterialTheme.colorScheme.background) { variant.content() }
        }
    }

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

        /** The font scale of the large-text goldens (M7 §8): Android's largest preset on most devices. */
        const val LARGE_FONT_SCALE = 2f

        private const val MAX_DISTANCE = 0.007f
        private const val SHIFT_PX = 1
        private const val CHANGE_THRESHOLD = 0.001f
        private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
        private val VERSION_QUALIFIER = Regex("-v\\d+$")
    }
}

/** The windows [ScreenshotRule.captureWidthClasses] renders in. */
enum class ScreenshotWindow(
    /** Robolectric qualifiers of the window; null keeps the test's own device. */
    val qualifiers: String?,
    val widthClass: WindowWidthClass,
    /** File-name suffix. */
    val suffix: String,
) {
    /** The test's device: [ScreenshotDevice.PIXEL_7] unless the test overrides it. */
    PHONE(qualifiers = null, widthClass = WindowWidthClass.Compact, suffix = "phone"),

    /** [ScreenshotDevice.TABLET]: 1280 x 800 dp, Expanded. */
    TABLET(qualifiers = ScreenshotDevice.TABLET, widthClass = WindowWidthClass.Expanded, suffix = "tablet"),
}

@Composable
private fun FontScaled(fontScale: Float, content: @Composable () -> Unit) {
    DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale), content)
}

@Composable
private fun WidthClassed(window: ScreenshotWindow, content: @Composable (WindowWidthClass) -> Unit) {
    CompositionLocalProvider(LocalWindowWidthClass provides window.widthClass) {
        content(window.widthClass)
    }
}

private val KidUiTier.suffix: String get() = name.lowercase()

private val KidUiTier.minTouchTarget: Dp get() = TierTokens.forTier(this).minTouchTarget

private val Float.suffix: String get() = "font${(this * PERCENT).roundToInt()}"

private const val PERCENT = 100

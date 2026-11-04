package dev.kpetriv.sprout.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyOutputDirectory
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.testing.accessibility.assertAccessible
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotRule

/**
 * Like [ScreenshotRule.captureLightDark], but captures the whole screen, so a dialog or a date picker (its own
 * window) is in the golden with the screen and scrim behind it. [open] runs after each variant is composed (to open
 * a dialog that local state controls); every variant is then checked with the accessibility rule, dialogs included.
 *
 * Kept here rather than in core/testing: only settings needs it so far (`ScreenshotRule` captures the content root).
 */
@OptIn(ExperimentalRoborazziApi::class)
fun ScreenshotRule.captureScreenLightDark(
    name: String,
    open: ComposeContentTestRule.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val outputDirectory = roborazziSystemPropertyOutputDirectory()
    var dark by mutableStateOf(false)
    compose.setContent {
        key(dark) {
            SproutTheme(darkTheme = dark) {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }
    }
    listOf(false, true).forEach { variant ->
        compose.runOnIdle { dark = variant }
        compose.waitForIdle()
        compose.open()
        compose.waitForIdle()
        val file = "${name}_${if (variant) "dark" else "light"}"
        captureScreenRoboImage(
            filePath = "$outputDirectory/$file.png",
            roborazziOptions = ScreenshotRule.DEFAULT_OPTIONS,
        )
        try {
            compose.assertAccessible()
        } catch (error: AssertionError) {
            throw AssertionError("$file: ${error.message}", error)
        }
    }
}

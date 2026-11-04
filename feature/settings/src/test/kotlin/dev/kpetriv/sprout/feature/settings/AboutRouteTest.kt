package dev.kpetriv.sprout.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.testing.accessibility.AccessibilityRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * About's inner navigation (review L10): the licences page opens from About and loads the list only then, system back
 * returns to About instead of leaving it, and the open page survives a saved-state restore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2400dp")
class AboutRouteTest {
    @get:Rule(order = 0)
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule(order = 1)
    val a11y = AccessibilityRule(compose)

    private val libraries = SettingsPreviewData.about.libraries.orEmpty()
    private var loads = 0
    private var backs = 0

    private fun viewModel() = AboutViewModel(
        AppBuildInfo("1.2.0", 345, "https://sprout.example/privacy", "https://sprout.example/security"),
        FakeUrlOpener(),
        OpenSourceLicences {
            loads++
            libraries
        },
    )

    @Test
    fun `the licences page opens, loads the list once, and system back returns to About`() {
        val vm = viewModel()
        compose.setContent { SproutTheme { AboutRoute(onBack = { backs++ }, viewModel = vm) } }

        compose.onNodeWithText("Version").assertIsDisplayed()
        compose.waitForIdle()
        assertEquals(0, loads)

        compose.onNodeWithText("Open-source licences").performClick()
        compose.onNodeWithText(libraries.first().name).assertIsDisplayed()
        assertEquals(1, loads)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Version").assertIsDisplayed()
        assertEquals(0, backs)

        compose.onNodeWithText("Open-source licences").performClick()
        compose.onNodeWithText(libraries.first().name).assertIsDisplayed()
        assertEquals(1, loads)
    }

    @Test
    fun `the open licences page survives a saved-state restore and a new view model loads its list`() {
        var current = viewModel()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { SproutTheme { AboutRoute(onBack = { backs++ }, viewModel = current) } }

        compose.onNodeWithText("Open-source licences").performClick()
        compose.onNodeWithText(libraries.first().name).assertIsDisplayed()
        assertEquals(1, loads)

        // As after process death: the saved page comes back with a view model that has loaded nothing yet.
        current = viewModel()
        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText(libraries.first().name).assertIsDisplayed()
        assertEquals(2, loads)
        assertEquals(0, backs)
    }
}

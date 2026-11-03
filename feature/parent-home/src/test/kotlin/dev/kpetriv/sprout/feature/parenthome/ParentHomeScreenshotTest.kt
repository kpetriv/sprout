package dev.kpetriv.sprout.feature.parenthome

import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotTest
import org.junit.Test
import org.robolectric.annotation.Config

/** Goldens in src/test/screenshots; see docs/SCREENSHOT_TESTS.md. */
class ParentHomeScreenshotTest : ScreenshotTest() {
    @Test
    fun `empty household`() = screenshot.captureLightDark("parent_home_empty") {
        ParentHomeScreen(ParentHomePreviewData.empty(), onEvent = {}, onSignNow = {}, links = ParentHomeLinks())
    }

    @Test
    @Config(qualifiers = "+h1700dp") // Every card of a busy dashboard in one image.
    fun `busy household`() = screenshot.captureLightDark("parent_home_busy") {
        ParentHomeScreen(ParentHomePreviewData.busy(), onEvent = {}, onSignNow = {}, links = ParentHomeLinks())
    }

    @Test
    fun `busy household, signing in progress`() = screenshot.captureLightDark("parent_home_signing") {
        ParentHomeScreen(
            ParentHomePreviewData.busy(signing = SigningProgress(2, 3)),
            onEvent = {},
            onSignNow = {},
            links = ParentHomeLinks(),
        )
    }

    @Test
    @Config(qualifiers = "+h1200dp") // The attention card and both kids in one image.
    fun `payments that need attention`() = screenshot.captureLightDark("parent_home_attention") {
        ParentHomeScreen(
            ParentHomePreviewData.needsAttention(),
            onEvent = {},
            onSignNow = {},
            links = ParentHomeLinks(),
        )
    }
}

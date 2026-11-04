package dev.kpetriv.sprout.feature.parenthome

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotTest
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotWindow
import org.junit.Test
import org.robolectric.annotation.Config

/** Goldens in src/test/screenshots; see docs/SCREENSHOT_TESTS.md. */
class ParentHomeScreenshotTest : ScreenshotTest(checkAccessibility = true) {
    @Test
    fun `empty household`() = screenshot.captureLightDark("parent_home_empty") {
        ParentHomeScreen(ParentHomePreviewData.empty(), onEvent = {}, onSignNow = {}, links = ParentHomeLinks())
    }

    @Test
    fun `weekly summary card`() = screenshot.captureLightDark("parent_home_summary") {
        WeeklySummaryCard(
            summary = ParentHomePreviewData.summary(),
            week = SummaryWeek.THIS_WEEK,
            onWeekChange = {},
            onDownloadModel = {},
            modifier = Modifier.padding(16.dp),
        )
    }

    @Test
    @Config(qualifiers = "+h1700dp") // Every card of a busy dashboard in one image.
    fun `busy household`() = screenshot.captureLightDark("parent_home_busy") {
        ParentHomeScreen(ParentHomePreviewData.busy(), onEvent = {}, onSignNow = {}, links = ParentHomeLinks())
    }

    // Two dashboard columns on a wider window (D-M7-2).
    @Test
    fun `busy household on a tablet`() = screenshot.captureWidthClasses(
        name = "parent_home_busy",
        windows = listOf(ScreenshotWindow.TABLET),
    ) {
        ParentHomeScreen(ParentHomePreviewData.busy(), onEvent = {}, onSignNow = {}, links = ParentHomeLinks())
    }

    // The first screenful at twice the text size (the whole dashboard would pass the 300 KB golden budget).
    @Test
    fun `busy household at font scale 2`() = screenshot.captureFontScale("parent_home_busy") {
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

    @Test
    fun `trial household banner`() = screenshot.captureLightDark("parent_home_trial") {
        ParentHomeScreen(ParentHomePreviewData.trial(), onEvent = {}, onSignNow = {}, links = ParentHomeLinks())
    }

    @Test
    @Config(qualifiers = "+h1200dp")
    fun `new device waiting and rejected events`() = screenshot.captureLightDark("parent_home_household") {
        ParentHomeScreen(ParentHomePreviewData.household(), onEvent = {}, onSignNow = {}, links = ParentHomeLinks())
    }

    @Test
    fun `signing key not registered with the household`() =
        screenshot.captureLightDark("parent_home_signing_key_not_registered") {
            ParentHomeScreen(
                ParentHomePreviewData.signingKeyNotRegistered(),
                onEvent = {},
                onSignNow = {},
                links = ParentHomeLinks(),
            )
        }

    @Test
    fun `fingerprint signing off`() = screenshot.captureLightDark("parent_home_signing_off") {
        ParentHomeScreen(ParentHomePreviewData.signingOff(), onEvent = {}, onSignNow = {}, links = ParentHomeLinks())
    }

    @Test
    fun `sync blocked`() = screenshot.captureLightDark("parent_home_sync_blocked") {
        ParentHomeScreen(ParentHomePreviewData.syncBlocked(), onEvent = {}, onSignNow = {}, links = ParentHomeLinks())
    }
}

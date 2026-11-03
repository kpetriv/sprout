package dev.kpetriv.sprout.feature.timebank

import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotTest
import org.junit.Test

/**
 * Golden screenshots of the time bank, in src/test/screenshots: every tier idle, running, with an empty balance, with
 * the time up while still running, loading and failed to load.
 * Re-record with `./gradlew :feature:timebank:recordRoborazziDebug`.
 */
class TimeBankScreenshotTest : ScreenshotTest() {
    @Test
    fun `idle, every tier`() = screenshot.captureTiers("timebank_idle") { tier ->
        TimeBankScreen(state = TimeBankPreviewData.idle(tier), onEvent = {})
    }

    @Test
    fun `running, every tier`() = screenshot.captureTiers("timebank_running") { tier ->
        TimeBankScreen(state = TimeBankPreviewData.running(tier), onEvent = {})
    }

    @Test
    fun `empty balance, every tier`() = screenshot.captureTiers("timebank_empty") { tier ->
        TimeBankScreen(state = TimeBankPreviewData.empty(tier), onEvent = {})
    }

    @Test
    fun `time up while running, every tier`() = screenshot.captureTiers("timebank_time_up") { tier ->
        TimeBankScreen(state = TimeBankPreviewData.timeUp(tier), onEvent = {})
    }

    @Test
    fun `loading, every tier`() = screenshot.captureTiers("timebank_loading") {
        TimeBankScreen(state = TimeBankUiState.Loading, onEvent = {})
    }

    @Test
    fun `error, every tier`() = screenshot.captureTiers("timebank_error") {
        TimeBankScreen(state = TimeBankUiState.Error, onEvent = {})
    }
}

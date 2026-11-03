package dev.kpetriv.sprout.feature.timebank

import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotTest
import org.junit.Test

/**
 * Golden screenshots of the time bank, in src/test/screenshots: every tier idle, running and with an empty balance.
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
}

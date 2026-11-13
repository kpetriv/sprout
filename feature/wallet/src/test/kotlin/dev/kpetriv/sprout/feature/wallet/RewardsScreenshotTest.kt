package dev.kpetriv.sprout.feature.wallet

import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotTest
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Golden screenshots of the parent's rewards list and editor, light and dark, in src/test/screenshots. The parent
 * shop preview with "Edit rewards" is `shop_parent_*` in [WalletScreenshotTest]. Re-record with
 * `./gradlew :feature:wallet:recordRoborazziDebug`.
 */
class RewardsScreenshotTest : ScreenshotTest(checkAccessibility = true) {
    @Test
    fun `rewards list, empty`() = screenshot.captureLightDark("rewards_list_empty") {
        RewardListScreen(state = RewardListUiState.Ready(emptyList()), onEvent = {}, onBack = {})
    }

    @Test
    @Config(qualifiers = TALL)
    fun `rewards list, with a hidden and a per-kid reward`() = screenshot.captureLightDark("rewards_list") {
        RewardListScreen(state = RewardsPreviewData.list(), onEvent = {}, onBack = {})
    }

    @Test
    @Config(qualifiers = TALL)
    fun `reward editor, new`() = screenshot.captureLightDark("reward_editor_new") {
        RewardEditorScreen(state = RewardsPreviewData.editorNew(), onEvent = {})
    }

    @Test
    @Config(qualifiers = TALL)
    fun `reward editor, editing screen time`() = screenshot.captureLightDark("reward_editor_screen_time") {
        RewardEditorScreen(state = RewardsPreviewData.editorScreenTime(), onEvent = {})
    }

    @Test
    @Config(qualifiers = TALL)
    fun `reward editor, with errors`() = screenshot.captureLightDark("reward_editor_errors") {
        RewardEditorScreen(state = RewardsPreviewData.editorErrors(), onEvent = {})
    }

    private companion object {
        const val TALL = "+h1200dp"
    }
}

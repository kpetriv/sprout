package dev.kpetriv.sprout.core.designsystem.components

import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotTest
import org.junit.Test

/** Per-tier goldens of every Morning garden component (M9 §3), with the accessibility rule on each variant. */
class ComponentsScreenshotTest : ScreenshotTest(checkAccessibility = true) {
    @Test
    fun card() = screenshot.captureTiers("component_card") { SproutCardSample() }

    @Test
    fun button() = screenshot.captureTiers("component_button") { SproutButtonSample() }

    @Test
    fun statTile() = screenshot.captureTiers("component_stat_tile") { StatTileSample() }

    @Test
    fun ledgerRow() = screenshot.captureTiers("component_ledger_row") { LedgerRowSample() }

    @Test
    fun goalBar() = screenshot.captureTiers("component_goal_bar") { GoalBarSample() }

    @Test
    fun chip() = screenshot.captureTiers("component_chip") { SproutChipSample() }

    @Test
    fun avatar() = screenshot.captureTiers("component_avatar") { InitialAvatarSample() }

    @Test
    fun topBar() = screenshot.captureTiers("component_top_bar") { SproutTopBarSample() }

    @Test
    fun navigationBar() = screenshot.captureTiers("component_navigation_bar") { tier ->
        SproutNavigationBarSample(tier)
    }

    @Test
    fun navigationRail() = screenshot.captureTiers("component_navigation_rail") { tier ->
        SproutNavigationRailSample(tier)
    }
}

/** Light and dark goldens of the parent-facing components and the pictogram set. */
class ParentComponentsScreenshotTest : ScreenshotTest(checkAccessibility = true) {
    @Test
    fun topBar() = screenshot.captureLightDark("component_top_bar_parent") { SproutTopBarSample() }

    @Test
    fun navigationBar() = screenshot.captureLightDark("component_navigation_bar_parent") {
        SproutNavigationBarSample(tier = null)
    }

    @Test
    fun navigationRail() = screenshot.captureLightDark("component_navigation_rail_parent") {
        SproutNavigationRailSample(tier = null)
    }

    @Test
    fun ledgerRow() = screenshot.captureLightDark("component_ledger_row_parent") { LedgerRowSample() }

    @Test
    fun statTile() = screenshot.captureLightDark("component_stat_tile_parent") { StatTileSample() }

    @Test
    fun card() = screenshot.captureLightDark("component_card_parent") { SproutCardSample() }

    @Test
    fun button() = screenshot.captureLightDark("component_button_parent") { SproutButtonSample() }

    @Test
    fun chip() = screenshot.captureLightDark("component_chip_parent") { SproutChipSample() }

    @Test
    fun goalBar() = screenshot.captureLightDark("component_goal_bar_parent") { GoalBarSample() }

    @Test
    fun avatar() = screenshot.captureLightDark("component_avatar_parent") { InitialAvatarSample() }

    @Test
    fun pictograms() = screenshot.captureLightDark("component_pictograms") { PictogramSample() }
}

/** Font scale 2.0 goldens (M7 §8) of the components that must grow rather than clip. */
class LargeFontComponentsScreenshotTest : ScreenshotTest(checkAccessibility = true) {
    @Test
    fun navigationBarTiers() = screenshot.captureFontScaleTiers("component_navigation_bar") { tier ->
        SproutNavigationBarSample(tier)
    }

    @Test
    fun navigationBarParent() = screenshot.captureFontScale("component_navigation_bar_parent") {
        SproutNavigationBarSample(tier = null)
    }

    @Test
    fun topBarTiers() = screenshot.captureFontScaleTiers("component_top_bar") { SproutTopBarSample() }

    @Test
    fun topBarParent() = screenshot.captureFontScale("component_top_bar_parent") { SproutTopBarSample() }

    @Test
    fun avatarTiers() = screenshot.captureFontScaleTiers("component_avatar") { InitialAvatarSample() }
}

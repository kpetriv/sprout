package dev.kpetriv.sprout.navigation

import android.app.Application
import androidx.compose.runtime.Composable
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotTest
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotWindow
import dev.kpetriv.sprout.feature.chat.ChatPreviewData
import dev.kpetriv.sprout.feature.chat.ChatThreadListScreen
import dev.kpetriv.sprout.feature.chores.ChoreListScreen
import dev.kpetriv.sprout.feature.chores.ChoresPreviewData
import dev.kpetriv.sprout.feature.parenthome.ParentHomeLinks
import dev.kpetriv.sprout.feature.parenthome.ParentHomePreviewData
import dev.kpetriv.sprout.feature.parenthome.ParentHomeScreen
import dev.kpetriv.sprout.feature.settings.SettingsScreen
import dev.kpetriv.sprout.feature.wallet.WalletPreviewData
import dev.kpetriv.sprout.feature.wallet.WalletScreen
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Goldens of the app shell (M9 §5.3): the parent shell on each tab (light and dark), the kid shell per tier on its
 * wallet (Seedling with three items and its giant shortcut buttons), and the rail on a tablet. The screens inside are
 * the features' real stateless screens with their preview data; these are the images the Play listing shows (slice F).
 * Every variant also passes the accessibility rule.
 */
@Config(application = Application::class) // Not the Hilt app: its startup opens the encrypted database.
class ShellScreenshotTest : ScreenshotTest(checkAccessibility = true) {
    @Test
    fun `parent shell, home`() = screenshot.captureLightDark("app_shell_parent_home") {
        ParentShell(selected = 0) { ParentHome() }
    }

    @Test
    fun `parent shell, chores`() = screenshot.captureLightDark("app_shell_parent_chores") {
        ParentShell(selected = 1) { ChoreListScreen(state = ChoresPreviewData.choreList(), onEvent = {}) }
    }

    @Test
    fun `parent shell, chat`() = screenshot.captureLightDark("app_shell_parent_chat") {
        ParentShell(selected = 2) {
            ChatThreadListScreen(ChatPreviewData.threads(), onEvent = {}, onOpenThread = {})
        }
    }

    @Test
    fun `parent shell, settings`() = screenshot.captureLightDark("app_shell_parent_settings") {
        ParentShell(selected = 3) { SettingsScreen(onOpen = {}, onBack = null) }
    }

    @Test
    fun `parent shell on a tablet`() = screenshot.captureWidthClasses(
        "app_shell_parent_home",
        windows = listOf(ScreenshotWindow.TABLET),
    ) {
        ParentShell(selected = 0) { ParentHome() }
    }

    @Test
    fun `kid shell, every tier`() = screenshot.captureTiers("app_shell_kid") { tier -> KidShellOnWallet(tier) }

    @Test
    fun `kid shell on a tablet, every tier`() = screenshot.captureWidthClassTiers(
        "app_shell_kid",
        windows = listOf(ScreenshotWindow.TABLET),
    ) { _, tier ->
        KidShellOnWallet(tier)
    }
}

@Composable
private fun ParentShell(selected: Int, content: @Composable () -> Unit) {
    SproutShell(tabs = parentTabs(chatAvailable = true), selectedIndex = selected, onSelect = {}, content = content)
}

@Composable
private fun ParentHome() {
    ParentHomeScreen(ParentHomePreviewData.busy(), onEvent = {}, onSignNow = {}, links = ParentHomeLinks())
}

@Composable
private fun KidShellOnWallet(tier: KidUiTier) {
    val kid = KidId("kid-${tier.name.lowercase()}")
    SproutShell(
        tabs = kidTabs(kid, tier, chatAvailable = true),
        selectedIndex = 0,
        onSelect = {},
        tier = tier,
    ) {
        WithShortcuts(kidShortcuts(tier, chat = true, onChat = {}, onScreenTime = {}, onGames = {})) {
            WalletScreen(state = WalletPreviewData.ready(tier, canAdjust = false), onEvent = {}, onOpenTimeBank = {})
        }
    }
}

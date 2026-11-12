package dev.kpetriv.sprout.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldLayout
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.navigation3.runtime.NavKey
import dev.kpetriv.sprout.R
import dev.kpetriv.sprout.core.designsystem.components.Pictogram
import dev.kpetriv.sprout.core.designsystem.components.SproutNavItem
import dev.kpetriv.sprout.core.designsystem.components.SproutNavigationBar
import dev.kpetriv.sprout.core.designsystem.components.SproutNavigationRail
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.ProvideKidTier
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.LocalShellChrome
import dev.kpetriv.sprout.core.ui.ShellChrome
import dev.kpetriv.sprout.core.ui.WindowWidthClass
import dev.kpetriv.sprout.core.ui.rememberWindowWidthClass
import dev.kpetriv.sprout.feature.wallet.WalletMode

/** What a bottom-bar item shows: its label and its outlined and filled (selected) icons (M9 §5.1). */
internal enum class ShellDestination(@param:StringRes val label: Int) {
    HOME(R.string.shell_tab_home),
    CHORES(R.string.shell_tab_chores),
    CHAT(R.string.shell_tab_chat),
    SETTINGS(R.string.shell_tab_settings),
    WALLET(R.string.shell_tab_wallet),
    SHOP(R.string.shell_tab_shop),
    GAMES(R.string.shell_tab_games),
    ;

    /**
     * The item for this destination in the current composition (labels are resources, icons vector resources). On
     * Seedling's bar the three items show pictograms in their own colours instead (M9 §5.1).
     */
    @Composable
    fun navItem(tier: KidUiTier? = null): SproutNavItem {
        val pictogram = if (tier == KidUiTier.SEEDLING) seedlingPictogram else null
        return if (pictogram != null) {
            SproutNavItem(stringResource(label), pictogram.imageVector, pictogram.imageVector, tinted = false)
        } else {
            symbolItem()
        }
    }

    private val seedlingPictogram: Pictogram?
        get() = when (this) {
            WALLET -> Pictogram.COIN
            CHORES -> Pictogram.BROOM
            SHOP -> Pictogram.SHOPPING_BAG
            else -> null
        }

    @Composable
    private fun symbolItem(): SproutNavItem = when (this) {
        HOME -> SproutNavItem(stringResource(label), SproutIcons.Home, SproutIcons.HomeFilled)

        CHORES -> SproutNavItem(stringResource(label), SproutIcons.Checklist, SproutIcons.ChecklistFilled)

        CHAT -> SproutNavItem(stringResource(label), SproutIcons.ChatBubble, SproutIcons.ChatBubbleFilled)

        SETTINGS -> SproutNavItem(stringResource(label), SproutIcons.Settings, SproutIcons.SettingsFilled)

        WALLET -> SproutNavItem(
            stringResource(label),
            SproutIcons.AccountBalanceWallet,
            SproutIcons.AccountBalanceWalletFilled,
        )

        SHOP -> SproutNavItem(stringResource(label), SproutIcons.Storefront, SproutIcons.StorefrontFilled)

        GAMES -> SproutNavItem(stringResource(label), SproutIcons.SportsEsports, SproutIcons.SportsEsportsFilled)
    }
}

/** One bottom-bar destination: the root key of its back stack and what its item shows. */
@Immutable
internal data class ShellTab(val root: NavKey, val destination: ShellDestination)

/** The parent shell's destinations (M9 §5.1, D-M9-10): Home, Chores, Chat (real households only), Settings. */
internal fun parentTabs(chatAvailable: Boolean): List<ShellTab> = buildList {
    add(ShellTab(ParentHomeKey, ShellDestination.HOME))
    add(ShellTab(ChoresKey, ShellDestination.CHORES))
    if (chatAvailable) add(ShellTab(ChatThreadsKey, ShellDestination.CHAT))
    add(ShellTab(SettingsKey, ShellDestination.SETTINGS))
}

/**
 * The kid shell's destinations for [kidId] in [tier] (M9 §5.1, D-M9-10): Wallet, Chores, Shop, Games and Chat (real
 * households only) on Sprout and Sapling; Wallet, Chores and Shop on Seedling, whose wallet keeps giant Chat and Games
 * buttons instead.
 */
internal fun kidTabs(kidId: KidId, tier: KidUiTier, chatAvailable: Boolean): List<ShellTab> = buildList {
    add(ShellTab(KidHomeKey(kidId), ShellDestination.WALLET))
    add(ShellTab(KidChoresKey(kidId, tier), ShellDestination.CHORES))
    add(ShellTab(ShopKey(kidId, WalletMode.KID), ShellDestination.SHOP))
    if (tier != KidUiTier.SEEDLING) {
        add(ShellTab(KidGamesKey(kidId, tier), ShellDestination.GAMES))
        if (chatAvailable) add(ShellTab(ChatThreadKey(kidId), ShellDestination.CHAT))
    }
}

/**
 * Counts the open [ShellChrome.setNavigationHidden] requests of the screens inside a [SproutShell]; the bar is hidden
 * while any is open.
 */
@Stable
internal class NavigationHideRequests : ShellChrome {
    private var open by mutableIntStateOf(0)

    val hidden: Boolean get() = open > 0

    override fun setNavigationHidden(hidden: Boolean) {
        open = if (hidden) open + 1 else (open - 1).coerceAtLeast(0)
    }
}

/**
 * The app shell's chrome around [content] (M9 §5): `SproutNavigationBar` under it on Compact and Medium windows,
 * `SproutNavigationRail` beside it on Expanded ones (D-M9-12), laid out by Material's `NavigationSuiteScaffoldLayout`.
 * [tier] sizes the bar for a kid (80 dp items on Seedling); null for the parent.
 *
 * The bar is hidden when [navigationHidden] (the caller's rule: a game in play) or while a screen inside asks through
 * `HideNavigationWhile` (the time bank's running timer); [content] stays composed either way. The bar is a traversal
 * group read after the content. While the bar shows, [content] does not pad for the system navigation bar again.
 */
@Composable
internal fun SproutShell(
    tabs: List<ShellTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tier: KidUiTier? = null,
    navigationHidden: Boolean = false,
    content: @Composable () -> Unit,
) {
    val requests = remember { NavigationHideRequests() }
    val hidden = navigationHidden || requests.hidden
    val rail = rememberWindowWidthClass() == WindowWidthClass.Expanded
    val items = tabs.map { it.destination.navItem(tier) }
    val type = when {
        hidden -> NavigationSuiteType.None
        rail -> NavigationSuiteType.NavigationRail
        else -> NavigationSuiteType.NavigationBar
    }
    // The bar (or rail) pads for the system bars on its side; the content must not pad for them again.
    val consumed = when (type) {
        NavigationSuiteType.NavigationRail -> NavigationRailDefaults.windowInsets.only(WindowInsetsSides.Start)
        NavigationSuiteType.NavigationBar -> NavigationBarDefaults.windowInsets.only(WindowInsetsSides.Bottom)
        else -> null
    }
    val contentInsets = consumed?.let { Modifier.consumeWindowInsets(it) } ?: Modifier
    Box(modifier) {
        NavigationSuiteScaffoldLayout(
            navigationSuite = {
                if (!hidden) {
                    ShellNavigation(items, selectedIndex, onSelect, rail, tier)
                }
            },
            navigationSuiteType = type,
        ) {
            Box(Modifier.fillMaxSize().then(contentInsets)) {
                CompositionLocalProvider(LocalShellChrome provides requests, content = content)
            }
        }
    }
}

@Composable
private fun ShellNavigation(
    items: List<SproutNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    rail: Boolean,
    tier: KidUiTier?,
) {
    // After the content for TalkBack, wherever the bar or rail sits (M9 §5.2).
    val order = Modifier.semantics { traversalIndex = 1f }
    val navigation: @Composable () -> Unit = {
        if (rail) {
            SproutNavigationRail(items, selectedIndex, onSelect, modifier = order)
        } else {
            SproutNavigationBar(items, selectedIndex, onSelect, modifier = order)
        }
    }
    if (tier == null) navigation() else ProvideKidTier(tier, navigation)
}

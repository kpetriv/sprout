package dev.kpetriv.sprout.core.designsystem.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.SproutPreviewFrame
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier

/**
 * One destination of [SproutNavigationBar] or [SproutNavigationRail]: plain data, no navigation dependency (the app
 * shell maps items to its keys). [icon] is the outlined symbol, [selectedIcon] the filled one shown while selected.
 * [label] is always visible and is what TalkBack reads (with the tab role and selected state), so the icons carry no
 * description of their own. [tinted] false draws the icons in their own colours (a [Pictogram]'s, on Seedling's bar,
 * M9 §5.1) instead of the item's content colour; icons are drawn 24 dp either way.
 */
@Immutable
data class SproutNavItem(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val tinted: Boolean = true,
)

/** Defaults for [SproutNavigationBar] and [SproutNavigationRail]. */
object SproutNavigationDefaults {
    /** Bar height on every tier but Seedling. */
    val ItemHeight: Dp = 64.dp

    /** Seedling's taller bar, for its 72 dp touch targets and 1.3x labels. */
    val LargeItemHeight: Dp = 80.dp

    /** The bar's height for the current tier: [LargeItemHeight] when the tier's touch target leaves no padding. */
    val itemHeight: Dp
        @Composable
        @ReadOnlyComposable
        get() {
            val touch = SproutTheme.tierTokens.minTouchTarget
            return if (touch > ItemHeight - ItemPadding) LargeItemHeight else ItemHeight
        }

    /**
     * The bar's actual height: [itemHeight], or taller when the label's line (after the tier's type scale and the
     * user's font scale) needs more room than that leaves next to the icon pill.
     */
    val barHeight: Dp
        @Composable
        @ReadOnlyComposable
        get() {
            val label = with(LocalDensity.current) { MaterialTheme.typography.labelMedium.lineHeight.toDp() }
            return maxOf(itemHeight, IconPillAndGap + label + ItemPadding * 2)
        }

    private val ItemPadding: Dp = 8.dp

    /** Material's 32 dp indicator pill plus its 4 dp gap to the label. */
    private val IconPillAndGap: Dp = 36.dp
}

/**
 * The bottom navigation bar of M9 §5: white with a hairline on top, one Material 3 `NavigationBarItem` per item with
 * its label always shown, outlined icons that turn filled when selected, and the selected item on a Leaf-tint pill.
 * 64 dp tall, 80 dp on Seedling (whose labels are 1.3x through the tier's type scale), and taller when a large font
 * scale needs it ([SproutNavigationDefaults.barHeight]). Each item is announced as a tab with its selected state,
 * and the bar is one traversal group.
 *
 * It lays the items out in its own row rather than in Material's `NavigationBar`, whose fixed 80 dp minimum height
 * would override the 64 dp of the design. The row gets an exact height because each `NavigationBarItem` has an
 * 80 dp default minimum of its own that a `heightIn(min = ...)` row would let through.
 */
@Composable
fun SproutNavigationBar(
    items: List<SproutNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = NavigationBarDefaults.windowInsets,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = scheme.onPrimaryContainer,
        selectedTextColor = scheme.onPrimaryContainer,
        indicatorColor = scheme.primaryContainer,
        unselectedIconColor = scheme.onSurfaceVariant,
        unselectedTextColor = scheme.onSurfaceVariant,
    )
    Surface(modifier = modifier.semantics { isTraversalGroup = true }, color = scheme.surfaceContainerLowest) {
        Column {
            HorizontalDivider(thickness = 1.dp, color = scheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(windowInsets)
                    .height(SproutNavigationDefaults.barHeight)
                    .selectableGroup(),
            ) {
                items.forEachIndexed { index, item ->
                    val selected = index == selectedIndex
                    NavigationBarItem(
                        selected = selected,
                        onClick = { onSelect(index) },
                        icon = { NavIcon(item, selected) },
                        label = { NavLabel(item) },
                        alwaysShowLabel = true,
                        colors = colors,
                    )
                }
            }
        }
    }
}

/**
 * [SproutNavigationBar]'s twin for Expanded windows (M9 §5.2, D-M9-12): a white Material 3 `NavigationRail` with a
 * hairline on its end edge and the same items, icons, labels, colours and semantics. Items are at least the tier's
 * minimum touch target tall.
 */
@Composable
fun SproutNavigationRail(
    items: List<SproutNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = NavigationRailDefaults.windowInsets,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = NavigationRailItemDefaults.colors(
        selectedIconColor = scheme.onPrimaryContainer,
        selectedTextColor = scheme.onPrimaryContainer,
        indicatorColor = scheme.primaryContainer,
        unselectedIconColor = scheme.onSurfaceVariant,
        unselectedTextColor = scheme.onSurfaceVariant,
    )
    val minHeight = maxOf(SproutTheme.tierTokens.minTouchTarget, SproutNavigationDefaults.ItemHeight)
    Row(modifier = modifier.semantics { isTraversalGroup = true }) {
        NavigationRail(
            modifier = Modifier.fillMaxHeight(),
            containerColor = scheme.surfaceContainerLowest,
            windowInsets = windowInsets,
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                NavigationRailItem(
                    selected = selected,
                    onClick = { onSelect(index) },
                    icon = { NavIcon(item, selected) },
                    label = { NavLabel(item) },
                    alwaysShowLabel = true,
                    colors = colors,
                    modifier = Modifier.heightIn(min = minHeight),
                )
            }
        }
        VerticalDivider(thickness = 1.dp, color = scheme.outlineVariant)
    }
}

@Composable
private fun NavIcon(item: SproutNavItem, selected: Boolean) {
    Icon(
        imageVector = if (selected) item.selectedIcon else item.icon,
        contentDescription = null,
        modifier = Modifier.size(NavIconSize),
        tint = if (item.tinted) LocalContentColor.current else Color.Unspecified,
    )
}

@Composable
private fun NavLabel(item: SproutNavItem) {
    Text(
        text = item.label,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The parent shell's four destinations (M9 §5.1), for previews and goldens. */
@Composable
internal fun parentNavItems(): List<SproutNavItem> = listOf(
    SproutNavItem("Home", SproutIcons.Home, SproutIcons.HomeFilled),
    SproutNavItem("Chores", SproutIcons.Checklist, SproutIcons.ChecklistFilled),
    SproutNavItem("Chat", SproutIcons.ChatBubble, SproutIcons.ChatBubbleFilled),
    SproutNavItem("Settings", SproutIcons.Settings, SproutIcons.SettingsFilled),
)

/** The kid shell's destinations for [tier] (M9 §5.1): three on Seedling, five otherwise. */
@Composable
internal fun kidNavItems(tier: KidUiTier): List<SproutNavItem> {
    val all = listOf(
        SproutNavItem("Wallet", SproutIcons.AccountBalanceWallet, SproutIcons.AccountBalanceWalletFilled),
        SproutNavItem("Chores", SproutIcons.Checklist, SproutIcons.ChecklistFilled),
        SproutNavItem("Shop", SproutIcons.Storefront, SproutIcons.StorefrontFilled),
        SproutNavItem("Games", SproutIcons.SportsEsports, SproutIcons.SportsEsportsFilled),
        SproutNavItem("Chat", SproutIcons.ChatBubble, SproutIcons.ChatBubbleFilled),
    )
    return if (tier == KidUiTier.SEEDLING) all.take(SEEDLING_ITEMS) else all
}

private const val SEEDLING_ITEMS = 3

/** The bar for [tier]'s kid shell, or the parent shell's when [tier] is null, first item selected. */
@Composable
internal fun SproutNavigationBarSample(tier: KidUiTier?) {
    val items = if (tier == null) parentNavItems() else kidNavItems(tier)
    SproutNavigationBar(items = items, selectedIndex = 0, onSelect = {}, windowInsets = WindowInsets(0))
}

/** The rail twin of [SproutNavigationBarSample], 400 dp tall. */
@Composable
internal fun SproutNavigationRailSample(tier: KidUiTier?) {
    val items = if (tier == null) parentNavItems() else kidNavItems(tier)
    SproutNavigationRail(
        items = items,
        selectedIndex = 1,
        onSelect = {},
        modifier = Modifier.height(RailSampleHeight),
        windowInsets = WindowInsets(0),
    )
}

private val RailSampleHeight = 400.dp

private val NavIconSize = 24.dp

@Preview(name = "Seedling", showBackground = true)
@Composable
private fun NavigationBarSeedlingPreview() = SproutPreviewFrame(KidUiTier.SEEDLING) {
    SproutNavigationBarSample(KidUiTier.SEEDLING)
}

@Preview(name = "Sprout", showBackground = true)
@Composable
private fun NavigationBarSproutPreview() = SproutPreviewFrame(KidUiTier.SPROUT) {
    SproutNavigationBarSample(KidUiTier.SPROUT)
}

@Preview(name = "Sapling", showBackground = true)
@Composable
private fun NavigationBarSaplingPreview() = SproutPreviewFrame(KidUiTier.SAPLING) {
    SproutNavigationBarSample(KidUiTier.SAPLING)
}

@Preview(name = "Parent", showBackground = true)
@Composable
private fun NavigationBarParentPreview() = SproutPreviewFrame(tier = null) { SproutNavigationBarSample(null) }

@Preview(name = "Rail, Seedling", showBackground = true)
@Composable
private fun NavigationRailSeedlingPreview() = SproutPreviewFrame(KidUiTier.SEEDLING) {
    SproutNavigationRailSample(KidUiTier.SEEDLING)
}

@Preview(name = "Rail, Sprout", showBackground = true)
@Composable
private fun NavigationRailSproutPreview() = SproutPreviewFrame(KidUiTier.SPROUT) {
    SproutNavigationRailSample(KidUiTier.SPROUT)
}

@Preview(name = "Rail, Sapling", showBackground = true)
@Composable
private fun NavigationRailSaplingPreview() = SproutPreviewFrame(KidUiTier.SAPLING) {
    SproutNavigationRailSample(KidUiTier.SAPLING)
}

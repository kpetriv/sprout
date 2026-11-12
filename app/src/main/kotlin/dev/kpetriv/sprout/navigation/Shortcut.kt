package dev.kpetriv.sprout.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.R
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutButtonStyle
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.ProvideKidTier
import dev.kpetriv.sprout.core.designsystem.theme.STACK_FONT_SCALE
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.contentWidth

/** What a [Shortcut] opens: its [label] (plain text, never emoji, D-M9-5) and its [icon], left of the label. */
internal enum class ShortcutKind(@param:StringRes val label: Int) {
    CHAT(R.string.home_chat),
    GAMES(R.string.home_games),
    SCREEN_TIME(R.string.home_screen_time),
    ;

    val icon: ImageVector
        @Composable get() = when (this) {
            CHAT -> SproutIcons.ChatBubble
            GAMES -> SproutIcons.SportsEsports
            SCREEN_TIME -> SproutIcons.Timer
        }
}

/** One button of a [WithShortcuts] bar. */
internal data class Shortcut(val kind: ShortcutKind, val onClick: () -> Unit)

/**
 * [content] (the kid's wallet) with a bar of [shortcuts] under it (M4: Chat and Screen time; M5: Games; M9: see
 * [kidShortcuts]). The bar takes the navigation bar inset, so the screen above does not pad for it again (inside the
 * shell the bottom bar has taken it already). Without shortcuts the content is shown as is.
 */
@Composable
internal fun WithShortcuts(shortcuts: List<Shortcut>, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (shortcuts.isEmpty()) {
        Box(modifier) { content() }
        return
    }
    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars)) { content() }
        ShortcutBar(shortcuts)
    }
}

/**
 * Seedling's giant pills (M9 §2.6, §3): a white bar with a Hairline on top, like the bottom bar under it, holding
 * Tertiary `SproutButton`s (Linen fill, Hairline border), so the wallet's Botanical Chores and Honey Shop pills stay
 * the screen's one or two loud actions. Only Seedling has shortcuts ([kidShortcuts]) and the shell's content is not
 * inside the kid's tier, so the bar provides Seedling's itself: 72 dp pills with Seedling's label size.
 *
 * Two pills share a row (three do not fit a phone at Seedling's size without breaking a word), so three are two over
 * one, the last as wide as the bar; at [STACK_FONT_SCALE] and above they stack, one per row. Every pill keeps its
 * label. On a tablet the bar keeps a phone's proportions, centred (M7 §5).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShortcutBar(shortcuts: List<Shortcut>) {
    ProvideKidTier(KidUiTier.SEEDLING) {
        val scheme = MaterialTheme.colorScheme
        val perRow = if (LocalDensity.current.fontScale >= STACK_FONT_SCALE) 1 else 2
        Surface(color = scheme.surfaceContainerLowest) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                HorizontalDivider(thickness = 1.dp, color = scheme.outlineVariant)
                FlowRow(
                    modifier = Modifier
                        .contentWidth()
                        .padding(horizontal = SproutSpacing.gutter, vertical = SproutSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
                    maxItemsInEachRow = perRow,
                ) {
                    shortcuts.forEach { shortcut ->
                        SproutButton(
                            text = stringResource(shortcut.kind.label),
                            onClick = shortcut.onClick,
                            modifier = Modifier.weight(1f),
                            style = SproutButtonStyle.Tertiary,
                            icon = shortcut.kind.icon,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The kid's home shortcuts under the wallet: only Seedling has any, its giant buttons, Chat (real households only),
 * Games and Screen time (M9 §5.1: its bar has only Wallet, Chores and Shop). Chat and Games come first so they share
 * the top row; Screen time, the longest label, takes the row under them. Sprout and Sapling have Chat and Games in the
 * bar and reach Screen time from the wallet's screen-time badge, so they get none.
 */
internal fun kidShortcuts(
    tier: KidUiTier,
    chat: Boolean,
    onChat: () -> Unit,
    onScreenTime: () -> Unit,
    onGames: () -> Unit,
): List<Shortcut> = if (tier == KidUiTier.SEEDLING) {
    buildList {
        if (chat) add(Shortcut(ShortcutKind.CHAT, onChat))
        add(Shortcut(ShortcutKind.GAMES, onGames))
        add(Shortcut(ShortcutKind.SCREEN_TIME, onScreenTime))
    }
} else {
    emptyList()
}

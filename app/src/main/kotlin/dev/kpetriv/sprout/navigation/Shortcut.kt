package dev.kpetriv.sprout.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.R
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.contentWidth

/** One button of a [WithShortcuts] bar. */
internal data class Shortcut(val label: Int, val onClick: () -> Unit)

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

@Composable
private fun ShortcutBar(shortcuts: List<Shortcut>) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                // On a tablet the buttons keep a phone's proportions, centred (M7 §5).
                .contentWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            shortcuts.forEach { shortcut ->
                // Three buttons share a phone's width (M5): a narrow inner padding and centred text, so a label that
                // has to wrap ("Screen time") wraps evenly instead of pushing its emoji onto a line of its own.
                FilledTonalButton(
                    onClick = shortcut.onClick,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Text(
                        stringResource(shortcut.label),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * The kid's home shortcuts under the wallet: only Seedling has any, its three giant buttons, Chat (real households
 * only), Screen time and Games (M9 §5.1: its bar has only Wallet, Chores and Shop). Sprout and Sapling have Chat and
 * Games in the bar and reach Screen time from the wallet's screen-time badge, so they get none.
 */
internal fun kidShortcuts(
    tier: KidUiTier,
    chat: Boolean,
    onChat: () -> Unit,
    onScreenTime: () -> Unit,
    onGames: () -> Unit,
): List<Shortcut> = if (tier == KidUiTier.SEEDLING) {
    buildList {
        if (chat) add(Shortcut(R.string.home_chat, onChat))
        add(Shortcut(R.string.home_screen_time, onScreenTime))
        add(Shortcut(R.string.home_games, onGames))
    }
} else {
    emptyList()
}

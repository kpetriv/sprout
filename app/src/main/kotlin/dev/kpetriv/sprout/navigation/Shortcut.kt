package dev.kpetriv.sprout.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.R

/** One button of a [WithShortcuts] bar. */
internal data class Shortcut(val label: Int, val onClick: () -> Unit)

/**
 * [content] (a feature's home screen) with a bar of [shortcuts] under it (M4: Chat and Screen time on the kid's home,
 * Chat on the parent dashboard). The bar takes the navigation bar inset, so the screen above does not pad for it
 * again. Without shortcuts the content is shown as is.
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            shortcuts.forEach { shortcut ->
                FilledTonalButton(
                    onClick = shortcut.onClick,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                ) {
                    Text(stringResource(shortcut.label), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** The kid's home shortcuts: Chat (real households only) and Screen time. */
internal fun kidShortcuts(chat: Boolean, onChat: () -> Unit, onScreenTime: () -> Unit): List<Shortcut> = buildList {
    if (chat) add(Shortcut(R.string.home_chat, onChat))
    add(Shortcut(R.string.home_screen_time, onScreenTime))
}

/** The parent dashboard's shortcut: Chat (real households only). */
internal fun parentShortcuts(chat: Boolean, onChat: () -> Unit): List<Shortcut> =
    if (chat) listOf(Shortcut(R.string.home_chat, onChat)) else emptyList()

package dev.kpetriv.sprout.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.kpetriv.sprout.core.designsystem.components.SproutCard
import dev.kpetriv.sprout.core.designsystem.components.SproutCardDefaults
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing

/**
 * A dialog's card on parent tokens (M9): a white `SproutCard` with [title] in the display face, [content], and the
 * [buttons] (SproutButtons, cancel first) ending on the right, stacked at large font sizes ([ButtonRow]).
 * [SettingsDialog] shows it in a dialog window; screen and screenshot tests render it inline (a text field inside a
 * dialog window never idles under Robolectric).
 */
@Composable
internal fun SettingsDialogCard(
    title: String,
    buttons: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // At most the window's height less the margins; the content scrolls inside it, so the buttons stay reachable
    // with the keyboard up or at a large font size.
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp - SproutSpacing.lg * 2
    SproutCard(
        modifier = modifier
            .widthIn(min = DialogMinWidth, max = DialogMaxWidth)
            .heightIn(max = maxHeight)
            .semantics { paneTitle = title },
        contentPadding = SproutCardDefaults.ContentPadding,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SproutSpacing.md)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SproutSpacing.md - SproutSpacing.xs),
            ) { content() }
            ButtonRow { buttons() }
        }
    }
}

/** Shows [card] (a [SettingsDialogCard]) in a dialog window. */
@Composable
internal fun SettingsDialog(onDismissRequest: () -> Unit, card: @Composable () -> Unit) {
    // Our own width: the card keeps the 24 dp margins of Material's dialogs and caps itself at 560 dp.
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.padding(horizontal = SproutSpacing.lg), contentAlignment = Alignment.Center) { card() }
    }
}

/**
 * A confirmation in a [SettingsDialogCard]: [title], [text] (and any [extra]), a quiet cancel and the [confirm]
 * button (a [DestructiveButton] for anything that deletes, erases or removes).
 */
@Composable
internal fun ConfirmDialog(
    title: String,
    onDismiss: () -> Unit,
    confirm: @Composable () -> Unit,
    cancelEnabled: Boolean = true,
    extra: @Composable () -> Unit = {},
    text: String? = null,
) {
    SettingsDialog(onDismissRequest = onDismiss) {
        SettingsDialogCard(
            title = title,
            buttons = {
                QuietButton(
                    text = stringResource(R.string.feature_settings_cancel),
                    onClick = onDismiss,
                    enabled = cancelEnabled,
                )
                confirm()
            },
        ) {
            text?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            extra()
        }
    }
}

private val DialogMinWidth = 280.dp
private val DialogMaxWidth = 560.dp

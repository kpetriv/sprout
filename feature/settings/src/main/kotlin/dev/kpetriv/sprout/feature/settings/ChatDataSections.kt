package dev.kpetriv.sprout.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing

/* Settings → Household data, M7: chat retention, "Delete chat history now" and the export's progress. */

/** "Keep chat messages for" and "Delete chat history now". */
@Composable
internal fun ChatDataSection(state: DataUiState, onEvent: (DataEvent) -> Unit) {
    SectionHeading(stringResource(R.string.feature_settings_data_chat_title), icon = SproutIcons.ChatBubble)
    val retention = state.retention
    if (retention != null) {
        ChoiceDropdown(
            label = stringResource(R.string.feature_settings_data_retention_label),
            options = retention.options,
            selected = retention.days,
            optionLabel = { retentionLabel(it) },
            onSelect = { onEvent(DataEvent.RetentionChosen(it)) },
            enabled = !retention.saving,
        )
    } else {
        // No household policy on this phone yet: it never writes one of its own (D-M7-57).
        Text(
            stringResource(R.string.feature_settings_policy_waiting),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        stringResource(R.string.feature_settings_data_retention_text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    DestructiveButton(
        text = stringResource(R.string.feature_settings_data_chat_delete),
        onClick = { onEvent(DataEvent.AskDeleteChat) },
        enabled = !state.purgingChat,
        style = DestructiveStyle.Outlined,
        icon = SproutIcons.Delete,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** "Export household": the text, the button and, while it runs, what it is doing. */
@Composable
internal fun ExportSection(state: DataUiState, onEvent: (DataEvent) -> Unit) {
    val download = ImageVector.vectorResource(R.drawable.feature_settings_ic_download)
    SectionHeading(stringResource(R.string.feature_settings_data_export_title), icon = download)
    Text(
        stringResource(R.string.feature_settings_data_export_text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        stringResource(R.string.feature_settings_data_export_chat_text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SproutButton(
        text = stringResource(R.string.feature_settings_data_export),
        onClick = { onEvent(DataEvent.Export) },
        enabled = !state.exporting && state.pendingExport == null,
        icon = download,
        modifier = Modifier.fillMaxWidth(),
    )
    state.exportProgress?.let { ExportProgressIndicator(it) }
}

@Composable
internal fun ExportProgressIndicator(progress: ExportProgress, modifier: Modifier = Modifier) {
    val label = when (progress.stage) {
        ExportStage.CONFIRMING -> stringResource(R.string.feature_settings_data_export_confirming)

        ExportStage.HOUSEHOLD -> stringResource(R.string.feature_settings_data_export_household)

        ExportStage.CHAT -> stringResource(R.string.feature_settings_data_export_chat)

        ExportStage.MEDIA ->
            stringResource(R.string.feature_settings_data_export_media, progress.done, progress.total)

        ExportStage.PACKING -> stringResource(R.string.feature_settings_data_export_packing)
    }
    Column(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
    ) {
        val fraction = progress.fraction
        val track = MaterialTheme.colorScheme.surfaceContainer
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                trackColor = track,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(trackColor = track, modifier = Modifier.fillMaxWidth())
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun retentionLabel(days: Int?): String = if (days == null) {
    stringResource(R.string.feature_settings_data_retention_forever)
} else {
    pluralStringResource(R.plurals.feature_settings_data_retention_days, days, days)
}

/** Asks before a shorter retention period, which deletes older messages on every phone. */
@Composable
internal fun RetentionConfirmDialog(choice: RetentionChoice, saving: Boolean, onEvent: (DataEvent) -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.feature_settings_data_retention_confirm_title, retentionLabel(choice.days)),
        text = stringResource(R.string.feature_settings_data_retention_confirm_text),
        onDismiss = { onEvent(DataEvent.DismissRetention) },
        cancelEnabled = !saving,
        confirm = {
            DestructiveButton(
                text = stringResource(R.string.feature_settings_data_retention_confirm),
                onClick = { onEvent(DataEvent.ConfirmRetention) },
                enabled = !saving,
            )
        },
        extra = { if (saving) LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
    )
}

/** "Delete chat history now": the typed word, then the parent's fingerprint or PIN (D-M7-8). */
@Composable
internal fun DeleteChatDialog(state: DataUiState, onEvent: (DataEvent) -> Unit) {
    SettingsDialog(onDismissRequest = { onEvent(DataEvent.DismissDeleteChat) }) { DeleteChatCard(state, onEvent) }
}

@Composable
internal fun DeleteChatCard(state: DataUiState, onEvent: (DataEvent) -> Unit, modifier: Modifier = Modifier) {
    SettingsDialogCard(
        title = stringResource(R.string.feature_settings_data_chat_delete_title),
        modifier = modifier,
        buttons = {
            QuietButton(
                text = stringResource(R.string.feature_settings_cancel),
                onClick = { onEvent(DataEvent.DismissDeleteChat) },
                enabled = !state.purgingChat,
            )
            DestructiveButton(
                text = stringResource(R.string.feature_settings_data_chat_delete_confirm),
                onClick = { onEvent(DataEvent.ConfirmDeleteChat) },
                enabled = state.canConfirmChatDelete,
            )
        },
    ) {
        Text(
            stringResource(R.string.feature_settings_data_chat_delete_text),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.feature_settings_data_delete_dialog_text, DataUiState.CHAT_DELETE_WORD),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = state.chatDeleteTyped.orEmpty(),
            onValueChange = { onEvent(DataEvent.ChatDeleteTyped(it)) },
            label = { Text(stringResource(R.string.feature_settings_data_delete_field)) },
            singleLine = true,
            enabled = !state.purgingChat,
            shape = MaterialTheme.shapes.small,
            colors = settingsFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.purgingChat) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

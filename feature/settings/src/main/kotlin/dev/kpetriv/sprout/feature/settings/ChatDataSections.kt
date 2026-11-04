package dev.kpetriv.sprout.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/* Settings → Household data, M7: chat retention, "Delete chat history now" and the export's progress. */

/** "Keep chat messages for" and "Delete chat history now". */
@Composable
internal fun ChatDataSection(state: DataUiState, onEvent: (DataEvent) -> Unit) {
    SectionHeading(stringResource(R.string.feature_settings_data_chat_title))
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
    Text(stringResource(R.string.feature_settings_data_retention_text), style = MaterialTheme.typography.bodyMedium)
    OutlinedButton(onClick = { onEvent(DataEvent.AskDeleteChat) }, enabled = !state.purgingChat) {
        Text(stringResource(R.string.feature_settings_data_chat_delete), color = MaterialTheme.colorScheme.error)
    }
}

/** "Export household": the text, the button and, while it runs, what it is doing. */
@Composable
internal fun ExportSection(state: DataUiState, onEvent: (DataEvent) -> Unit) {
    SectionHeading(stringResource(R.string.feature_settings_data_export_title))
    Text(stringResource(R.string.feature_settings_data_export_text), style = MaterialTheme.typography.bodyMedium)
    Text(
        stringResource(R.string.feature_settings_data_export_chat_text),
        style = MaterialTheme.typography.bodyMedium,
    )
    Button(onClick = { onEvent(DataEvent.Export) }, enabled = !state.exporting && state.pendingExport == null) {
        Text(stringResource(R.string.feature_settings_data_export))
    }
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
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val fraction = progress.fraction
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(label, style = MaterialTheme.typography.bodySmall)
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
    AlertDialog(
        onDismissRequest = { onEvent(DataEvent.DismissRetention) },
        title = {
            Text(stringResource(R.string.feature_settings_data_retention_confirm_title, retentionLabel(choice.days)))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.feature_settings_data_retention_confirm_text))
                if (saving) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                onClick = { onEvent(DataEvent.ConfirmRetention) },
                enabled = !saving,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.feature_settings_data_retention_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(DataEvent.DismissRetention) }, enabled = !saving) {
                Text(stringResource(R.string.feature_settings_cancel))
            }
        },
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
            TextButton(onClick = { onEvent(DataEvent.DismissDeleteChat) }, enabled = !state.purgingChat) {
                Text(stringResource(R.string.feature_settings_cancel))
            }
            Button(
                onClick = { onEvent(DataEvent.ConfirmDeleteChat) },
                enabled = state.canConfirmChatDelete,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.feature_settings_data_chat_delete_confirm)) }
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
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.purgingChat) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

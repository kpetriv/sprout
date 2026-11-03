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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun AccountRoute(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: AccountViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    AccountScreen(state, viewModel::onEvent, onBack, modifier)
}

/** Settings → Account: who is signed in, the household, "Sign out" (data stays on the phone). */
@Composable
fun AccountScreen(
    state: AccountUiState,
    onEvent: (AccountEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(
        title = stringResource(R.string.feature_settings_section_account),
        onBack = onBack,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(it).sectionPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                state.email?.let { email -> stringResource(R.string.feature_settings_account_signed_in_as, email) }
                    ?: stringResource(R.string.feature_settings_account_signed_in),
                style = MaterialTheme.typography.titleMedium,
            )
            state.householdId?.let { id ->
                Text(
                    stringResource(R.string.feature_settings_account_household, id.value),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
            OutlinedButton(onClick = { onEvent(AccountEvent.AskSignOut) }, enabled = !state.signingOut) {
                Text(stringResource(R.string.feature_settings_account_sign_out))
            }
            if (state.signingOut) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
    if (state.confirmingSignOut) {
        AlertDialog(
            onDismissRequest = { onEvent(AccountEvent.DismissSignOut) },
            title = { Text(stringResource(R.string.feature_settings_account_sign_out_title)) },
            text = { Text(stringResource(R.string.feature_settings_account_sign_out_text)) },
            confirmButton = {
                TextButton(onClick = { onEvent(AccountEvent.ConfirmSignOut) }) {
                    Text(stringResource(R.string.feature_settings_account_sign_out))
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(AccountEvent.DismissSignOut) }) {
                    Text(stringResource(R.string.feature_settings_cancel))
                }
            },
        )
    }
}

@Composable
internal fun DataRoute(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: DataViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ZIP_MIME)) { uri ->
        viewModel.onEvent(DataEvent.ExportDestination(uri?.toString()))
    }
    val pending = state.pendingExport
    LaunchedEffect(pending) { pending?.let { launcher.launch(it.fileName) } }
    DataScreen(state, viewModel::onEvent, onBack, modifier)
}

/** Settings → Household data: export (zip of JSON + ledger.csv) and deletion with a typed confirmation. */
@Composable
fun DataScreen(state: DataUiState, onEvent: (DataEvent) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val snackbar = remember { SnackbarHostState() }
    SettingsScaffold(
        title = stringResource(R.string.feature_settings_section_data),
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbar,
    ) {
        Column(
            modifier = Modifier.padding(it).verticalScroll(rememberScrollState()).sectionPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeading(stringResource(R.string.feature_settings_data_export_title))
            Text(
                stringResource(R.string.feature_settings_data_export_text),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { onEvent(DataEvent.Export) }, enabled = !state.exporting && state.pendingExport == null) {
                Text(stringResource(R.string.feature_settings_data_export))
            }
            if (state.exporting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            HorizontalDivider()
            SectionHeading(stringResource(R.string.feature_settings_data_delete_title))
            Text(
                stringResource(R.string.feature_settings_data_delete_text),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { onEvent(DataEvent.AskDelete) }, enabled = state.householdId != null) {
                Text(stringResource(R.string.feature_settings_data_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    DataMessageEffect(state.message, snackbar) { onEvent(DataEvent.MessageShown) }
    if (state.deleteTyped != null) {
        AlertDialog(
            onDismissRequest = { onEvent(DataEvent.DismissDelete) },
            title = { Text(stringResource(R.string.feature_settings_data_delete_dialog_title)) },
            text = { DeleteHouseholdContent(state, onEvent) },
            confirmButton = {
                Button(
                    onClick = { onEvent(DataEvent.ConfirmDelete) },
                    enabled = state.canConfirmDelete,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.feature_settings_data_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(DataEvent.DismissDelete) }, enabled = !state.deleting) {
                    Text(stringResource(R.string.feature_settings_cancel))
                }
            },
        )
    }
}

/** The delete dialog's body: what to type, the field, progress. */
@Composable
internal fun DeleteHouseholdContent(state: DataUiState, onEvent: (DataEvent) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.feature_settings_data_delete_text))
        Text(stringResource(R.string.feature_settings_data_delete_dialog_text, state.deleteConfirmation))
        OutlinedTextField(
            value = state.deleteTyped.orEmpty(),
            onValueChange = { onEvent(DataEvent.DeleteTyped(it)) },
            label = { Text(stringResource(R.string.feature_settings_data_delete_field)) },
            singleLine = true,
            enabled = !state.deleting,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.deleting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun DataMessageEffect(message: DataMessage?, snackbar: SnackbarHostState, onShown: () -> Unit) {
    val text = message?.let {
        stringResource(
            when (it) {
                DataMessage.EXPORTED -> R.string.feature_settings_data_message_exported
                DataMessage.EXPORT_FAILED -> R.string.feature_settings_data_message_export_failed
                DataMessage.EXPORT_TOO_LARGE -> R.string.feature_settings_data_message_too_large
                DataMessage.DELETE_FAILED -> R.string.feature_settings_data_message_delete_failed
                DataMessage.NETWORK -> R.string.feature_settings_data_message_network
            },
        )
    }
    val shown by rememberUpdatedState(onShown)
    LaunchedEffect(message, text) {
        if (text != null) {
            snackbar.showSnackbar(text)
            shown()
        }
    }
}

private const val ZIP_MIME = "application/zip"

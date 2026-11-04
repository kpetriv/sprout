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

@Composable
internal fun AccountRoute(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: AccountViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    AccountScreen(state, viewModel::onEvent, onBack, modifier)
}

/** Settings → Account: who is signed in, the household, "Sign out" (erases this phone's copy after a confirmation). */
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

/**
 * Settings → Household data: chat retention and "Delete chat history now" (M7 §2), export (zip of JSON, ledger.csv
 * and the readable chat, M7 §3) with its progress, and deletion with a typed confirmation.
 */
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
            ChatDataSection(state, onEvent)
            HorizontalDivider()
            ExportSection(state, onEvent)
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
    state.reauth?.let { ReauthDialog(it, onEvent) }
    state.retention?.let { row -> row.pending?.let { RetentionConfirmDialog(it, row.saving, onEvent) } }
    if (state.chatDeleteTyped != null) DeleteChatDialog(state, onEvent)
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

/** "Sign in again to confirm": the server wants a recent sign-in before it deletes the household (D-M3-34). */
@Composable
internal fun ReauthDialog(reauth: ReauthUi, onEvent: (DataEvent) -> Unit) {
    SettingsDialog(onDismissRequest = { onEvent(DataEvent.DismissReauth) }) { ReauthCard(reauth, onEvent) }
}

@Composable
internal fun ReauthCard(reauth: ReauthUi, onEvent: (DataEvent) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Not saveable: a password never goes into saved instance state.
    var password by remember { mutableStateOf("") }
    SettingsDialogCard(
        title = stringResource(R.string.feature_settings_data_reauth_title),
        modifier = modifier,
        buttons = {
            TextButton(onClick = { onEvent(DataEvent.DismissReauth) }, enabled = !reauth.busy) {
                Text(stringResource(R.string.feature_settings_cancel))
            }
            Button(
                onClick = { onEvent(DataEvent.SignInAgain(context, password.takeIf { reauth.usesPassword })) },
                enabled = !reauth.busy && (!reauth.usesPassword || password.isNotEmpty()),
            ) {
                Text(
                    stringResource(
                        if (reauth.usesPassword) {
                            R.string.feature_settings_data_reauth_sign_in
                        } else {
                            R.string.feature_settings_data_reauth_google
                        },
                    ),
                )
            }
        },
    ) {
        Text(stringResource(R.string.feature_settings_data_reauth_text), style = MaterialTheme.typography.bodyMedium)
        reauth.problem?.let { problem ->
            Text(
                stringResource(
                    when (problem) {
                        ReauthProblem.WRONG_ACCOUNT -> R.string.feature_settings_data_reauth_wrong_account
                        ReauthProblem.SIGN_IN_FAILED -> R.string.feature_settings_data_reauth_failed
                        ReauthProblem.NETWORK -> R.string.feature_settings_data_message_network
                    },
                ),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (reauth.usesPassword) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.feature_settings_data_reauth_password)) },
                singleLine = true,
                enabled = !reauth.busy,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (reauth.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
    val text = message?.let { stringResource(it.textRes()) }
    val shown by rememberUpdatedState(onShown)
    LaunchedEffect(message, text) {
        if (text != null) {
            snackbar.showSnackbar(text)
            shown()
        }
    }
}

private fun DataMessage.textRes(): Int = when (this) {
    DataMessage.EXPORTED -> R.string.feature_settings_data_message_exported
    DataMessage.EXPORT_FAILED -> R.string.feature_settings_data_message_export_failed
    DataMessage.EXPORT_TOO_LARGE -> R.string.feature_settings_data_message_too_large
    DataMessage.DELETE_FAILED -> R.string.feature_settings_data_message_delete_failed
    DataMessage.NETWORK -> R.string.feature_settings_data_message_network
    DataMessage.NOT_CONFIRMED -> R.string.feature_settings_data_message_not_confirmed
    DataMessage.RETENTION_SAVED -> R.string.feature_settings_data_message_retention_saved
    DataMessage.RETENTION_FAILED -> R.string.feature_settings_data_message_retention_failed
    DataMessage.CHAT_DELETED -> R.string.feature_settings_data_message_chat_deleted
    DataMessage.CHAT_DELETED_ON_THIS_PHONE -> R.string.feature_settings_data_message_chat_deleted_here
    DataMessage.CHAT_DELETE_FAILED -> R.string.feature_settings_data_message_chat_delete_failed
    DataMessage.POLICY_NOT_SYNCED -> R.string.feature_settings_policy_not_synced
}

private const val ZIP_MIME = "application/zip"

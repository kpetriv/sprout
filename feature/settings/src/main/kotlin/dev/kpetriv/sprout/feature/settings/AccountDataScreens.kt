package dev.kpetriv.sprout.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing

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
    ) { padding ->
        SettingsColumn(padding = padding) {
            SettingsCard {
                SectionHeading(
                    state.email?.let { email -> stringResource(R.string.feature_settings_account_signed_in_as, email) }
                        ?: stringResource(R.string.feature_settings_account_signed_in),
                    icon = ImageVector.vectorResource(R.drawable.feature_settings_ic_account_circle),
                    iconTint = MaterialTheme.colorScheme.primary,
                )
                state.householdId?.let { id ->
                    Text(
                        stringResource(R.string.feature_settings_account_household, id.value),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Signing out erases this phone's copy of the household (D-M3-47): a destructive action.
                DestructiveButton(
                    text = stringResource(R.string.feature_settings_account_sign_out),
                    onClick = { onEvent(AccountEvent.AskSignOut) },
                    enabled = !state.signingOut,
                    style = DestructiveStyle.Outlined,
                    icon = ImageVector.vectorResource(R.drawable.feature_settings_ic_logout),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.signingOut) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
    if (state.confirmingSignOut) {
        ConfirmDialog(
            title = stringResource(R.string.feature_settings_account_sign_out_title),
            text = stringResource(R.string.feature_settings_account_sign_out_text),
            onDismiss = { onEvent(AccountEvent.DismissSignOut) },
            confirm = {
                DestructiveButton(
                    text = stringResource(R.string.feature_settings_account_sign_out),
                    onClick = { onEvent(AccountEvent.ConfirmSignOut) },
                )
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
    ) { padding ->
        SettingsColumn(padding = padding) {
            SettingsCard { ChatDataSection(state, onEvent) }
            SettingsCard { ExportSection(state, onEvent) }
            SettingsCard { DeleteHouseholdSection(state, onEvent) }
        }
    }
    DataMessageEffect(state.message, snackbar) { onEvent(DataEvent.MessageShown) }
    state.reauth?.let { ReauthDialog(it, onEvent) }
    state.retention?.let { row -> row.pending?.let { RetentionConfirmDialog(it, row.saving, onEvent) } }
    if (state.chatDeleteTyped != null) DeleteChatDialog(state, onEvent)
    if (state.deleteTyped != null) {
        ConfirmDialog(
            title = stringResource(R.string.feature_settings_data_delete_dialog_title),
            onDismiss = { onEvent(DataEvent.DismissDelete) },
            cancelEnabled = !state.deleting,
            confirm = {
                DestructiveButton(
                    text = stringResource(R.string.feature_settings_data_delete_confirm),
                    onClick = { onEvent(DataEvent.ConfirmDelete) },
                    enabled = state.canConfirmDelete,
                )
            },
            extra = { DeleteHouseholdContent(state, onEvent) },
        )
    }
}

/** "Delete household" (MG-08): the warning heading, what it deletes, and the filled `error` pill. */
@Composable
private fun DeleteHouseholdSection(state: DataUiState, onEvent: (DataEvent) -> Unit) {
    SectionHeading(
        stringResource(R.string.feature_settings_data_delete_title),
        icon = ImageVector.vectorResource(R.drawable.feature_settings_ic_warning),
        iconTint = MaterialTheme.colorScheme.error,
    )
    Text(
        stringResource(R.string.feature_settings_data_delete_text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    DestructiveButton(
        text = stringResource(R.string.feature_settings_data_delete),
        onClick = { onEvent(DataEvent.AskDelete) },
        enabled = state.householdId != null,
        icon = ImageVector.vectorResource(R.drawable.feature_settings_ic_delete_forever),
        modifier = Modifier.fillMaxWidth(),
    )
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
            QuietButton(
                text = stringResource(R.string.feature_settings_cancel),
                onClick = { onEvent(DataEvent.DismissReauth) },
                enabled = !reauth.busy,
            )
            // Signing in here goes straight on to deleting the household: the destructive colours.
            DestructiveButton(
                text = stringResource(
                    if (reauth.usesPassword) {
                        R.string.feature_settings_data_reauth_sign_in
                    } else {
                        R.string.feature_settings_data_reauth_google
                    },
                ),
                onClick = { onEvent(DataEvent.SignInAgain(context, password.takeIf { reauth.usesPassword })) },
                enabled = !reauth.busy && (!reauth.usesPassword || password.isNotEmpty()),
            )
        },
    ) {
        Text(stringResource(R.string.feature_settings_data_reauth_text), style = MaterialTheme.typography.bodyMedium)
        reauth.problem?.let { problem ->
            ErrorText(
                stringResource(
                    when (problem) {
                        ReauthProblem.WRONG_ACCOUNT -> R.string.feature_settings_data_reauth_wrong_account
                        ReauthProblem.SIGN_IN_FAILED -> R.string.feature_settings_data_reauth_failed
                        ReauthProblem.NETWORK -> R.string.feature_settings_data_message_network
                    },
                ),
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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = MaterialTheme.shapes.small,
                colors = settingsFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (reauth.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/** The delete dialog's body: what to type, the field, progress. */
@Composable
internal fun DeleteHouseholdContent(state: DataUiState, onEvent: (DataEvent) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(SproutSpacing.md - SproutSpacing.xs)) {
        Text(stringResource(R.string.feature_settings_data_delete_text), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.feature_settings_data_delete_dialog_text, state.deleteConfirmation),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = state.deleteTyped.orEmpty(),
            onValueChange = { onEvent(DataEvent.DeleteTyped(it)) },
            label = { Text(stringResource(R.string.feature_settings_data_delete_field)) },
            singleLine = true,
            enabled = !state.deleting,
            shape = MaterialTheme.shapes.small,
            colors = settingsFieldColors(),
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

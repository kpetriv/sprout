package dev.kpetriv.sprout.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.Role

@Composable
internal fun DevicesRoute(onBack: () -> Unit, reviewDeviceId: DeviceId?, modifier: Modifier = Modifier) {
    val viewModel: DevicesViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(reviewDeviceId) { reviewDeviceId?.let { viewModel.onEvent(DevicesEvent.Review(it)) } }
    DevicesScreen(state, viewModel::onEvent, onBack, modifier)
}

/** Settings → Devices: the list, the approval sheet and the revoke confirmation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    state: DevicesUiState,
    onEvent: (DevicesEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    SettingsScaffold(
        title = stringResource(R.string.feature_settings_section_devices),
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbar,
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!loadStatus(state.isLoading, state.loadFailed)) {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    if (state.devices.isEmpty()) {
                        Text(stringResource(R.string.feature_settings_devices_empty), Modifier.sectionPadding())
                    }
                    state.devices.forEachIndexed { index, row ->
                        if (index > 0) HorizontalDivider()
                        DeviceRow(row, state.busy, onEvent)
                    }
                }
            }
        }
    }
    DevicesMessageEffect(state.message, snackbar) { onEvent(DevicesEvent.MessageShown) }
    state.reviewing?.let { row ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { onEvent(DevicesEvent.DismissReview) }, sheetState = sheetState) {
            ApproveDeviceContent(row, state.busy, onEvent, Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp))
        }
    }
    state.revoking?.let { row ->
        AlertDialog(
            onDismissRequest = { onEvent(DevicesEvent.DismissRevoke) },
            title = { Text(stringResource(R.string.feature_settings_devices_revoke_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(deviceTitle(row))
                    Text(stringResource(R.string.feature_settings_devices_revoke_text))
                }
            },
            confirmButton = {
                TextButton(onClick = { onEvent(DevicesEvent.ConfirmRevoke) }, enabled = !state.busy) {
                    Text(
                        stringResource(R.string.feature_settings_devices_revoke_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(DevicesEvent.DismissRevoke) }) {
                    Text(stringResource(R.string.feature_settings_cancel))
                }
            },
        )
    }
}

@Composable
private fun DeviceRow(row: DeviceRowUi, busy: Boolean, onEvent: (DevicesEvent) -> Unit) {
    ListItem(
        headlineContent = { Text(deviceTitle(row)) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(
                        R.string.feature_settings_devices_row_detail,
                        statusText(row.status),
                        attestationText(row.attestation),
                        row.fingerprint,
                    ),
                )
                if (row.canApprove || row.canRevoke) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (row.canApprove) {
                            Button(onClick = { onEvent(DevicesEvent.Review(row.deviceId)) }, enabled = !busy) {
                                Text(stringResource(R.string.feature_settings_devices_approve))
                            }
                        }
                        if (row.canRevoke) {
                            OutlinedButton(onClick = {
                                onEvent(DevicesEvent.AskRevoke(row.deviceId))
                            }, enabled = !busy) {
                                Text(stringResource(R.string.feature_settings_devices_revoke))
                            }
                        }
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The approval sheet's content: the fingerprint to compare, the attestation warning, approve, or remove it. */
@Composable
internal fun ApproveDeviceContent(
    row: DeviceRowUi,
    busy: Boolean,
    onEvent: (DevicesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeading(stringResource(R.string.feature_settings_devices_review_title))
        Text(stringResource(R.string.feature_settings_devices_review_text), style = MaterialTheme.typography.bodyLarge)
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = row.fingerprint,
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 24.dp, horizontal = 16.dp),
            )
        }
        if (row.attestation == AttestationLabel.UNVERIFIED) {
            Text(
                stringResource(R.string.feature_settings_devices_review_unverified),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(onClick = { onEvent(DevicesEvent.Approve) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.feature_settings_devices_review_approve))
        }
        // A phone that is not the one in hand (or a stale PENDING entry) is removed rather than left waiting (L8).
        OutlinedButton(
            onClick = { onEvent(DevicesEvent.RejectReview) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.feature_settings_devices_review_reject)) }
    }
}

@Composable
private fun deviceTitle(row: DeviceRowUi): String {
    val owner = when (row.role) {
        Role.PARENT -> stringResource(R.string.feature_settings_devices_role_parent)

        Role.KID -> row.kidName?.let { stringResource(R.string.feature_settings_devices_role_kid, it) }
            ?: stringResource(R.string.feature_settings_devices_role_kid_unknown)
    }
    return if (row.isThisDevice) "$owner · ${stringResource(R.string.feature_settings_devices_this_device)}" else owner
}

@Composable
private fun statusText(status: RegistrationStatus): String = stringResource(
    when (status) {
        RegistrationStatus.PENDING -> R.string.feature_settings_devices_status_pending
        RegistrationStatus.ACTIVE -> R.string.feature_settings_devices_status_active
        RegistrationStatus.REVOKED -> R.string.feature_settings_devices_status_revoked
    },
)

@Composable
private fun attestationText(label: AttestationLabel): String = stringResource(
    when (label) {
        AttestationLabel.STRONGBOX -> R.string.feature_settings_devices_attestation_strongbox
        AttestationLabel.TEE -> R.string.feature_settings_devices_attestation_tee
        AttestationLabel.UNVERIFIED -> R.string.feature_settings_devices_attestation_unverified
    },
)

@Composable
private fun DevicesMessageEffect(message: DevicesMessage?, snackbar: SnackbarHostState, onShown: () -> Unit) {
    val text = message?.let {
        stringResource(
            when (it) {
                DevicesMessage.APPROVED -> R.string.feature_settings_devices_message_approved
                DevicesMessage.REVOKED -> R.string.feature_settings_devices_message_revoked
                DevicesMessage.FAILED -> R.string.feature_settings_devices_message_failed
                DevicesMessage.NETWORK -> R.string.feature_settings_devices_message_network
                DevicesMessage.NOT_ALLOWED -> R.string.feature_settings_devices_message_not_allowed
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

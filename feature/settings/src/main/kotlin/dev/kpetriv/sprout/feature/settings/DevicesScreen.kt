package dev.kpetriv.sprout.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
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
        if (!loadStatus(state.isLoading, state.loadFailed, Modifier.padding(padding))) {
            SettingsColumn(padding = padding, spacing = SproutSpacing.rowGap) {
                if (state.devices.isEmpty()) {
                    Text(
                        stringResource(R.string.feature_settings_devices_empty),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                state.devices.forEach { row -> DeviceRow(row, state.busy, onEvent) }
            }
        }
    }
    DevicesMessageEffect(state.message, snackbar) { onEvent(DevicesEvent.MessageShown) }
    state.reviewing?.let { row ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { onEvent(DevicesEvent.DismissReview) },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            ApproveDeviceContent(row, state.busy, onEvent, Modifier.sheetPadding())
        }
    }
    state.revoking?.let { row ->
        ConfirmDialog(
            title = stringResource(R.string.feature_settings_devices_revoke_title),
            onDismiss = { onEvent(DevicesEvent.DismissRevoke) },
            confirm = {
                DestructiveButton(
                    text = stringResource(R.string.feature_settings_devices_revoke_confirm),
                    onClick = { onEvent(DevicesEvent.ConfirmRevoke) },
                    enabled = !state.busy,
                )
            },
            extra = {
                Text(deviceTitle(row), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.feature_settings_devices_revoke_text),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
        )
    }
}

/** One phone as a white card: who it belongs to, its status, key storage and fingerprint, and its actions. */
@Composable
private fun DeviceRow(row: DeviceRowUi, busy: Boolean, onEvent: (DevicesEvent) -> Unit) {
    val title = deviceTitle(row)
    SettingsRow(
        title = title,
        summary = stringResource(
            R.string.feature_settings_devices_row_detail,
            statusText(row.status),
            attestationText(row.attestation),
            row.fingerprint,
        ),
        icon = ImageVector.vectorResource(R.drawable.feature_settings_ic_smartphone),
        extra = if (row.canApprove || row.canRevoke) {
            { DeviceActions(row, title, busy, onEvent) }
        } else {
            null
        },
    )
}

/** Approve and remove, each spoken with the phone it acts on (the rows repeat the same two words). */
@Composable
private fun DeviceActions(row: DeviceRowUi, title: String, busy: Boolean, onEvent: (DevicesEvent) -> Unit) {
    ButtonRow(modifier = Modifier.padding(top = SproutSpacing.sm), alignment = Alignment.Start) {
        if (row.canApprove) {
            val spoken = stringResource(R.string.feature_settings_devices_approve_for, title, row.fingerprint)
            SproutButton(
                text = stringResource(R.string.feature_settings_devices_approve),
                onClick = { onEvent(DevicesEvent.Review(row.deviceId)) },
                enabled = !busy,
                icon = SproutIcons.Check,
                modifier = Modifier.semantics { contentDescription = spoken },
            )
        }
        if (row.canRevoke) {
            val spoken = stringResource(R.string.feature_settings_devices_revoke_for, title, row.fingerprint)
            DestructiveButton(
                text = stringResource(R.string.feature_settings_devices_revoke),
                onClick = { onEvent(DevicesEvent.AskRevoke(row.deviceId)) },
                enabled = !busy,
                style = DestructiveStyle.Outlined,
                icon = SproutIcons.Delete,
                modifier = Modifier.semantics { contentDescription = spoken },
            )
        }
    }
}

/** The approval sheet's content: the fingerprint to compare, the attestation warning, approve, or remove it. */
@Composable
internal fun ApproveDeviceContent(
    row: DeviceRowUi,
    busy: Boolean,
    onEvent: (DevicesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(SproutSpacing.md)) {
        SectionHeading(
            stringResource(R.string.feature_settings_devices_review_title),
            icon = ImageVector.vectorResource(R.drawable.feature_settings_ic_smartphone),
        )
        Text(stringResource(R.string.feature_settings_devices_review_text), style = MaterialTheme.typography.bodyLarge)
        FingerprintTile(row.fingerprint)
        if (row.attestation == AttestationLabel.UNVERIFIED) {
            Text(
                stringResource(R.string.feature_settings_devices_review_unverified),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        SproutButton(
            text = stringResource(R.string.feature_settings_devices_review_approve),
            onClick = { onEvent(DevicesEvent.Approve) },
            enabled = !busy,
            icon = SproutIcons.Check,
            modifier = Modifier.fillMaxWidth(),
        )
        // A phone that is not the one in hand (or a stale PENDING entry) is removed rather than left waiting (L8).
        DestructiveButton(
            text = stringResource(R.string.feature_settings_devices_review_reject),
            onClick = { onEvent(DevicesEvent.RejectReview) },
            enabled = !busy,
            style = DestructiveStyle.Outlined,
            icon = SproutIcons.Close,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A device fingerprint to compare by eye: monospace, centred on a Linen tile. */
@Composable
internal fun FingerprintTile(fingerprint: String, modifier: Modifier = Modifier) {
    Text(
        text = fingerprint,
        style = MaterialTheme.typography.headlineSmall,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(vertical = SproutSpacing.lg, horizontal = SproutSpacing.md),
    )
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

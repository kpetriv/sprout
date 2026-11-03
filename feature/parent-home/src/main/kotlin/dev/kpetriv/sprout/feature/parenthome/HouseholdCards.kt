package dev.kpetriv.sprout.feature.parenthome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.Role
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/** The household cards at the top of the dashboard (M3 §6): trial banner, sync line, new devices, refused events. */
@Composable
internal fun HouseholdCards(status: HouseholdStatus, onSetUpSync: () -> Unit, onReviewDevice: (DeviceId) -> Unit) {
    if (status.trial) TrialBanner(onSetUpSync)
    status.sync?.let { SyncStatusLine(it) }
    if (status.pendingDevices.isNotEmpty()) NewDevicesCard(status.pendingDevices, onReviewDevice)
    if (status.rejected.isNotEmpty()) RejectedEventsCard(status.rejected)
}

@Composable
private fun TrialBanner(onSetUpSync: () -> Unit) {
    HouseholdCard(stringResource(R.string.feature_parent_home_trial_title), highlighted = true) {
        Text(stringResource(R.string.feature_parent_home_trial_text), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onSetUpSync) { Text(stringResource(R.string.feature_parent_home_trial_set_up)) }
    }
}

@Composable
private fun SyncStatusLine(sync: SyncLine) {
    val blocked = sync.blockedCode
    val text = when {
        blocked != null -> stringResource(R.string.feature_parent_home_sync_blocked, blocked)
        sync.failing -> stringResource(R.string.feature_parent_home_sync_failing)
        sync.lastSyncedAt != null -> stringResource(R.string.feature_parent_home_sync_last, timeText(sync.lastSyncedAt))
        else -> stringResource(R.string.feature_parent_home_sync_never)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (blocked != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NewDevicesCard(devices: List<PendingDevice>, onReviewDevice: (DeviceId) -> Unit) {
    HouseholdCard(stringResource(R.string.feature_parent_home_devices_title), highlighted = true) {
        Text(stringResource(R.string.feature_parent_home_devices_text), style = MaterialTheme.typography.bodyMedium)
        devices.forEach { device ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    device.fingerprint,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedButton(onClick = { onReviewDevice(device.deviceId) }) {
                    Text(stringResource(R.string.feature_parent_home_devices_review))
                }
            }
        }
    }
}

@Composable
private fun RejectedEventsCard(rejected: List<RejectedEvent>) {
    HouseholdCard(stringResource(R.string.feature_parent_home_rejected_title)) {
        Text(
            pluralStringResource(R.plurals.feature_parent_home_rejected_count, rejected.size, rejected.size),
            style = MaterialTheme.typography.bodyMedium,
        )
        rejected.take(MAX_ROWS).forEach { event ->
            Text(
                stringResource(
                    R.string.feature_parent_home_rejected_row,
                    reasonText(RejectionReason.of(event.reason)),
                    deviceText(event.device),
                    timeText(event.receivedAt),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (rejected.size > MAX_ROWS) {
            Text(
                stringResource(R.string.feature_parent_home_rejected_more, rejected.size - MAX_ROWS),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun HouseholdCard(title: String, highlighted: Boolean = false, content: @Composable () -> Unit) {
    val colors = if (highlighted) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    } else {
        CardDefaults.cardColors()
    }
    Card(modifier = Modifier.fillMaxWidth(), colors = colors) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun deviceText(device: DeviceLabel): String = when (device.role) {
    Role.PARENT -> stringResource(R.string.feature_parent_home_device_parent, device.fingerprint)

    Role.KID -> device.kidName?.let { stringResource(R.string.feature_parent_home_device_kid, it) }
        ?: stringResource(R.string.feature_parent_home_device_kid_unknown)

    null -> stringResource(R.string.feature_parent_home_device_unknown, device.fingerprint)
}

@Composable
private fun reasonText(reason: RejectionReason): String = stringResource(
    when (reason) {
        RejectionReason.UNREADABLE -> R.string.feature_parent_home_reason_unreadable
        RejectionReason.ALTERED -> R.string.feature_parent_home_reason_altered
        RejectionReason.UNKNOWN_DEVICE -> R.string.feature_parent_home_reason_unknown_device
        RejectionReason.DEVICE_NOT_ACTIVE -> R.string.feature_parent_home_reason_device_not_active
        RejectionReason.WRONG_ACCOUNT -> R.string.feature_parent_home_reason_wrong_account
        RejectionReason.ROLE_NOT_ALLOWED -> R.string.feature_parent_home_reason_role
        RejectionReason.BAD_SIGNATURE -> R.string.feature_parent_home_reason_bad_signature
        RejectionReason.PARENT_SIGNATURE_MISSING -> R.string.feature_parent_home_reason_parent_signature_missing
        RejectionReason.PARENT_SIGNATURE_INVALID -> R.string.feature_parent_home_reason_parent_signature_invalid
        RejectionReason.OUT_OF_ORDER -> R.string.feature_parent_home_reason_out_of_order
        RejectionReason.OTHER -> R.string.feature_parent_home_reason_other
    },
)

private fun timeText(instant: Instant): String = DateTimeFormatter.ofLocalizedDateTime(
    FormatStyle.SHORT,
).withZone(ZoneId.systemDefault()).format(instant.toJavaInstant())

private const val MAX_ROWS = 3

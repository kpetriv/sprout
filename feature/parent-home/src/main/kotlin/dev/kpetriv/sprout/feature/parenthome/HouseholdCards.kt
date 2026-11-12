package dev.kpetriv.sprout.feature.parenthome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutButtonStyle
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.Role
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * The household's rows of the "Needs you" card (M3 §6), in this order: the trial banner, the household policy not
 * synced (D-REL-14), the unregistered signing key (D-M3-39), fingerprint signing off (D-M3-46), a blocked sync, new
 * devices and refused events. Each is an [AttentionRow]: the security states lead with an icon on the error container
 * ([AttentionLead.Alert]), with the pill
 * that fixes them (Botanical where the fix is about signing, Linen otherwise); the words carry the state (D-REL-17,
 * audit N5).
 */
internal fun householdRows(status: HouseholdStatus, links: ParentHomeLinks): List<@Composable () -> Unit> = buildList {
    if (status.trial) add { TrialRow(links.onSetUpSync) }
    if (status.policyMissing) add { PolicyMissingRow(status.policyRestoreWithPin, links.onRegisterSigningKey) }
    if (status.signingKeyNotRegistered) add { SigningKeyNotRegisteredRow(links.onRegisterSigningKey) }
    if (status.signingOff) add { SigningOffRow(links.onRegisterSigningKey) }
    status.sync?.blockedCode?.let { code -> add { SyncBlockedRow(code) } }
    if (status.pendingDevices.isNotEmpty()) add { NewDevicesRow(status.pendingDevices, links.onReviewDevice) }
    if (status.rejected.isNotEmpty()) add { RejectedEventsRow(status.rejected) }
}

/**
 * D-REL-14: both actions open Settings → Security. With a strong biometric, fingerprint signing set-up recovers the
 * policy with the key; without one ([restoreWithPin]), "Restore household settings" recovers it with the PIN
 * (D-REL-22).
 */
@Composable
private fun PolicyMissingRow(restoreWithPin: Boolean, onOpenSecurity: () -> Unit) {
    AttentionRow(
        lead = AttentionLead.Alert(SproutIcons.Lock),
        title = stringResource(R.string.feature_parent_home_policy_missing_title),
        detail = stringResource(
            if (restoreWithPin) {
                R.string.feature_parent_home_policy_missing_restore_text
            } else {
                R.string.feature_parent_home_policy_missing_text
            },
        ),
        actionBelow = true,
        action = { modifier ->
            SproutButton(
                text = stringResource(
                    if (restoreWithPin) {
                        R.string.feature_parent_home_policy_missing_restore
                    } else {
                        R.string.feature_parent_home_policy_missing_set_up
                    },
                ),
                onClick = onOpenSecurity,
                modifier = modifier,
            )
        },
    )
}

@Composable
private fun SigningOffRow(onTurnOn: () -> Unit) {
    AttentionRow(
        lead = AttentionLead.Alert(SproutIcons.Lock),
        title = stringResource(R.string.feature_parent_home_signing_off_title),
        detail = stringResource(R.string.feature_parent_home_signing_off_text),
        actionBelow = true,
        action = { modifier ->
            SproutButton(stringResource(R.string.feature_parent_home_signing_off_turn_on), onTurnOn, modifier)
        },
    )
}

@Composable
private fun SigningKeyNotRegisteredRow(onRetry: () -> Unit) {
    AttentionRow(
        lead = AttentionLead.Alert(SproutIcons.Lock),
        title = stringResource(R.string.feature_parent_home_signing_key_title),
        detail = stringResource(R.string.feature_parent_home_signing_key_text),
        actionBelow = true,
        action = { modifier ->
            SproutButton(stringResource(R.string.feature_parent_home_signing_key_retry), onRetry, modifier)
        },
    )
}

@Composable
private fun TrialRow(onSetUpSync: () -> Unit) {
    AttentionRow(
        lead = AttentionLead.Symbol(SproutIcons.Eco),
        title = stringResource(R.string.feature_parent_home_trial_title),
        detail = stringResource(R.string.feature_parent_home_trial_text),
        actionBelow = true,
        action = { modifier ->
            SproutButton(
                text = stringResource(R.string.feature_parent_home_trial_set_up),
                onClick = onSetUpSync,
                style = SproutButtonStyle.Tertiary,
                modifier = modifier,
            )
        },
    )
}

/** A refused entry of this phone blocks sync (the server's code never shows, only a human reason). */
@Composable
private fun SyncBlockedRow(code: String) {
    AttentionRow(
        lead = AttentionLead.Alert(SproutIcons.Close),
        title = null,
        detail = stringResource(
            R.string.feature_parent_home_sync_blocked,
            stringResource(
                when (SyncBlockedReason.of(code)) {
                    SyncBlockedReason.NOT_ALLOWED -> R.string.feature_parent_home_sync_blocked_not_allowed
                    SyncBlockedReason.INVALID -> R.string.feature_parent_home_sync_blocked_invalid
                    SyncBlockedReason.OTHER -> R.string.feature_parent_home_sync_blocked_other
                },
            ),
        ),
    )
}

/** "Synced …", "Not synced yet" or "Couldn't sync just now" under the bar; a blocked sync is a row instead. */
@Composable
internal fun SyncStatusLine(sync: SyncLine, modifier: Modifier = Modifier) {
    if (sync.blockedCode != null) return
    val text = when {
        sync.failing -> stringResource(R.string.feature_parent_home_sync_failing)
        sync.lastSyncedAt != null -> stringResource(R.string.feature_parent_home_sync_last, timeText(sync.lastSyncedAt))
        else -> stringResource(R.string.feature_parent_home_sync_never)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun NewDevicesRow(devices: List<PendingDevice>, onReviewDevice: (DeviceId) -> Unit) {
    AttentionRow(
        lead = AttentionLead.Alert(SproutIcons.Lock),
        title = stringResource(R.string.feature_parent_home_devices_title),
        detail = stringResource(R.string.feature_parent_home_devices_text),
        extra = {
            devices.forEach { device ->
                Row(
                    modifier = Modifier.fillMaxWidth().semantics { isTraversalGroup = true },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
                ) {
                    Text(
                        device.fingerprint,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    // Read with the phone's short code, so several phones' Reviews can be told apart.
                    val description = stringResource(
                        R.string.feature_parent_home_devices_review_described,
                        device.fingerprint.take(SHORT_CODE_LENGTH),
                    )
                    SproutButton(
                        text = stringResource(R.string.feature_parent_home_devices_review),
                        onClick = { onReviewDevice(device.deviceId) },
                        style = SproutButtonStyle.Tertiary,
                        modifier = Modifier.semantics { contentDescription = description },
                    )
                }
            }
        },
    )
}

@Composable
private fun RejectedEventsRow(rejected: List<RejectedEvent>) {
    AttentionRow(
        lead = AttentionLead.Count(rejected.size),
        title = stringResource(R.string.feature_parent_home_rejected_title),
        detail = pluralStringResource(R.plurals.feature_parent_home_rejected_count, rejected.size, rejected.size),
        extra = {
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
        },
    )
}

@Composable
private fun deviceText(device: DeviceLabel): String = when (device.role) {
    Role.PARENT -> stringResource(R.string.feature_parent_home_device_parent, device.fingerprint)

    Role.KID -> device.kidName?.let { stringResource(R.string.feature_parent_home_device_kid, it) }
        ?: stringResource(R.string.feature_parent_home_device_kid_unknown)

    null -> stringResource(R.string.feature_parent_home_device_unknown, device.fingerprint)
}

@Composable
@Suppress("CyclomaticComplexMethod") // A flat lookup: one string per reason.
private fun reasonText(reason: RejectionReason): String = stringResource(
    when (reason) {
        RejectionReason.UNREADABLE -> R.string.feature_parent_home_reason_unreadable
        RejectionReason.ALTERED -> R.string.feature_parent_home_reason_altered
        RejectionReason.UNKNOWN_DEVICE -> R.string.feature_parent_home_reason_unknown_device
        RejectionReason.DEVICE_NOT_ACTIVE -> R.string.feature_parent_home_reason_device_not_active
        RejectionReason.WRONG_ACCOUNT -> R.string.feature_parent_home_reason_wrong_account
        RejectionReason.ROLE_NOT_ALLOWED -> R.string.feature_parent_home_reason_role
        RejectionReason.WRONG_KID -> R.string.feature_parent_home_reason_wrong_kid
        RejectionReason.BAD_SIGNATURE -> R.string.feature_parent_home_reason_bad_signature
        RejectionReason.PARENT_SIGNATURE_MISSING -> R.string.feature_parent_home_reason_parent_signature_missing
        RejectionReason.PARENT_SIGNATURE_INVALID -> R.string.feature_parent_home_reason_parent_signature_invalid
        RejectionReason.OUT_OF_ORDER -> R.string.feature_parent_home_reason_out_of_order
        RejectionReason.CONFLICTING_HISTORY -> R.string.feature_parent_home_reason_conflicting_history
        RejectionReason.WRONG_THREAD -> R.string.feature_parent_home_reason_wrong_thread
        RejectionReason.WAITING_FOR_EARLIER -> R.string.feature_parent_home_reason_waiting_for_earlier
        RejectionReason.WRONG_HOUSEHOLD -> R.string.feature_parent_home_reason_wrong_household
        RejectionReason.GAME_RULE -> R.string.feature_parent_home_reason_game_rule
        RejectionReason.OTHER -> R.string.feature_parent_home_reason_other
    },
)

private fun timeText(instant: Instant): String = DateTimeFormatter.ofLocalizedDateTime(
    FormatStyle.SHORT,
).withZone(ZoneId.systemDefault()).format(instant.toJavaInstant())

private const val MAX_ROWS = 3

/** The first two groups of a device fingerprint ("3fa9 c01d"). */
private const val SHORT_CODE_LENGTH = 9

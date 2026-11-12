package dev.kpetriv.sprout.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.ui.PinDots
import dev.kpetriv.sprout.core.ui.PinPad
import dev.kpetriv.sprout.core.ui.PinPadDefaults
import kotlin.time.Duration
import kotlin.time.DurationUnit

@Composable
internal fun ParentSecurityRoute(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: ParentSecurityViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(ParentSecurityEvent.Refresh)
        onPauseOrDispose {}
    }
    ParentSecurityScreen(state = state, onEvent = viewModel::onEvent, onBack = onBack, modifier = modifier)
}

/**
 * Fingerprint signing (on, turn on, renew after a fingerprint change, or the D-M2-1 warning), the auto-lock
 * preference, and PIN change. Every PIN here is typed on a [PinPad], never in a text field.
 */
@Composable
fun ParentSecurityScreen(
    state: ParentSecurityUiState,
    onEvent: (ParentSecurityEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    SettingsScaffold(
        title = stringResource(R.string.feature_settings_section_security),
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbar,
    ) { padding ->
        SettingsColumn(padding = padding) {
            SigningCard(state, onEvent)
            SettingsCard {
                LockWhenLeavingRow(state.lockWhenLeaving) { onEvent(ParentSecurityEvent.LockWhenLeavingChanged(it)) }
            }
            SettingsCard { ChangePinForm(state, onEvent) }
        }
    }
    MessageEffect(state.message, snackbar) { onEvent(ParentSecurityEvent.MessageShown) }
}

@Composable
private fun SigningCard(state: ParentSecurityUiState, onEvent: (ParentSecurityEvent) -> Unit) {
    val warning = state.signing == SigningStatus.UNAVAILABLE || state.signing == SigningStatus.KEY_INVALIDATED ||
        state.signing == SigningStatus.NOT_REGISTERED_WITH_HOUSEHOLD ||
        state.signing == SigningStatus.SIGNATURES_NOT_REQUIRED
    // A warning keeps its words in the title; the error-coloured warning icon only repeats it.
    SettingsCard {
        SectionHeading(
            text = stringResource(state.signingTitleRes()),
            icon = if (warning) {
                ImageVector.vectorResource(R.drawable.feature_settings_ic_warning)
            } else {
                SproutIcons.Lock
            },
            iconTint = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(state.signingDetailRes()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(
                if (state.policy?.requireParentSignature == true) {
                    R.string.feature_settings_security_policy_required
                } else {
                    R.string.feature_settings_security_policy_not_required
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        registerErrorText(state.registerError)?.let { ErrorText(it) }
        val entry = state.signingPin
        if (entry != null) {
            SigningPinEntry(entry, isRegistering = state.isRegistering, onEvent = onEvent)
        } else {
            SigningAction(state, onEvent)
        }
        if (state.offerRestore) RestoreSettings(isRestoring = state.isRestoring, onEvent = onEvent)
    }
}

/**
 * "Restore household settings" (D-REL-22): a phone without a strong biometric whose household policy exists nowhere
 * restores it with the PIN, signatures off.
 */
@Composable
private fun RestoreSettings(isRestoring: Boolean, onEvent: (ParentSecurityEvent) -> Unit) {
    Text(stringResource(R.string.feature_settings_security_restore_text), style = MaterialTheme.typography.bodyMedium)
    val label = if (isRestoring) {
        R.string.feature_settings_security_restoring
    } else {
        R.string.feature_settings_security_restore
    }
    SproutButton(
        text = stringResource(label),
        onClick = { onEvent(ParentSecurityEvent.RestoreHouseholdSettings) },
        enabled = !isRestoring,
    )
}

@Composable
private fun SigningAction(state: ParentSecurityUiState, onEvent: (ParentSecurityEvent) -> Unit) {
    val label = when (state.signing) {
        SigningStatus.NOT_SET_UP -> R.string.feature_settings_security_signing_register
        SigningStatus.KEY_INVALIDATED -> R.string.feature_settings_security_signing_renew
        SigningStatus.NOT_REGISTERED_WITH_HOUSEHOLD -> R.string.feature_settings_security_signing_retry
        SigningStatus.SIGNATURES_NOT_REQUIRED -> R.string.feature_settings_security_signing_renew
        SigningStatus.ON, SigningStatus.UNAVAILABLE -> return
    }
    SproutButton(
        text = stringResource(
            if (state.isRegistering) R.string.feature_settings_security_signing_registering else label,
        ),
        onClick = { onEvent(ParentSecurityEvent.StartSigningKeyChange) },
        enabled = !state.isRegistering,
    )
}

/** The PIN for turning signing on or renewing the key, typed on a [PinPad]; six digits submit. */
@Composable
private fun SigningPinEntry(entry: SigningPinUi, isRegistering: Boolean, onEvent: (ParentSecurityEvent) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.md - SproutSpacing.xs),
    ) {
        Text(
            stringResource(
                when {
                    isRegistering -> R.string.feature_settings_security_signing_registering
                    entry.purpose == SigningPinPurpose.ENROL -> R.string.feature_settings_security_signing_pin_enrol
                    else -> R.string.feature_settings_security_signing_pin_renew
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        PinDots(entered = entry.enteredDigits, length = PARENT_PIN_LENGTH, shakeKey = entry.rejections)
        PinPad(
            onDigit = { onEvent(ParentSecurityEvent.SigningPinDigit(it)) },
            onBackspace = { onEvent(ParentSecurityEvent.SigningPinBackspace) },
            enabled = !isRegistering,
            keySize = PinPadDefaults.CompactKeySize,
        )
        QuietButton(
            text = stringResource(R.string.feature_settings_security_cancel),
            onClick = { onEvent(ParentSecurityEvent.CancelSigningKeyChange) },
            enabled = !isRegistering,
        )
    }
}

@Composable
private fun registerErrorText(error: RegisterError?): String? = when (error) {
    null -> null

    RegisterError.Unavailable -> stringResource(R.string.feature_settings_security_register_unavailable)

    RegisterError.Failed -> stringResource(R.string.feature_settings_security_register_failed)

    RegisterError.Cancelled -> stringResource(R.string.feature_settings_security_register_cancelled)

    RegisterError.PolicyNotSynced -> stringResource(R.string.feature_settings_policy_not_synced)

    RegisterError.RequireBiometric -> stringResource(R.string.feature_settings_security_pin_require_biometric)

    is RegisterError.LockedOut -> stringResource(
        R.string.feature_settings_security_pin_locked_out,
        waitText(error.wait),
    )

    is RegisterError.WrongPin -> error.wait?.let {
        stringResource(R.string.feature_settings_security_register_wrong_pin_wait, waitText(it))
    } ?: stringResource(R.string.feature_settings_security_register_wrong_pin)
}

private fun ParentSecurityUiState.signingTitleRes(): Int = when (signing) {
    SigningStatus.UNAVAILABLE -> R.string.feature_settings_security_signing_unavailable_title
    SigningStatus.KEY_INVALIDATED -> R.string.feature_settings_security_signing_invalidated_title
    SigningStatus.NOT_REGISTERED_WITH_HOUSEHOLD -> R.string.feature_settings_security_signing_not_shared_title
    SigningStatus.SIGNATURES_NOT_REQUIRED -> R.string.feature_settings_security_signing_not_required_title
    SigningStatus.ON, SigningStatus.NOT_SET_UP -> R.string.feature_settings_security_signing_title
}

private fun ParentSecurityUiState.signingDetailRes(): Int = when (signing) {
    SigningStatus.ON -> R.string.feature_settings_security_signing_on

    SigningStatus.NOT_SET_UP -> R.string.feature_settings_security_signing_not_set_up

    SigningStatus.KEY_INVALIDATED -> R.string.feature_settings_security_signing_invalidated

    SigningStatus.NOT_REGISTERED_WITH_HOUSEHOLD -> R.string.feature_settings_security_signing_not_shared

    SigningStatus.SIGNATURES_NOT_REQUIRED -> R.string.feature_settings_security_signing_not_required

    SigningStatus.UNAVAILABLE -> when (availability) {
        ParentAuthAvailability.NOT_ENROLLED -> R.string.feature_settings_security_signing_not_enrolled

        ParentAuthAvailability.NO_HARDWARE -> R.string.feature_settings_security_signing_no_hardware

        ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE,
        ParentAuthAvailability.UNAVAILABLE_OTHER,
        -> R.string.feature_settings_security_signing_unavailable
    }
}

@Composable
private fun LockWhenLeavingRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row toggles, so TalkBack reads the title with the switch state (D-M7-5).
    SwitchRow(
        title = stringResource(R.string.feature_settings_security_lock_title),
        summary = stringResource(R.string.feature_settings_security_lock_summary),
        checked = checked,
        enabled = true,
        onChange = onChange,
    )
}

/**
 * Change PIN in three steps (current, new, confirm) on one [PinPad]. The digits live in the ViewModel's zeroed
 * [CharArray]s; this form only shows how many were typed. Six digits move to the next step; the last one submits.
 */
@Composable
private fun ChangePinForm(state: ParentSecurityUiState, onEvent: (ParentSecurityEvent) -> Unit) {
    val form = state.changePin
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.md - SproutSpacing.xs),
    ) {
        SectionHeading(
            stringResource(R.string.feature_settings_security_pin_title),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(
                when {
                    state.isChangingPin -> R.string.feature_settings_saving
                    form.step == ChangePinStep.CURRENT -> R.string.feature_settings_security_pin_current
                    form.step == ChangePinStep.NEW -> R.string.feature_settings_security_pin_new
                    else -> R.string.feature_settings_security_pin_confirm
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
        PinDots(entered = form.enteredDigits, length = PARENT_PIN_LENGTH, shakeKey = form.rejections)
        pinErrorText(state.pinError)?.let { ErrorText(it) }
        PinPad(
            onDigit = { onEvent(ParentSecurityEvent.ChangePinDigit(it)) },
            onBackspace = { onEvent(ParentSecurityEvent.ChangePinBackspace) },
            enabled = !state.isChangingPin,
            keySize = PinPadDefaults.CompactKeySize,
        )
        if (form.step != ChangePinStep.CURRENT && !state.isChangingPin) {
            QuietButton(
                text = stringResource(R.string.feature_settings_security_pin_start_over),
                onClick = { onEvent(ParentSecurityEvent.ChangePinStartOver) },
            )
        }
    }
}

@Composable
private fun pinErrorText(error: PinChangeError?): String? = when (error) {
    null -> null

    is PinChangeError.WrongCurrent -> error.wait?.let {
        stringResource(R.string.feature_settings_security_pin_wrong_wait, waitText(it))
    } ?: stringResource(R.string.feature_settings_security_pin_wrong)

    is PinChangeError.LockedOut -> stringResource(
        R.string.feature_settings_security_pin_locked_out,
        waitText(error.wait),
    )

    PinChangeError.RequireBiometric -> stringResource(R.string.feature_settings_security_pin_require_biometric)

    is PinChangeError.Invalid -> stringResource(error.problem.textRes())

    PinChangeError.Mismatch -> stringResource(R.string.feature_settings_security_pin_mismatch)

    PinChangeError.NotAllowed -> stringResource(R.string.feature_settings_security_pin_not_allowed)

    PinChangeError.Failed -> stringResource(R.string.feature_settings_security_pin_failed)
}

private fun NewPinProblem.textRes(): Int = when (this) {
    NewPinProblem.WRONG_LENGTH -> R.string.feature_settings_security_pin_wrong_length
    NewPinProblem.NOT_DIGITS -> R.string.feature_settings_security_pin_not_digits
    NewPinProblem.ALL_SAME -> R.string.feature_settings_security_pin_all_same
    NewPinProblem.SEQUENCE -> R.string.feature_settings_security_pin_sequence
}

/** A short wait such as "30 s" or "5 min", rounded up. */
private fun waitText(wait: Duration): String {
    val seconds = wait.toLong(DurationUnit.SECONDS).coerceAtLeast(1)
    return if (seconds < SECONDS_PER_MINUTE) {
        "$seconds s"
    } else {
        "${(seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE} min"
    }
}

private const val SECONDS_PER_MINUTE = 60L

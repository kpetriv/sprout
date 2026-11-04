package dev.kpetriv.sprout.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
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
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).sectionPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SigningCard(state, onEvent)
            HorizontalDivider()
            LockWhenLeavingRow(state.lockWhenLeaving) { onEvent(ParentSecurityEvent.LockWhenLeavingChanged(it)) }
            HorizontalDivider()
            ChangePinForm(state, onEvent)
        }
    }
    MessageEffect(state.message, snackbar) { onEvent(ParentSecurityEvent.MessageShown) }
}

@Composable
private fun SigningCard(state: ParentSecurityUiState, onEvent: (ParentSecurityEvent) -> Unit) {
    val warning = state.signing == SigningStatus.UNAVAILABLE || state.signing == SigningStatus.KEY_INVALIDATED ||
        state.signing == SigningStatus.NOT_REGISTERED_WITH_HOUSEHOLD
    val colors = if (warning) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    } else {
        CardDefaults.cardColors()
    }
    Card(modifier = Modifier.fillMaxWidth(), colors = colors) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(
                    when (state.signing) {
                        SigningStatus.UNAVAILABLE -> R.string.feature_settings_security_signing_unavailable_title

                        SigningStatus.KEY_INVALIDATED -> R.string.feature_settings_security_signing_invalidated_title

                        SigningStatus.NOT_REGISTERED_WITH_HOUSEHOLD ->
                            R.string.feature_settings_security_signing_not_shared_title

                        SigningStatus.ON, SigningStatus.NOT_SET_UP -> R.string.feature_settings_security_signing_title
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(state.signingDetailRes()), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(
                    if (state.policy?.requireParentSignature == true) {
                        R.string.feature_settings_security_policy_required
                    } else {
                        R.string.feature_settings_security_policy_not_required
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            registerErrorText(state.registerError)?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            val entry = state.signingPin
            if (entry != null) {
                SigningPinEntry(entry, isRegistering = state.isRegistering, onEvent = onEvent)
            } else {
                SigningAction(state, onEvent)
            }
        }
    }
}

@Composable
private fun SigningAction(state: ParentSecurityUiState, onEvent: (ParentSecurityEvent) -> Unit) {
    val label = when (state.signing) {
        SigningStatus.NOT_SET_UP -> R.string.feature_settings_security_signing_register
        SigningStatus.KEY_INVALIDATED -> R.string.feature_settings_security_signing_renew
        SigningStatus.NOT_REGISTERED_WITH_HOUSEHOLD -> R.string.feature_settings_security_signing_retry
        SigningStatus.ON, SigningStatus.UNAVAILABLE -> return
    }
    Button(onClick = { onEvent(ParentSecurityEvent.StartSigningKeyChange) }, enabled = !state.isRegistering) {
        Text(stringResource(if (state.isRegistering) R.string.feature_settings_security_signing_registering else label))
    }
}

/** The PIN for turning signing on or renewing the key, typed on a [PinPad]; six digits submit. */
@Composable
private fun SigningPinEntry(entry: SigningPinUi, isRegistering: Boolean, onEvent: (ParentSecurityEvent) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
        TextButton(onClick = { onEvent(ParentSecurityEvent.CancelSigningKeyChange) }, enabled = !isRegistering) {
            Text(stringResource(R.string.feature_settings_security_cancel))
        }
    }
}

@Composable
private fun registerErrorText(error: RegisterError?): String? = when (error) {
    null -> null

    RegisterError.Unavailable -> stringResource(R.string.feature_settings_security_register_unavailable)

    RegisterError.Failed -> stringResource(R.string.feature_settings_security_register_failed)

    RegisterError.Cancelled -> stringResource(R.string.feature_settings_security_register_cancelled)

    RegisterError.RequireBiometric -> stringResource(R.string.feature_settings_security_pin_require_biometric)

    is RegisterError.LockedOut -> stringResource(
        R.string.feature_settings_security_pin_locked_out,
        waitText(error.wait),
    )

    is RegisterError.WrongPin -> error.wait?.let {
        stringResource(R.string.feature_settings_security_register_wrong_pin_wait, waitText(it))
    } ?: stringResource(R.string.feature_settings_security_register_wrong_pin)
}

private fun ParentSecurityUiState.signingDetailRes(): Int = when (signing) {
    SigningStatus.ON -> R.string.feature_settings_security_signing_on

    SigningStatus.NOT_SET_UP -> R.string.feature_settings_security_signing_not_set_up

    SigningStatus.KEY_INVALIDATED -> R.string.feature_settings_security_signing_invalidated

    SigningStatus.NOT_REGISTERED_WITH_HOUSEHOLD -> R.string.feature_settings_security_signing_not_shared

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
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.feature_settings_security_lock_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.feature_settings_security_lock_summary),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
        pinErrorText(state.pinError)?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        PinPad(
            onDigit = { onEvent(ParentSecurityEvent.ChangePinDigit(it)) },
            onBackspace = { onEvent(ParentSecurityEvent.ChangePinBackspace) },
            enabled = !state.isChangingPin,
            keySize = PinPadDefaults.CompactKeySize,
        )
        if (form.step != ChangePinStep.CURRENT && !state.isChangingPin) {
            TextButton(onClick = { onEvent(ParentSecurityEvent.ChangePinStartOver) }) {
                Text(stringResource(R.string.feature_settings_security_pin_start_over))
            }
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

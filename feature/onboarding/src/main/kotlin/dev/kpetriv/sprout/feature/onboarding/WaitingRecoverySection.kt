package dev.kpetriv.sprout.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutButtonStyle
import dev.kpetriv.sprout.core.designsystem.components.SproutCard
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.ui.PinDots
import dev.kpetriv.sprout.core.ui.PinPad
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Instant

/*
 * The waiting screen's account recovery (ADR-0013 §3). The whole onboarding area runs with FLAG_SECURE (the app's
 * `AppModeContent`), so the PIN pad here is never captured. Digits never reach this UI: only how many were typed.
 */

/** "Don't have your other phone?" under the approval text: the PIN path, the wait, or why neither is offered. */
@Composable
internal fun RecoverySection(recovery: RecoveryUi, onEvent: (OnboardingEvent) -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text(
        stringResource(R.string.feature_onboarding_recovery_title),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() },
    )
    when (recovery) {
        RecoveryUi.Loading -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md - SproutSpacing.xs),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
            Text(
                stringResource(R.string.feature_onboarding_recovery_loading),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        is RecoveryUi.Unavailable -> {
            // The failure's own words: "offline" only when it is offline.
            FailureText(stringResource(recovery.failure.messageRes()))
            SproutButton(
                text = stringResource(R.string.feature_onboarding_recovery_retry),
                onClick = { onEvent(OnboardingEvent.RetryRecoveryStatus) },
                style = SproutButtonStyle.Tertiary,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        RecoveryUi.OtherParent -> Text(
            stringResource(R.string.feature_onboarding_recovery_other_parent),
            style = MaterialTheme.typography.bodyLarge,
        )

        is RecoveryUi.Ready -> {
            // Back on screen (the app was in the background over the end of the wait): read the clock again.
            LifecycleResumeEffect(Unit) {
                onEvent(OnboardingEvent.RecoveryResumed)
                onPauseOrDispose {}
            }
            ReadyOptions(recovery, onEvent)
        }
    }
}

@Composable
private fun ReadyOptions(state: RecoveryUi.Ready, onEvent: (OnboardingEvent) -> Unit) {
    val busy = state.restoring || state.signInNeeded
    if (state.signInNeeded) {
        // recent-sign-in: Credential Manager again, over this activity, then the restore is retried once.
        val context = LocalContext.current
        LaunchedEffect(Unit) { onEvent(OnboardingEvent.RecoverySignIn(context)) }
    }
    if (state.pinAvailable) {
        SproutButton(
            text = stringResource(R.string.feature_onboarding_recovery_with_pin),
            onClick = { onEvent(OnboardingEvent.ChooseRecovery(RecoveryChoice.PIN)) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    when {
        state.wrongPinAttemptsLeft == 0 -> FailureText(stringResource(R.string.feature_onboarding_recovery_pin_closed))

        state.pinUnsupported -> Text(
            stringResource(R.string.feature_onboarding_recovery_pin_unsupported),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    if (!state.showWait) {
        SproutButton(
            text = stringResource(R.string.feature_onboarding_recovery_forgot_pin),
            onClick = { onEvent(OnboardingEvent.ShowRecoveryWait) },
            enabled = !busy,
            style = SproutButtonStyle.Tertiary,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        WaitPart(state, busy, onEvent)
    }
    if (state.restoring && state.pinEntry == null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md - SproutSpacing.xs),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
            Text(
                stringResource(R.string.feature_onboarding_recovery_restoring),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
    state.failure?.let { FailureText(stringResource(it.messageRes())) }
    if (state.confirming != null) {
        Dialog(
            onDismissRequest = { onEvent(OnboardingEvent.CancelRecovery) },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(modifier = Modifier.padding(horizontal = SproutSpacing.lg), contentAlignment = Alignment.Center) {
                RecoveryConfirmCard(onEvent)
            }
        }
    }
}

/** The wait (ADR-0013 §3): "Start the 7-day wait", then its end date, and from that date "Restore this account". */
@Composable
private fun WaitPart(state: RecoveryUi.Ready, busy: Boolean, onEvent: (OnboardingEvent) -> Unit) {
    val endsAt = state.waitEndsAt
    if (endsAt == null) {
        Text(
            stringResource(R.string.feature_onboarding_recovery_wait_intro),
            style = MaterialTheme.typography.bodyLarge,
        )
        SproutButton(
            text = stringResource(R.string.feature_onboarding_recovery_start_wait),
            onClick = { onEvent(OnboardingEvent.StartRecoveryWait) },
            enabled = !busy,
            style = SproutButtonStyle.Tertiary,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    Text(
        stringResource(R.string.feature_onboarding_recovery_wait_date, recoveryDateTime(endsAt)),
        style = MaterialTheme.typography.bodyLarge,
    )
    if (state.waitOver) {
        SproutButton(
            text = stringResource(R.string.feature_onboarding_recovery_restore),
            onClick = { onEvent(OnboardingEvent.ChooseRecovery(RecoveryChoice.WAIT)) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** "Restore on this phone?" before either restore (ADR-0013 §3). Drawn inline by the screenshot tests. */
@Composable
internal fun RecoveryConfirmCard(onEvent: (OnboardingEvent) -> Unit, modifier: Modifier = Modifier) {
    val title = stringResource(R.string.feature_onboarding_recovery_confirm_title)
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp - SproutSpacing.lg * 2
    SproutCard(modifier = modifier.widthIn(max = 560.dp).heightIn(max = maxHeight).semantics { paneTitle = title }) {
        Column(verticalArrangement = Arrangement.spacedBy(SproutSpacing.md)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(
                stringResource(R.string.feature_onboarding_recovery_confirm_text),
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm, Alignment.End),
            ) {
                SproutButton(
                    text = stringResource(R.string.feature_onboarding_recovery_cancel),
                    onClick = { onEvent(OnboardingEvent.CancelRecovery) },
                    style = SproutButtonStyle.Tertiary,
                )
                DestructiveButton(
                    text = stringResource(R.string.feature_onboarding_recovery_confirm),
                    onClick = { onEvent(OnboardingEvent.ConfirmRecovery) },
                )
            }
        }
    }
}

/** The PIN pad that replaces the waiting text while the parent types the PIN they used before. */
@Composable
internal fun RecoveryPinPad(state: RecoveryUi.Ready, entry: RecoveryPinEntry, onEvent: (OnboardingEvent) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            stringResource(R.string.feature_onboarding_recovery_pin_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        PinDots(entered = entry.enteredDigits, length = entry.length, shakeKey = entry.rejections)
        val message = when {
            state.restoring -> stringResource(R.string.feature_onboarding_recovery_restoring)

            state.wrongPinAttemptsLeft != null -> pluralStringResource(
                R.plurals.feature_onboarding_recovery_wrong_pin,
                state.wrongPinAttemptsLeft,
                state.wrongPinAttemptsLeft,
            )

            state.failure != null -> stringResource(state.failure.messageRes())

            else -> ""
        }
        Text(
            text = message,
            color = if (state.restoring) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.heightIn(min = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (state.restoring) CircularProgressIndicator()
        PinPad(
            onDigit = { onEvent(OnboardingEvent.RecoveryPinDigit(it)) },
            onBackspace = { onEvent(OnboardingEvent.RecoveryPinBackspace) },
            enabled = !state.restoring && !state.signInNeeded,
        )
        SproutButton(
            text = stringResource(R.string.feature_onboarding_recovery_cancel),
            onClick = { onEvent(OnboardingEvent.CancelRecovery) },
            enabled = !state.restoring && !state.signInNeeded,
            style = SproutButtonStyle.Tertiary,
        )
        if (state.signInNeeded) {
            val context = LocalContext.current
            LaunchedEffect(Unit) { onEvent(OnboardingEvent.RecoverySignIn(context)) }
        }
    }
}

@Composable
private fun FailureText(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** [at] as a date and time in this phone's zone and locale ("20 November 2026, 13:00"). */
@Composable
private fun recoveryDateTime(at: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT).withLocale(locale)
    return java.time.Instant.ofEpochMilli(at.toEpochMilliseconds()).atZone(ZoneId.systemDefault()).format(formatter)
}

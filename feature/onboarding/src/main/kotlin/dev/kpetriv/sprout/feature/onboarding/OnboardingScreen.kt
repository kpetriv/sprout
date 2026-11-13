package dev.kpetriv.sprout.feature.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutButtonStyle
import dev.kpetriv.sprout.core.designsystem.components.SproutCard
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.ui.contentWidth

/**
 * Onboarding (M3 §6) backed by an [OnboardingViewModel].
 *
 * @param parentLock the app's PIN and fingerprint-signing step (`feature/lock`); it calls its argument when done.
 * @param onFinished the device is set up: the app leaves onboarding for the area its session allows.
 * @param onExit system back on the welcome screen, or "Not now" (shown only when non-null and [canLeave]).
 * @param canLeave there is somewhere to go back to (the trial household); otherwise back leaves the app.
 * @param onStarted the user left the welcome screen: from now on onboarding may save a session before it is
 *   finished, and the app must keep showing it until [onFinished].
 */
@Composable
fun OnboardingRoute(
    parentLock: @Composable (onDone: () -> Unit) -> Unit,
    onFinished: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    canLeave: Boolean = false,
    onStarted: () -> Unit = {},
) {
    val viewModel: OnboardingViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.finished) { if (state.finished) onFinished() }
    val atWelcome = state.step == OnboardingStep.Welcome
    LaunchedEffect(atWelcome) { if (!atWelcome) onStarted() }
    // While a call runs, system back is swallowed (the view model ignores Back then): a registration is never left
    // half-way, and the activity is not finished under it either.
    SystemBackHandler(enabled = !atWelcome || state.busy) { viewModel.onEvent(OnboardingEvent.Back) }
    SystemBackHandler(enabled = atWelcome && canLeave && !state.busy, onBack = onExit)
    if (state.step == OnboardingStep.ParentLock) {
        parentLock { viewModel.onEvent(OnboardingEvent.ParentLockDone) }
    } else {
        OnboardingScreen(
            state = state,
            onEvent = viewModel::onEvent,
            onNotNow = onExit.takeIf { canLeave },
            modifier = modifier,
        )
    }
}

/** The stateless onboarding screen for every step but [OnboardingStep.ParentLock], which the app draws. */
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
    modifier: Modifier = Modifier,
    onNotNow: (() -> Unit)? = null,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .contentWidth()
                .padding(horizontal = SproutSpacing.gutter, vertical = SproutSpacing.md),
            verticalArrangement = Arrangement.spacedBy(SproutSpacing.md),
        ) {
            val step = state.step
            if (step != OnboardingStep.Welcome && step != OnboardingStep.FirstSync) {
                // The arrow's glyph sits on the 20 dp gutter, as SproutTopBar's does.
                IconButton(
                    onClick = { onEvent(OnboardingEvent.Back) },
                    enabled = !state.busy,
                    modifier = Modifier.offset(x = -ArrowInset),
                ) {
                    Icon(SproutIcons.ArrowBack, contentDescription = stringResource(R.string.feature_onboarding_back))
                }
            }
            when (step) {
                OnboardingStep.Welcome -> Welcome(onEvent, onNotNow)
                is OnboardingStep.ParentSignIn -> ParentSignIn(step, state.busy, onEvent)
                is OnboardingStep.CreateHousehold -> CreateHousehold(step, state.busy, onEvent)
                is OnboardingStep.ConfirmPurge -> ConfirmPurge(step, state.busy, onEvent)
                is OnboardingStep.WaitingForApproval -> WaitingForApproval(step, onEvent)
                is OnboardingStep.KidCode -> KidCode(step, state.busy, state.failure != null, onEvent)
                OnboardingStep.FirstSync -> Working(R.string.feature_onboarding_first_sync)
                OnboardingStep.ParentLock -> Working(R.string.feature_onboarding_working)
            }
            if (state.busy && step != OnboardingStep.FirstSync) {
                LinearProgressIndicator(
                    trackColor = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.failure?.let { failure ->
                Text(
                    text = stringResource(failure.messageRes()),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Welcome(onEvent: (OnboardingEvent) -> Unit, onNotNow: (() -> Unit)?) {
    Spacer(Modifier.height(SproutSpacing.lg))
    SproutMark()
    Text(
        stringResource(R.string.feature_onboarding_title),
        style = MaterialTheme.typography.headlineLarge,
        modifier = Modifier.semantics { heading() },
    )
    Text(stringResource(R.string.feature_onboarding_welcome_subtitle), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(SproutSpacing.sm))
    Choice(
        title = stringResource(R.string.feature_onboarding_welcome_parent),
        detail = stringResource(R.string.feature_onboarding_welcome_parent_detail),
        icon = ImageVector.vectorResource(R.drawable.feature_onboarding_ic_group),
        onClick = { onEvent(OnboardingEvent.ChooseParent) },
    )
    Choice(
        title = stringResource(R.string.feature_onboarding_welcome_kid),
        detail = stringResource(R.string.feature_onboarding_welcome_kid_detail),
        icon = ImageVector.vectorResource(R.drawable.feature_onboarding_ic_smartphone),
        onClick = { onEvent(OnboardingEvent.ChooseKidDevice) },
    )
    if (onNotNow != null) {
        SproutButton(
            text = stringResource(R.string.feature_onboarding_welcome_not_now),
            onClick = onNotNow,
            style = SproutButtonStyle.Tertiary,
        )
    }
}

/** One way in, as a white card that is one button: an icon in a Leaf-tint circle, title, detail and a chevron. */
@Composable
private fun Choice(title: String, detail: String, icon: ImageVector, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    SproutCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md),
        ) {
            Box(
                modifier = Modifier.size(ChoiceIconSize).background(scheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = scheme.onPrimaryContainer)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SproutSpacing.xs)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            Icon(SproutIcons.ChevronRight, contentDescription = null, tint = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ParentSignIn(step: OnboardingStep.ParentSignIn, busy: Boolean, onEvent: (OnboardingEvent) -> Unit) {
    Title(stringResource(R.string.feature_onboarding_sign_in_title))
    if (step.emailPassword) {
        Text(
            stringResource(R.string.feature_onboarding_sign_in_emulator_detail),
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedTextField(
            value = step.email,
            onValueChange = { onEvent(OnboardingEvent.EmailChanged(it)) },
            label = { Text(stringResource(R.string.feature_onboarding_sign_in_email)) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            shape = MaterialTheme.shapes.small,
            colors = onboardingFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = step.password,
            onValueChange = { onEvent(OnboardingEvent.PasswordChanged(it)) },
            label = { Text(stringResource(R.string.feature_onboarding_sign_in_password)) },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onEvent(OnboardingEvent.SignInWithEmail) }),
            shape = MaterialTheme.shapes.small,
            colors = onboardingFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        SproutButton(
            text = stringResource(R.string.feature_onboarding_sign_in_submit),
            onClick = { onEvent(OnboardingEvent.SignInWithEmail) },
            enabled = !busy && step.canSubmit,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        Text(
            stringResource(R.string.feature_onboarding_sign_in_google_detail),
            style = MaterialTheme.typography.bodyLarge,
        )
        val context = LocalContext.current
        SproutButton(
            text = stringResource(R.string.feature_onboarding_sign_in_google),
            onClick = { onEvent(OnboardingEvent.SignInWithGoogle(context)) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateHousehold(step: OnboardingStep.CreateHousehold, busy: Boolean, onEvent: (OnboardingEvent) -> Unit) {
    Title(stringResource(R.string.feature_onboarding_create_title))
    Text(stringResource(R.string.feature_onboarding_create_detail), style = MaterialTheme.typography.bodyLarge)
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (!busy) expanded = it }) {
        OutlinedTextField(
            value = currencyLabel(step.currency),
            onValueChange = {},
            readOnly = true,
            enabled = !busy,
            label = { Text(stringResource(R.string.feature_onboarding_create_currency)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = MaterialTheme.shapes.small,
            colors = onboardingFieldColors(),
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            step.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(currencyLabel(option)) },
                    onClick = {
                        expanded = false
                        onEvent(OnboardingEvent.CurrencyChosen(option))
                    },
                )
            }
        }
    }
    SproutButton(
        text = stringResource(R.string.feature_onboarding_create_submit),
        onClick = { onEvent(OnboardingEvent.CreateHousehold) },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun currencyLabel(currency: Currency.Fiat): String {
    val name = runCatching { java.util.Currency.getInstance(currency.isoCode).displayName }.getOrNull()
    return if (name == null || name == currency.isoCode) currency.isoCode else "${currency.isoCode} · $name"
}

@Composable
private fun ConfirmPurge(step: OnboardingStep.ConfirmPurge, busy: Boolean, onEvent: (OnboardingEvent) -> Unit) {
    Title(stringResource(R.string.feature_onboarding_purge_title))
    Text(stringResource(R.string.feature_onboarding_purge_text), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(
            if (step.forKidDevice) {
                R.string.feature_onboarding_purge_detail_kid
            } else {
                R.string.feature_onboarding_purge_detail_parent
            },
        ),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Continuing erases the trial household on this phone: the destructive colours (M9 §4).
    DestructiveButton(
        text = stringResource(R.string.feature_onboarding_purge_continue),
        onClick = { onEvent(OnboardingEvent.ConfirmPurge) },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    SproutButton(
        text = stringResource(R.string.feature_onboarding_purge_cancel),
        onClick = { onEvent(OnboardingEvent.Back) },
        enabled = !busy,
        style = SproutButtonStyle.Tertiary,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun WaitingForApproval(step: OnboardingStep.WaitingForApproval, onEvent: (OnboardingEvent) -> Unit) {
    val recovery = step.recovery
    // The recovery PIN pad replaces the waiting text while it is open (ADR-0013 §3); approval polling goes on.
    val entry = (recovery as? RecoveryUi.Ready)?.pinEntry
    if (recovery is RecoveryUi.Ready && entry != null) {
        RecoveryPinPad(recovery, entry, onEvent)
        return
    }
    Title(stringResource(R.string.feature_onboarding_waiting_title))
    Text(stringResource(R.string.feature_onboarding_waiting_detail), style = MaterialTheme.typography.bodyLarge)
    // The fingerprint to compare with the parent's phone, on a Linen tile (as the parent's approval sheet shows it).
    Text(
        text = step.fingerprint,
        style = MaterialTheme.typography.headlineSmall,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(vertical = SproutSpacing.lg, horizontal = SproutSpacing.md),
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md - SproutSpacing.xs),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        Text(stringResource(R.string.feature_onboarding_waiting_progress), style = MaterialTheme.typography.bodyLarge)
    }
    SproutButton(
        text = stringResource(R.string.feature_onboarding_waiting_cancel),
        onClick = { onEvent(OnboardingEvent.Back) },
        enabled = (recovery as? RecoveryUi.Ready)?.let { !it.restoring && !it.signInNeeded } ?: true,
        style = SproutButtonStyle.Tertiary,
        modifier = Modifier.fillMaxWidth(),
    )
    if (recovery != null) RecoverySection(recovery, onEvent)
}

@Composable
private fun KidCode(step: OnboardingStep.KidCode, busy: Boolean, error: Boolean, onEvent: (OnboardingEvent) -> Unit) {
    Title(stringResource(R.string.feature_onboarding_code_title))
    Text(stringResource(R.string.feature_onboarding_code_detail), style = MaterialTheme.typography.bodyLarge)
    val label = stringResource(R.string.feature_onboarding_code_field)
    BasicTextField(
        value = step.code,
        onValueChange = { onEvent(OnboardingEvent.CodeChanged(it)) },
        enabled = !busy,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onEvent(OnboardingEvent.SubmitCode) }),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        decorationBox = { CodeCells(step.code, error) },
    )
    Text(
        stringResource(R.string.feature_onboarding_code_note),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SproutButton(
        text = stringResource(R.string.feature_onboarding_code_submit),
        onClick = { onEvent(OnboardingEvent.SubmitCode) },
        enabled = !busy && step.complete,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Eight Linen boxes in two groups of four; the next box to fill has a Botanical border, so typing advances box by
 * box. A refused code turns every border `error` (the failure line below says why).
 */
@Composable
private fun CodeCells(code: String, error: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(LinkCodeInput.LENGTH) { index ->
            if (index == LinkCodeInput.LENGTH / 2) Spacer(Modifier.width(16.dp))
            val active = index == code.length
            val border = when {
                error -> MaterialTheme.colorScheme.error
                active -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outline
            }
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(if (active) 2.dp else 1.dp, border),
                modifier = Modifier.padding(horizontal = 3.dp).size(width = 36.dp, height = 48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = code.getOrNull(index)?.toString().orEmpty(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun Working(@StringRes text: Int) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator()
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

private val ChoiceIconSize = 48.dp

/** How far IconButton's 48 dp box sits outside its 24 dp glyph, so the glyph lands on the gutter. */
private val ArrowInset = 12.dp

@StringRes
@Suppress("CyclomaticComplexMethod") // One string per failure.
internal fun OnboardingFailure.messageRes(): Int = when (this) {
    OnboardingFailure.NETWORK -> R.string.feature_onboarding_failure_network
    OnboardingFailure.INVALID_CREDENTIAL -> R.string.feature_onboarding_failure_invalid_credential
    OnboardingFailure.CANCELLED -> R.string.feature_onboarding_failure_cancelled
    OnboardingFailure.NO_GOOGLE_ACCOUNT -> R.string.feature_onboarding_failure_no_google_account
    OnboardingFailure.GOOGLE_NOT_CONFIGURED -> R.string.feature_onboarding_failure_google_not_configured
    OnboardingFailure.NOT_AVAILABLE -> R.string.feature_onboarding_failure_not_available
    OnboardingFailure.TOO_MANY_ATTEMPTS -> R.string.feature_onboarding_failure_too_many
    OnboardingFailure.ACCOUNT_DISABLED -> R.string.feature_onboarding_failure_disabled
    OnboardingFailure.CODE_NOT_ACCEPTED -> R.string.feature_onboarding_failure_code
    OnboardingFailure.NOT_ALLOWED -> R.string.feature_onboarding_failure_not_allowed
    OnboardingFailure.DEVICE_REJECTED -> R.string.feature_onboarding_failure_rejected
    OnboardingFailure.SIGN_IN_AGAIN -> R.string.feature_onboarding_failure_sign_in_again
    OnboardingFailure.WRONG_ACCOUNT -> R.string.feature_onboarding_failure_wrong_account
    OnboardingFailure.UNKNOWN -> R.string.feature_onboarding_failure_unknown
}

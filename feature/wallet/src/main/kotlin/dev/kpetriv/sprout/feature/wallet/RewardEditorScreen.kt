package dev.kpetriv.sprout.feature.wallet

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutChip
import dev.kpetriv.sprout.core.designsystem.components.SproutTopBar
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind
import dev.kpetriv.sprout.core.ui.MinorUnitsParser
import dev.kpetriv.sprout.core.ui.contentWidth

/** Navigation entry for adding ([rewardId] null) or editing a reward. [onDone] runs once the reward is saved. */
@Composable
fun RewardEditorRoute(rewardId: RewardId?, onDone: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val args = Bundle().apply { rewardId?.let { putString(RewardEditorViewModel.REWARD_ID_KEY, it.value) } }
    val viewModel = featureViewModel<RewardEditorViewModel>(
        key = "rewards:editor:${rewardId?.value ?: "new"}",
        args = args,
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(state.saved) { if (state.saved) done() }
    RewardEditorScreen(state = state, onEvent = viewModel::onEvent, onBack = onBack, modifier = modifier)
}

/**
 * The reward form, a pushed screen (back arrow) on parent tokens: name, kind as one-choice chips, price in the
 * household's currency, minutes (screen time only), who can buy it ("All kids" or chosen kids) and "Show in the shop".
 * Errors show under their field once the parent tries to save, and are announced. "Save" is the bar's Botanical pill.
 */
@Composable
fun RewardEditorScreen(
    state: RewardEditorUiState,
    onEvent: (RewardEditorEvent) -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    Scaffold(
        modifier = modifier,
        topBar = {
            SproutTopBar(
                title = stringResource(
                    if (state.isNew) {
                        R.string.feature_wallet_reward_editor_new
                    } else {
                        R.string.feature_wallet_reward_editor_edit
                    },
                ),
                onBack = onBack,
                trailing = {
                    if (!state.isLoading && !state.notFound) {
                        SproutButton(
                            text = stringResource(R.string.feature_wallet_reward_editor_save),
                            onClick = { onEvent(RewardEditorEvent.Save) },
                            enabled = !state.isSaving,
                            modifier = Modifier.padding(end = SproutSpacing.sm),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading -> RewardsLoading(stringResource(R.string.feature_wallet_rewards_loading))

                state.notFound -> Text(
                    text = stringResource(R.string.feature_wallet_reward_editor_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(SproutSpacing.lg),
                )

                else -> RewardForm(state, onEvent)
            }
        }
    }
    val failed = if (state.saveFailed) stringResource(R.string.feature_wallet_reward_editor_save_failed) else null
    val shown by rememberUpdatedState { onEvent(RewardEditorEvent.SaveFailedShown) }
    LaunchedEffect(state.saveFailed, failed) {
        if (failed != null) {
            snackbar.showSnackbar(failed)
            shown()
        }
    }
}

@Composable
private fun RewardForm(state: RewardEditorUiState, onEvent: (RewardEditorEvent) -> Unit) {
    val errors = state.errors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .contentWidth()
            .padding(horizontal = SproutSpacing.gutter, vertical = SproutSpacing.md),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.md),
    ) {
        OutlinedTextField(
            value = state.title,
            onValueChange = { onEvent(RewardEditorEvent.TitleChanged(it)) },
            label = { Text(stringResource(R.string.feature_wallet_reward_field_title)) },
            placeholder = { Text(stringResource(R.string.feature_wallet_reward_field_title_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            isError = errors.anyOf(TitleErrors),
            supportingText = errorSlot(errors, state, TitleErrors),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        KindPicker(state.kind, onEvent)
        OutlinedTextField(
            value = state.priceText,
            onValueChange = { onEvent(RewardEditorEvent.PriceChanged(it)) },
            label = { Text(stringResource(R.string.feature_wallet_reward_field_price, state.currency.isoCode)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = errors.anyOf(PriceErrors),
            supportingText = errorSlot(errors, state, PriceErrors),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.showsMinutes) {
            val minutesError = errorSlot(errors, state, MinutesErrors)
            OutlinedTextField(
                value = state.minutesText,
                onValueChange = { onEvent(RewardEditorEvent.MinutesChanged(it)) },
                label = { Text(stringResource(R.string.feature_wallet_reward_field_minutes)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = errors.anyOf(MinutesErrors),
                supportingText = minutesError
                    ?: { Text(stringResource(R.string.feature_wallet_reward_field_minutes_help)) },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AudiencePicker(state, onEvent)
        ActiveSwitch(state.active, onEvent)
    }
}

/** One kind of four, as radio chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KindPicker(kind: RewardKind, onEvent: (RewardEditorEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm)) {
        FieldHeading(stringResource(R.string.feature_wallet_reward_field_kind))
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
        ) {
            RewardKind.entries.forEach { option ->
                SproutChip(
                    selected = kind == option,
                    label = option.label(),
                    onClick = { onEvent(RewardEditorEvent.KindChanged(option)) },
                    role = Role.RadioButton,
                )
            }
        }
    }
}

/** "All kids" or "Chosen kids" (radio chips), then one checkbox chip per kid while choosing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudiencePicker(state: RewardEditorUiState, onEvent: (RewardEditorEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm)) {
        FieldHeading(stringResource(R.string.feature_wallet_reward_field_for))
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
        ) {
            SproutChip(
                selected = state.forAllKids,
                label = stringResource(R.string.feature_wallet_reward_for_all),
                onClick = { onEvent(RewardEditorEvent.AllKidsChanged(true)) },
                role = Role.RadioButton,
            )
            SproutChip(
                selected = !state.forAllKids,
                label = stringResource(R.string.feature_wallet_reward_for_chosen),
                onClick = { onEvent(RewardEditorEvent.AllKidsChanged(false)) },
                role = Role.RadioButton,
            )
        }
        if (!state.forAllKids) {
            if (state.kids.isEmpty()) {
                Text(
                    text = stringResource(R.string.feature_wallet_reward_no_kids_yet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
            ) {
                state.kids.forEach { kid ->
                    SproutChip(
                        selected = kid.id in state.chosenKids,
                        label = kid.name,
                        onClick = { onEvent(RewardEditorEvent.KidToggled(kid.id)) },
                        role = Role.Checkbox,
                    )
                }
            }
        }
        ErrorLine(state.errors, state, listOf(RewardEditorError.NO_KIDS_CHOSEN))
    }
}

/** "Show in the shop": the whole row is the switch. */
@Composable
private fun ActiveSwitch(active: Boolean, onEvent: (RewardEditorEvent) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .toggleable(
                value = active,
                role = Role.Switch,
                onValueChange = { onEvent(RewardEditorEvent.ActiveChanged(it)) },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SproutSpacing.xs)) {
            Text(
                text = stringResource(R.string.feature_wallet_reward_field_active),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    if (active) {
                        R.string.feature_wallet_reward_field_active_on
                    } else {
                        R.string.feature_wallet_reward_field_active_off
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = active, onCheckedChange = null)
    }
}

@Composable
private fun FieldHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
    )
}

private fun Set<RewardEditorError>.anyOf(which: List<RewardEditorError>): Boolean = which.any { it in this }

/** A supporting-text slot showing the first of [which] present in [errors], announced; null when none is. */
private fun errorSlot(
    errors: Set<RewardEditorError>,
    state: RewardEditorUiState,
    which: List<RewardEditorError>,
): (@Composable () -> Unit)? {
    val error = which.firstOrNull { it in errors } ?: return null
    return {
        val message = rewardErrorMessage(error, state)
        Text(
            text = message,
            modifier = Modifier.semantics {
                error(message)
                liveRegion = LiveRegionMode.Polite
            },
        )
    }
}

/** The first of [which] present in [errors] as an error line announced to screen readers. */
@Composable
private fun ErrorLine(errors: Set<RewardEditorError>, state: RewardEditorUiState, which: List<RewardEditorError>) {
    val error = which.firstOrNull { it in errors } ?: return
    val message = rewardErrorMessage(error, state)
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.semantics {
            error(message)
            liveRegion = LiveRegionMode.Polite
        },
    )
}

@Composable
internal fun rewardErrorMessage(error: RewardEditorError, state: RewardEditorUiState): String = when (error) {
    RewardEditorError.TITLE_BLANK -> stringResource(R.string.feature_wallet_reward_error_title_blank)

    RewardEditorError.TITLE_TOO_LONG -> stringResource(R.string.feature_wallet_reward_error_title_too_long)

    RewardEditorError.PRICE_MISSING -> stringResource(R.string.feature_wallet_reward_error_price_missing)

    RewardEditorError.PRICE_NOT_A_NUMBER -> stringResource(
        R.string.feature_wallet_reward_error_price_not_a_number,
        examplePrice(state.currency.fractionDigits),
    )

    RewardEditorError.PRICE_TOO_MANY_DECIMALS -> tooManyDecimalsMessage(state.currency.fractionDigits)

    RewardEditorError.PRICE_NOT_POSITIVE -> stringResource(R.string.feature_wallet_reward_error_price_not_positive)

    RewardEditorError.PRICE_TOO_HIGH -> stringResource(R.string.feature_wallet_reward_error_price_too_high)

    RewardEditorError.MINUTES_MISSING -> stringResource(R.string.feature_wallet_reward_error_minutes_missing)

    RewardEditorError.MINUTES_NOT_A_NUMBER -> stringResource(R.string.feature_wallet_reward_error_minutes_not_a_number)

    RewardEditorError.MINUTES_NOT_POSITIVE -> stringResource(R.string.feature_wallet_reward_error_minutes_not_positive)

    RewardEditorError.MINUTES_OVER_A_DAY -> stringResource(
        R.string.feature_wallet_reward_error_minutes_over_a_day,
        RewardEditorViewModel.MAX_SCREEN_TIME_MINUTES.toInt(),
    )

    RewardEditorError.NO_KIDS_CHOSEN -> stringResource(R.string.feature_wallet_reward_error_no_kids)
}

/** "Enter a whole price." for a currency without decimals, otherwise "Use at most 2 decimal places." */
@Composable
private fun tooManyDecimalsMessage(digits: Int): String = if (digits == 0) {
    stringResource(R.string.feature_wallet_reward_error_price_whole)
} else {
    pluralStringResource(R.plurals.feature_wallet_reward_error_price_decimals, digits, digits)
}

private val TitleErrors = listOf(RewardEditorError.TITLE_BLANK, RewardEditorError.TITLE_TOO_LONG)

private val PriceErrors = listOf(
    RewardEditorError.PRICE_MISSING,
    RewardEditorError.PRICE_NOT_A_NUMBER,
    RewardEditorError.PRICE_TOO_MANY_DECIMALS,
    RewardEditorError.PRICE_NOT_POSITIVE,
    RewardEditorError.PRICE_TOO_HIGH,
)

private val MinutesErrors = listOf(
    RewardEditorError.MINUTES_MISSING,
    RewardEditorError.MINUTES_NOT_A_NUMBER,
    RewardEditorError.MINUTES_NOT_POSITIVE,
    RewardEditorError.MINUTES_OVER_A_DAY,
)

/**
 * An example price as a parent types it in a currency with [fractionDigits] decimals: "2.50" for pounds, "2.5" with
 * one decimal, "250" for yen. Built from minor units, never a floating-point number.
 */
internal fun examplePrice(fractionDigits: Int): String {
    val minor = when (fractionDigits) {
        0 -> EXAMPLE_MINOR
        1 -> EXAMPLE_MINOR / DECIMAL_RADIX
        else -> (2 until fractionDigits).fold(EXAMPLE_MINOR) { acc, _ -> acc * DECIMAL_RADIX }
    }
    return MinorUnitsParser.toInputText(minor, fractionDigits)
}

private val MinTouchTarget = 48.dp

/** [examplePrice]'s digits: "2.50" in a currency with two decimals. */
private const val EXAMPLE_MINOR = 250L
private const val DECIMAL_RADIX = 10L

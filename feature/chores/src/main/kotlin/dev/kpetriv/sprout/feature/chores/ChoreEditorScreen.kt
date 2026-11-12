package dev.kpetriv.sprout.feature.chores

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutButtonStyle
import dev.kpetriv.sprout.core.designsystem.components.SproutTopBar
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.ui.contentWidth

/**
 * Navigation entry for creating ([choreId] null) or editing a chore. [kidId] preselects the kid of a new chore.
 * [onDone] runs once the chore is saved.
 */
@Composable
fun ChoreEditorRoute(
    choreId: ChoreId?,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    kidId: KidId? = null,
) {
    val args = Bundle().apply {
        choreId?.let { putString(ChoreEditorViewModel.CHORE_ID_KEY, it.value) }
        kidId?.let { putString(ChoreEditorViewModel.KID_ID_KEY, it.value) }
    }
    val viewModel = choresViewModel<ChoreEditorViewModel>(
        key = "chores:editor:${choreId?.value ?: "new"}:${kidId?.value.orEmpty()}",
        args = args,
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(state.saved) { if (state.saved) done() }
    ChoreEditorScreen(state = state, onEvent = viewModel::onEvent, onBack = onBack, modifier = modifier)
}

/**
 * The chore form, a pushed screen (back arrow) on parent tokens: kid and schedule as chips, title, description,
 * reward (money and/or minutes), with validation errors, and "Save" as the bar's Botanical pill. A new chore offers
 * "Suggest chores" once its kid is picked (M6 §3.2).
 */
@Composable
fun ChoreEditorScreen(
    state: ChoreEditorUiState,
    onEvent: (ChoreEditorEvent) -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    Scaffold(
        modifier = modifier,
        topBar = {
            SproutTopBar(
                title = stringResource(
                    if (state.isNew) R.string.feature_chores_editor_new else R.string.feature_chores_editor_edit,
                ),
                onBack = onBack,
                trailing = {
                    if (!state.isLoading && !state.notFound) {
                        SproutButton(
                            text = stringResource(R.string.feature_chores_save),
                            onClick = { onEvent(ChoreEditorEvent.Save) },
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
                state.isLoading -> LoadingContent()

                state.notFound -> Text(
                    text = stringResource(R.string.feature_chores_editor_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(SproutSpacing.lg),
                )

                else -> EditorForm(state, onEvent)
            }
        }
    }
    val failed = if (state.saveFailed) stringResource(R.string.feature_chores_editor_save_failed) else null
    MessageEffect(snackbar, state.saveFailed, failed) { onEvent(ChoreEditorEvent.SaveFailedShown) }
    state.suggestions?.let { sheet ->
        ChoreSuggestionsSheet(
            sheet = sheet,
            kidName = state.kids.firstOrNull { it.id == state.kidId }?.name,
            currency = state.currency,
            onEvent = onEvent,
        )
    }
}

@Composable
private fun EditorForm(state: ChoreEditorUiState, onEvent: (ChoreEditorEvent) -> Unit) {
    val errors = state.errors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .contentWidth()
            .padding(horizontal = SproutSpacing.gutter, vertical = SproutSpacing.md),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.md),
    ) {
        KidPicker(state, onEvent)
        if (state.isNew) {
            SproutButton(
                text = stringResource(R.string.feature_chores_suggest),
                onClick = { onEvent(ChoreEditorEvent.SuggestOpened) },
                enabled = state.canSuggest,
                style = SproutButtonStyle.Tertiary,
                icon = SproutIcons.AutoAwesome,
            )
        }
        OutlinedTextField(
            value = state.title,
            onValueChange = { onEvent(ChoreEditorEvent.TitleChanged(it)) },
            label = { Text(stringResource(R.string.feature_chores_field_title)) },
            singleLine = true,
            isError = EditorError.TITLE_BLANK in errors || EditorError.TITLE_TOO_LONG in errors,
            supportingText = errorText(errors, EditorError.TITLE_BLANK, EditorError.TITLE_TOO_LONG),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.description,
            onValueChange = { onEvent(ChoreEditorEvent.DescriptionChanged(it)) },
            label = { Text(stringResource(R.string.feature_chores_field_description)) },
            minLines = 2,
            isError = EditorError.DESCRIPTION_TOO_LONG in errors,
            supportingText = errorText(errors, EditorError.DESCRIPTION_TOO_LONG),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        RewardFields(state, onEvent)
        ScheduleEditor(state, onEvent)
    }
}

@Composable
private fun RewardFields(state: ChoreEditorUiState, onEvent: (ChoreEditorEvent) -> Unit) {
    val errors = state.errors
    val rewardMissing = EditorError.NO_REWARD in errors || EditorError.REWARD_NOT_POSITIVE in errors
    Column(verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm)) {
        SectionHeading(stringResource(R.string.feature_chores_field_reward))
        OutlinedTextField(
            value = state.moneyText,
            onValueChange = { onEvent(ChoreEditorEvent.MoneyChanged(it)) },
            label = { Text(stringResource(R.string.feature_chores_field_money, state.currency.isoCode)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = EditorError.MONEY_INVALID in errors || rewardMissing,
            supportingText = errorText(errors, EditorError.MONEY_INVALID),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.minutesText,
            onValueChange = { onEvent(ChoreEditorEvent.MinutesChanged(it)) },
            label = { Text(stringResource(R.string.feature_chores_field_minutes)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = EditorError.MINUTES_INVALID in errors || rewardMissing,
            supportingText = errorText(errors, EditorError.MINUTES_INVALID),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorLine(errors, EditorError.NO_REWARD, EditorError.REWARD_NOT_POSITIVE)
    }
}

/** A supporting-text slot showing the first of [which] present in [errors], or null. */
internal fun errorText(errors: Set<EditorError>, vararg which: EditorError): (@Composable () -> Unit)? {
    val error = which.firstOrNull { it in errors } ?: return null
    return { Text(editorErrorMessage(error)) }
}

/** The first of [which] present in [errors] as an error line announced to screen readers. */
@Composable
internal fun ErrorLine(errors: Set<EditorError>, vararg which: EditorError) {
    val error = which.firstOrNull { it in errors } ?: return
    val message = editorErrorMessage(error)
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
internal fun editorErrorMessage(error: EditorError): String = stringResource(
    when (error) {
        EditorError.NO_KID -> R.string.feature_chores_error_no_kid
        EditorError.TITLE_BLANK -> R.string.feature_chores_error_title_blank
        EditorError.TITLE_TOO_LONG -> R.string.feature_chores_error_title_too_long
        EditorError.DESCRIPTION_TOO_LONG -> R.string.feature_chores_error_description_too_long
        EditorError.MONEY_INVALID -> R.string.feature_chores_error_money_invalid
        EditorError.MINUTES_INVALID -> R.string.feature_chores_error_minutes_invalid
        EditorError.NO_REWARD -> R.string.feature_chores_error_no_reward
        EditorError.REWARD_NOT_POSITIVE -> R.string.feature_chores_error_reward_not_positive
        EditorError.NO_DAYS -> R.string.feature_chores_error_no_days
        EditorError.DUE_DATE_IN_PAST -> R.string.feature_chores_error_due_in_past
    },
)

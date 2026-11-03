package dev.kpetriv.sprout.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.domainhousehold.SavingsGoal
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.MAX_TITLE_LENGTH
import dev.kpetriv.sprout.core.ui.rememberMoneyFormatter

@Composable
internal fun SavingsGoalsRoute(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: SavingsGoalsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    SavingsGoalsScreen(state = state, onEvent = viewModel::onEvent, onBack = onBack, modifier = modifier)
}

/** Savings goals for the selected kid, with the add/edit sheet when [SavingsGoalsUiState.editor] is set. */
@Composable
fun SavingsGoalsScreen(
    state: SavingsGoalsUiState,
    onEvent: (SavingsGoalsEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    SettingsScaffold(
        title = stringResource(R.string.feature_settings_section_goals),
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbar,
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!loadStatus(state.isLoading, state.loadFailed)) {
                GoalsContent(state, onEvent)
            }
        }
    }
    MessageEffect(state.message, snackbar) { onEvent(SavingsGoalsEvent.MessageShown) }
    state.editor?.let { GoalEditorSheet(it, onEvent) }
}

@Composable
private fun GoalsContent(state: SavingsGoalsUiState, onEvent: (SavingsGoalsEvent) -> Unit) {
    if (state.kids.isEmpty()) {
        Text(stringResource(R.string.feature_settings_no_kids), modifier = Modifier.sectionPadding())
        return
    }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        KidChips(
            kids = state.kids,
            selected = state.selectedKidId,
            onSelect = { onEvent(SavingsGoalsEvent.SelectKid(it)) },
            modifier = Modifier.sectionPadding(),
        )
        if (state.goals.isEmpty()) {
            Text(stringResource(R.string.feature_settings_goals_empty), modifier = Modifier.sectionPadding())
        }
        val currency = state.currency
        state.goals.forEachIndexed { index, goal ->
            if (index > 0) HorizontalDivider()
            if (currency != null) GoalRow(goal, currency, onClick = { onEvent(SavingsGoalsEvent.EditGoal(goal.id)) })
        }
        Button(onClick = { onEvent(SavingsGoalsEvent.AddGoal) }, modifier = Modifier.sectionPadding()) {
            Text(stringResource(R.string.feature_settings_goals_add))
        }
    }
}

@Composable
private fun GoalRow(goal: SavingsGoal, currency: Currency, onClick: () -> Unit) {
    val formatter = rememberMoneyFormatter()
    ListItem(
        headlineContent = { Text(goal.title) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.feature_settings_goals_target, formatter.format(goal.target, currency)))
                goal.achievedOn?.let { Text(stringResource(R.string.feature_settings_goals_achieved, formatDate(it))) }
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalEditorSheet(editor: GoalEditorUi, onEvent: (SavingsGoalsEvent) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { onEvent(SavingsGoalsEvent.Dismiss) }, sheetState = sheetState) {
        GoalEditorForm(editor, onEvent, Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp))
    }
}

/** Title and target amount; delete for an existing goal. */
@Composable
internal fun GoalEditorForm(editor: GoalEditorUi, onEvent: (SavingsGoalsEvent) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeading(
            stringResource(
                if (editor.goalId == null) {
                    R.string.feature_settings_goals_add_title
                } else {
                    R.string.feature_settings_goals_edit_title
                },
            ),
        )
        val titleError = nameErrorText(editor.titleError, MAX_TITLE_LENGTH)
        OutlinedTextField(
            value = editor.title,
            onValueChange = { onEvent(SavingsGoalsEvent.TitleChanged(it)) },
            label = { Text(stringResource(R.string.feature_settings_goals_title_field)) },
            isError = titleError != null,
            supportingText = titleError?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val targetError = amountErrorText(editor.targetError)
        OutlinedTextField(
            value = editor.targetText,
            onValueChange = { onEvent(SavingsGoalsEvent.TargetChanged(it)) },
            label = { Text(stringResource(R.string.feature_settings_goals_target_field, editor.currency.isoCode)) },
            isError = targetError != null,
            supportingText = targetError?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (editor.saveFailed) {
            Text(stringResource(R.string.feature_settings_save_failed), color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SaveButton(editor.isSaving, enabled = true, onClick = { onEvent(SavingsGoalsEvent.Save) })
            TextButton(onClick = { onEvent(SavingsGoalsEvent.Dismiss) }) {
                Text(stringResource(R.string.feature_settings_cancel))
            }
        }
        if (editor.goalId != null) {
            TextButton(onClick = { onEvent(SavingsGoalsEvent.Delete) }, enabled = !editor.isSaving) {
                Text(stringResource(R.string.feature_settings_goals_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

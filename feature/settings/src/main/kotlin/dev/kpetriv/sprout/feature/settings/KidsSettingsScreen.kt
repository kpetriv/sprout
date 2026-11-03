package dev.kpetriv.sprout.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.MAX_FIRST_NAME_LENGTH
import java.text.DateFormatSymbols

@Composable
internal fun KidsSettingsRoute(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: KidsSettingsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    KidsSettingsScreen(state = state, onEvent = viewModel::onEvent, onBack = onBack, modifier = modifier)
}

/** Kids & tiers: the list, "Add kid", and the edit sheet when [KidsSettingsUiState.editor] is set. */
@Composable
fun KidsSettingsScreen(
    state: KidsSettingsUiState,
    onEvent: (KidsSettingsEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    SettingsScaffold(
        title = stringResource(R.string.feature_settings_section_kids),
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbar,
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!loadStatus(state.isLoading, state.loadFailed)) {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    if (state.kids.isEmpty()) {
                        Text(
                            stringResource(R.string.feature_settings_kids_empty),
                            modifier = Modifier.sectionPadding(),
                        )
                    }
                    state.kids.forEachIndexed { index, row ->
                        if (index > 0) HorizontalDivider()
                        KidRow(row, onClick = { onEvent(KidsSettingsEvent.EditKid(row.kid.id)) })
                    }
                    Button(
                        onClick = { onEvent(KidsSettingsEvent.AddKid) },
                        modifier = Modifier.sectionPadding(),
                    ) {
                        Text(stringResource(R.string.feature_settings_kids_add))
                    }
                }
            }
        }
    }
    MessageEffect(state.message, snackbar) { onEvent(KidsSettingsEvent.MessageShown) }
    state.editor?.let { editor ->
        KidEditorSheet(editor, onEvent)
    }
}

@Composable
private fun KidRow(row: KidRowUi, onClick: () -> Unit) {
    val tier = tierName(row.kid.uiTier)
    val tierText = if (row.isTierOverridden) {
        stringResource(R.string.feature_settings_kids_row_overridden, tier, tierName(row.suggestedTier))
    } else {
        tier
    }
    ListItem(
        headlineContent = { Text(row.kid.firstName) },
        supportingContent = { Text(stringResource(R.string.feature_settings_kids_row_detail, row.ageYears, tierText)) },
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KidEditorSheet(editor: KidEditorUi, onEvent: (KidsSettingsEvent) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { onEvent(KidsSettingsEvent.DismissEditor) }, sheetState = sheetState) {
        KidEditorForm(editor, onEvent, Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp))
    }
}

/** The kid form: first name, birth month and year, screen style (tier) with the suggestion, archive. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KidEditorForm(editor: KidEditorUi, onEvent: (KidsSettingsEvent) -> Unit, modifier: Modifier = Modifier) {
    var confirmArchive by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeading(
            if (editor.isNew) {
                stringResource(R.string.feature_settings_kids_add_title)
            } else {
                stringResource(R.string.feature_settings_kids_edit_title, editor.firstName.ifBlank { "…" })
            },
        )
        val nameError = nameErrorText(editor.nameError, MAX_FIRST_NAME_LENGTH)
        OutlinedTextField(
            value = editor.firstName,
            onValueChange = { onEvent(KidsSettingsEvent.NameChanged(it)) },
            label = { Text(stringResource(R.string.feature_settings_kids_first_name)) },
            isError = nameError != null,
            supportingText = nameError?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val months = remember { DateFormatSymbols.getInstance().months.take(MONTHS) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceDropdown(
                label = stringResource(R.string.feature_settings_kids_birth_month),
                options = (1..MONTHS).toList(),
                selected = editor.birthMonth,
                optionLabel = { months[it - 1] },
                onSelect = { onEvent(KidsSettingsEvent.BirthChanged(editor.birthYear, it)) },
                modifier = Modifier.weight(1f),
            )
            ChoiceDropdown(
                label = stringResource(R.string.feature_settings_kids_birth_year),
                options = editor.years,
                selected = editor.birthYear,
                optionLabel = { it.toString() },
                onSelect = { onEvent(KidsSettingsEvent.BirthChanged(it, editor.birthMonth)) },
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = stringResource(
                if (editor.birthInFuture) {
                    R.string.feature_settings_kids_birth_future
                } else {
                    R.string.feature_settings_kids_birth_note
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (editor.birthInFuture) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(stringResource(R.string.feature_settings_kids_tier), style = MaterialTheme.typography.labelMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            KidUiTier.entries.forEachIndexed { index, tier ->
                SegmentedButton(
                    selected = editor.tier == tier,
                    onClick = { onEvent(KidsSettingsEvent.TierChosen(tier)) },
                    shape = SegmentedButtonDefaults.itemShape(index, KidUiTier.entries.size),
                ) {
                    Text(tierName(tier))
                }
            }
        }
        editor.suggestedTier?.let { suggested ->
            Text(
                stringResource(R.string.feature_settings_kids_tier_suggested, tierName(suggested)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (editor.saveFailed) {
            Text(
                stringResource(R.string.feature_settings_save_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SaveButton(editor.isSaving, enabled = true, onClick = { onEvent(KidsSettingsEvent.Save) })
            TextButton(onClick = { onEvent(KidsSettingsEvent.DismissEditor) }) {
                Text(stringResource(R.string.feature_settings_cancel))
            }
        }
        if (!editor.isNew) {
            TextButton(onClick = { confirmArchive = true }, enabled = !editor.isSaving) {
                Text(stringResource(R.string.feature_settings_kids_archive), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmArchive) {
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text(stringResource(R.string.feature_settings_kids_archive_confirm_title, editor.firstName)) },
            text = { Text(stringResource(R.string.feature_settings_kids_archive_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmArchive = false
                        onEvent(KidsSettingsEvent.Archive)
                    },
                ) { Text(stringResource(R.string.feature_settings_kids_archive_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmArchive = false }) {
                    Text(stringResource(R.string.feature_settings_cancel))
                }
            },
        )
    }
}

private const val MONTHS = 12

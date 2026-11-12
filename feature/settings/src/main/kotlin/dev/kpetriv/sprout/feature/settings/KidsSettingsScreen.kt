package dev.kpetriv.sprout.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.components.InitialAvatar
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.MAX_FIRST_NAME_LENGTH
import java.text.DateFormatSymbols

@Composable
internal fun KidsSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onLinkDevice: ((KidId, String) -> Unit)? = null,
) {
    val viewModel: KidsSettingsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    KidsSettingsScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
        onLinkDevice = onLinkDevice,
    )
}

/**
 * Kids & tiers: the list, "Add kid", and the edit sheet when [KidsSettingsUiState.editor] is set. With
 * [onLinkDevice] (a synced household) the sheet of an existing kid offers "Link a device".
 */
@Composable
fun KidsSettingsScreen(
    state: KidsSettingsUiState,
    onEvent: (KidsSettingsEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onLinkDevice: ((KidId, String) -> Unit)? = null,
) {
    val snackbar = remember { SnackbarHostState() }
    SettingsScaffold(
        title = stringResource(R.string.feature_settings_section_kids),
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbar,
    ) { padding ->
        if (!loadStatus(state.isLoading, state.loadFailed, Modifier.padding(padding))) {
            SettingsColumn(padding = padding, spacing = SproutSpacing.rowGap) {
                if (state.kids.isEmpty()) {
                    Text(
                        stringResource(R.string.feature_settings_kids_empty),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                state.kids.forEachIndexed { index, row ->
                    KidRow(row, index, onClick = { onEvent(KidsSettingsEvent.EditKid(row.kid.id)) })
                }
                SproutButton(
                    text = stringResource(R.string.feature_settings_kids_add),
                    onClick = { onEvent(KidsSettingsEvent.AddKid) },
                    icon = SproutIcons.Add,
                    modifier = Modifier.padding(top = SproutSpacing.sm),
                )
            }
        }
    }
    MessageEffect(state.message, snackbar) { onEvent(KidsSettingsEvent.MessageShown) }
    state.editor?.let { editor ->
        val kidId = editor.kidId
        val link = if (onLinkDevice != null && kidId != null) {
            {
                onEvent(KidsSettingsEvent.DismissEditor)
                onLinkDevice(kidId, editor.firstName)
            }
        } else {
            null
        }
        KidEditorSheet(editor, onEvent, link)
    }
}

@Composable
private fun KidRow(row: KidRowUi, index: Int, onClick: () -> Unit) {
    val tier = tierName(row.kid.uiTier)
    val tierText = if (row.isTierOverridden) {
        stringResource(R.string.feature_settings_kids_row_overridden, tier, tierName(row.suggestedTier))
    } else {
        tier
    }
    SettingsRow(
        title = row.kid.firstName,
        summary = stringResource(R.string.feature_settings_kids_row_detail, row.ageYears, tierText),
        onClick = onClick,
        leading = {
            InitialAvatar(
                initial = row.kid.firstName.take(1).uppercase(),
                tintIndex = index,
                size = 40.dp,
                modifier = Modifier.clearAndSetSemantics {},
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KidEditorSheet(editor: KidEditorUi, onEvent: (KidsSettingsEvent) -> Unit, onLinkDevice: (() -> Unit)?) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { onEvent(KidsSettingsEvent.DismissEditor) },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        KidEditorForm(editor, onEvent, Modifier.sheetPadding(), onLinkDevice = onLinkDevice)
    }
}

/** The kid form: first name, birth month and year, screen style (tier) with the suggestion, archive. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KidEditorForm(
    editor: KidEditorUi,
    onEvent: (KidsSettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
    onLinkDevice: (() -> Unit)? = null,
) {
    var confirmArchive by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.md),
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
            shape = MaterialTheme.shapes.small,
            colors = settingsFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        val months = remember { DateFormatSymbols.getInstance().months.take(MONTHS) }
        Row(horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md - SproutSpacing.xs)) {
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
        Text(stringResource(R.string.feature_settings_kids_tier), style = MaterialTheme.typography.labelLarge)
        RadioChips(
            options = KidUiTier.entries,
            selected = editor.tier,
            optionLabel = { tierName(it) },
            onSelect = { onEvent(KidsSettingsEvent.TierChosen(it)) },
        )
        editor.suggestedTier?.let { suggested ->
            Text(
                stringResource(R.string.feature_settings_kids_tier_suggested, tierName(suggested)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (editor.saveFailed) ErrorText(stringResource(R.string.feature_settings_save_failed))
        ButtonRow(alignment = Alignment.Start) {
            SaveButton(editor.isSaving, enabled = true, onClick = { onEvent(KidsSettingsEvent.Save) })
            QuietButton(
                text = stringResource(R.string.feature_settings_cancel),
                onClick = { onEvent(KidsSettingsEvent.DismissEditor) },
            )
        }
        if (!editor.isNew && onLinkDevice != null) {
            QuietButton(
                text = stringResource(R.string.feature_settings_kids_link),
                onClick = onLinkDevice,
                enabled = !editor.isSaving,
                icon = ImageVector.vectorResource(R.drawable.feature_settings_ic_link),
            )
        }
        if (!editor.isNew) {
            DestructiveButton(
                text = stringResource(R.string.feature_settings_kids_archive),
                onClick = { confirmArchive = true },
                enabled = !editor.isSaving,
                style = DestructiveStyle.Outlined,
            )
        }
    }
    if (confirmArchive) {
        ConfirmDialog(
            title = stringResource(R.string.feature_settings_kids_archive_confirm_title, editor.firstName),
            text = stringResource(R.string.feature_settings_kids_archive_confirm_text),
            onDismiss = { confirmArchive = false },
            confirm = {
                DestructiveButton(
                    text = stringResource(R.string.feature_settings_kids_archive_confirm),
                    onClick = {
                        confirmArchive = false
                        onEvent(KidsSettingsEvent.Archive)
                    },
                )
            },
        )
    }
}

private const val MONTHS = 12

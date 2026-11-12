package dev.kpetriv.sprout.feature.chores

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutButtonStyle
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.MAX_NOTE_LENGTH

/** The Sprout / Sapling submission sheet: an optional note, an optional photo, and Send. */
@Composable
internal fun SubmitForm(
    sheet: SubmitSheetUi,
    onEvent: (KidTodayEvent) -> Unit,
    onAddPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SproutSpacing.md)) {
        SectionHeading(text = sheet.title, style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = sheet.note,
            onValueChange = { onEvent(KidTodayEvent.NoteChanged(it)) },
            label = { Text(stringResource(R.string.feature_chores_submit_note)) },
            supportingText = {
                Text(stringResource(R.string.feature_chores_counter, sheet.note.length, MAX_NOTE_LENGTH))
            },
            minLines = 2,
            enabled = !sheet.isSubmitting,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        if (sheet.hasPhoto) {
            PhotoAdded(enabled = !sheet.isSubmitting, onRemove = { onEvent(KidTodayEvent.RemovePhoto) })
        } else {
            SproutButton(
                text = stringResource(R.string.feature_chores_add_photo),
                onClick = onAddPhoto,
                enabled = !sheet.isSubmitting,
                style = SproutButtonStyle.Tertiary,
                icon = SproutIcons.Add,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SproutButton(
            text = stringResource(R.string.feature_chores_send),
            onClick = { onEvent(KidTodayEvent.ConfirmSubmit) },
            enabled = !sheet.isSubmitting,
            icon = SproutIcons.Send,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** "Photo added" with a check on Leaf tint, and "Remove photo" under it. */
@Composable
private fun PhotoAdded(enabled: Boolean, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm)) {
        StatusPill(
            text = stringResource(R.string.feature_chores_photo_added),
            tone = StatusTone.Done,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        SproutButton(
            text = stringResource(R.string.feature_chores_remove_photo),
            onClick = onRemove,
            enabled = enabled,
            style = SproutButtonStyle.Tertiary,
            icon = SproutIcons.Close,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Sapling's "Propose a chore" sheet: a title and a suggested reward (money and/or minutes). */
@Composable
internal fun ProposeForm(
    form: ProposeSheetUi,
    currency: Currency.Fiat,
    onEvent: (KidTodayEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val errors = form.errors
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm)) {
        SectionHeading(
            text = stringResource(R.string.feature_chores_propose_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.feature_chores_propose_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = form.title,
            onValueChange = { onEvent(KidTodayEvent.ProposeTitleChanged(it)) },
            label = { Text(stringResource(R.string.feature_chores_field_title)) },
            singleLine = true,
            isError = EditorError.TITLE_BLANK in errors || EditorError.TITLE_TOO_LONG in errors,
            supportingText = errorText(errors, EditorError.TITLE_BLANK, EditorError.TITLE_TOO_LONG),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.moneyText,
            onValueChange = { onEvent(KidTodayEvent.ProposeMoneyChanged(it)) },
            label = { Text(stringResource(R.string.feature_chores_field_money, currency.isoCode)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = EditorError.MONEY_INVALID in errors,
            supportingText = errorText(errors, EditorError.MONEY_INVALID),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.minutesText,
            onValueChange = { onEvent(KidTodayEvent.ProposeMinutesChanged(it)) },
            label = { Text(stringResource(R.string.feature_chores_field_minutes)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = EditorError.MINUTES_INVALID in errors,
            supportingText = errorText(errors, EditorError.MINUTES_INVALID),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorLine(errors, EditorError.NO_REWARD, EditorError.REWARD_NOT_POSITIVE)
        SproutButton(
            text = stringResource(R.string.feature_chores_propose_send),
            onClick = { onEvent(KidTodayEvent.ConfirmPropose) },
            enabled = !form.isSubmitting,
            icon = SproutIcons.Send,
            modifier = Modifier.fillMaxWidth().padding(top = SproutSpacing.sm),
        )
    }
}

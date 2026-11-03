package dev.kpetriv.sprout.feature.chores

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.MAX_NOTE_LENGTH
import dev.kpetriv.sprout.core.ui.TierTextRole
import dev.kpetriv.sprout.core.ui.tierTextStyle

/** The Sprout / Sapling submission sheet: an optional note, an optional photo, and Send. */
@Composable
internal fun SubmitForm(
    sheet: SubmitSheetUi,
    tier: KidUiTier,
    onEvent: (KidTodayEvent) -> Unit,
    onAddPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val touch = SproutTheme.tierTokens.minTouchTarget
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeading(text = sheet.title, style = tierTextStyle(TierTextRole.Title, tier))
        OutlinedTextField(
            value = sheet.note,
            onValueChange = { onEvent(KidTodayEvent.NoteChanged(it)) },
            label = { Text(stringResource(R.string.feature_chores_submit_note)) },
            supportingText = {
                Text(stringResource(R.string.feature_chores_counter, sheet.note.length, MAX_NOTE_LENGTH))
            },
            minLines = 2,
            enabled = !sheet.isSubmitting,
            modifier = Modifier.fillMaxWidth(),
        )
        if (sheet.hasPhoto) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "📷 " + stringResource(R.string.feature_chores_photo_added),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onEvent(KidTodayEvent.RemovePhoto) },
                    enabled = !sheet.isSubmitting,
                    modifier = Modifier.heightIn(min = touch),
                ) { Text(stringResource(R.string.feature_chores_remove_photo)) }
            }
        } else {
            FilledTonalButton(
                onClick = onAddPhoto,
                enabled = !sheet.isSubmitting,
                modifier = Modifier.fillMaxWidth().heightIn(min = touch),
            ) { Text(stringResource(R.string.feature_chores_add_photo)) }
        }
        Button(
            onClick = { onEvent(KidTodayEvent.ConfirmSubmit) },
            enabled = !sheet.isSubmitting,
            modifier = Modifier.fillMaxWidth().heightIn(min = touch),
        ) { Text(stringResource(R.string.feature_chores_send)) }
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
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeading(
            text = stringResource(R.string.feature_chores_propose_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(stringResource(R.string.feature_chores_propose_hint), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = form.title,
            onValueChange = { onEvent(KidTodayEvent.ProposeTitleChanged(it)) },
            label = { Text(stringResource(R.string.feature_chores_field_title)) },
            singleLine = true,
            isError = EditorError.TITLE_BLANK in errors || EditorError.TITLE_TOO_LONG in errors,
            supportingText = errorText(errors, EditorError.TITLE_BLANK, EditorError.TITLE_TOO_LONG),
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
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorLine(errors, EditorError.NO_REWARD, EditorError.REWARD_NOT_POSITIVE)
        Button(
            onClick = { onEvent(KidTodayEvent.ConfirmPropose) },
            enabled = !form.isSubmitting,
            modifier = Modifier.fillMaxWidth().heightIn(min = SproutTheme.tierTokens.minTouchTarget),
        ) { Text(stringResource(R.string.feature_chores_propose_send)) }
    }
}

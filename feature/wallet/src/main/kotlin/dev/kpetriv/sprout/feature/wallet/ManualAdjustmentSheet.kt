package dev.kpetriv.sprout.feature.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.ui.MinorUnitsParser

/** Parent-only sheet for a manual credit or debit on the kid's main money account. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManualAdjustmentSheet(
    adjustment: AdjustmentUi,
    onSubmit: (amountMinor: Long, sign: AdjustmentSign, reason: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ManualAdjustmentForm(
            adjustment = adjustment,
            onSubmit = onSubmit,
            onCancel = onDismiss,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        )
    }
}

/**
 * The sheet's form. The amount is typed as text and parsed straight into minor units with [MinorUnitsParser]
 * (never through a floating-point number) using the currency's fraction digits.
 */
@Composable
internal fun ManualAdjustmentForm(
    adjustment: AdjustmentUi,
    onSubmit: (amountMinor: Long, sign: AdjustmentSign, reason: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var amountText by rememberSaveable { mutableStateOf("") }
    var sign by rememberSaveable { mutableStateOf(AdjustmentSign.CREDIT) }
    var reason by rememberSaveable { mutableStateOf("") }
    val digits = adjustment.currency.fractionDigits
    val parsed = remember(amountText, digits) { MinorUnitsParser.parse(amountText, digits) }
    val amountMinor = (parsed as? MinorUnitsParser.Result.Valid)?.minorUnits
    val amountError = amountErrorText(parsed, digits)
    val canSubmit = amountMinor != null && amountMinor > 0 && reason.isNotBlank() && !adjustment.isSubmitting

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.feature_wallet_adjust),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            AdjustmentSign.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = sign == option,
                    onClick = { sign = option },
                    shape = SegmentedButtonDefaults.itemShape(index, AdjustmentSign.entries.size),
                ) {
                    Text(
                        stringResource(
                            when (option) {
                                AdjustmentSign.CREDIT -> R.string.feature_wallet_adjust_credit
                                AdjustmentSign.DEBIT -> R.string.feature_wallet_adjust_debit
                            },
                        ),
                    )
                }
            }
        }
        OutlinedTextField(
            value = amountText,
            onValueChange = { input -> if (input.isAmountInput()) amountText = input },
            label = { Text(stringResource(R.string.feature_wallet_adjust_amount)) },
            suffix = { Text(adjustment.currency.isoCode) },
            isError = amountError != null,
            supportingText = amountError?.let { { Text(it) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = reason,
            onValueChange = { if (it.length <= MAX_REASON_LENGTH) reason = it },
            label = { Text(stringResource(R.string.feature_wallet_adjust_reason)) },
            placeholder = { Text(stringResource(R.string.feature_wallet_adjust_reason_hint)) },
            maxLines = REASON_MAX_LINES,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (adjustment.unsignedWarning) {
            Text(
                text = stringResource(R.string.feature_wallet_adjust_unsigned_warning),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        adjustment.error?.let { error ->
            Text(
                text = error.message(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.feature_wallet_adjust_cancel)) }
            Button(
                onClick = { if (amountMinor != null) onSubmit(amountMinor, sign, reason.trim()) },
                enabled = canSubmit,
            ) {
                if (adjustment.isSubmitting) {
                    val saving = stringResource(R.string.feature_wallet_adjust_saving)
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(18.dp)
                            .semantics { contentDescription = saving },
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.feature_wallet_adjust_submit))
                }
            }
        }
    }
}

/** Inline error under the amount field; null while the amount is empty or fine. */
@Composable
private fun amountErrorText(parsed: MinorUnitsParser.Result, fractionDigits: Int): String? = when (parsed) {
    is MinorUnitsParser.Result.Invalid -> parsed.error.message(fractionDigits)

    is MinorUnitsParser.Result.Valid ->
        if (parsed.minorUnits == 0L) stringResource(R.string.feature_wallet_amount_zero) else null

    MinorUnitsParser.Result.Empty -> null
}

/** Typing filter: ASCII digits and separators only, so no signs or exponents reach the parser. */
private fun String.isAmountInput(): Boolean =
    length <= MAX_AMOUNT_LENGTH && all { it in '0'..'9' || it == '.' || it == ',' }

private const val MAX_AMOUNT_LENGTH = 15
private const val MAX_REASON_LENGTH = 200
private const val REASON_MAX_LINES = 3

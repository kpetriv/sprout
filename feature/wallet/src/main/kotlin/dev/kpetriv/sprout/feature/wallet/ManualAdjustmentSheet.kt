package dev.kpetriv.sprout.feature.wallet

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutButtonStyle
import dev.kpetriv.sprout.core.designsystem.components.SproutChip
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.ui.MinorUnitsParser

/**
 * Parent-only sheet for a manual credit or debit on the kid's main money account. "Scan a receipt" opens the system
 * photo picker; the picked image goes to [onReceiptPicked] and the result comes back as [AdjustmentUi.prefill].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManualAdjustmentSheet(
    adjustment: AdjustmentUi,
    onSubmit: (amountMinor: Long, sign: AdjustmentSign, reason: String) -> Unit,
    onDismiss: () -> Unit,
    onReceiptPicked: (uri: String) -> Unit,
    onPrefillApplied: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pickReceipt = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { onReceiptPicked(it.toString()) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        ManualAdjustmentForm(
            adjustment = adjustment,
            onSubmit = onSubmit,
            onCancel = onDismiss,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            // D-M6-51: no scan button while the On-device AI switch is off.
            onScanReceipt = if (adjustment.canScan) {
                { pickReceipt.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            } else {
                null
            },
            onPrefillApplied = onPrefillApplied,
        )
    }
}

/**
 * The sheet's form. The amount is typed as text and parsed straight into minor units with [MinorUnitsParser]
 * (never through a floating-point number) using the currency's fraction digits.
 *
 * With [onScanReceipt] the form offers "Scan a receipt" and says that the reason is stored as plain text; a
 * [AdjustmentUi.prefill] then fills the amount as a debit and the reason "Receipt: <merchant>" (at most
 * [MAX_REASON_LENGTH] characters) once, and [onPrefillApplied] tells the ViewModel it was taken. A field the parent
 * changed after tapping "Scan a receipt" keeps the parent's value (audit L5): the amount, the reason, and the sign
 * (a sign the parent tapped is never forced to a debit). Save is disabled while a receipt is being read.
 */
@Suppress("CyclomaticComplexMethod") // Field state, the receipt prefill and the parent's edits, each a flat branch.
@Composable
internal fun ManualAdjustmentForm(
    adjustment: AdjustmentUi,
    onSubmit: (amountMinor: Long, sign: AdjustmentSign, reason: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onScanReceipt: (() -> Unit)? = null,
    onPrefillApplied: () -> Unit = {},
) {
    var amountText by rememberSaveable { mutableStateOf("") }
    var sign by rememberSaveable { mutableStateOf(AdjustmentSign.CREDIT) }
    var reason by rememberSaveable { mutableStateOf("") }
    // What the parent changed since tapping "Scan a receipt": a late prefill leaves those fields alone (audit L5).
    var amountEdited by rememberSaveable { mutableStateOf(false) }
    var signEdited by rememberSaveable { mutableStateOf(false) }
    var reasonEdited by rememberSaveable { mutableStateOf(false) }
    val digits = adjustment.currency.fractionDigits
    PrefillEffect(adjustment, onPrefillApplied) { amount, receiptReason ->
        if (!amountEdited) amount?.let { amountText = it }
        if (!signEdited) sign = AdjustmentSign.DEBIT
        if (!reasonEdited) reason = receiptReason
    }
    val scan = onScanReceipt?.let { pick ->
        {
            amountEdited = false
            signEdited = false
            reasonEdited = false
            pick()
        }
    }
    val parsed = remember(amountText, digits) { MinorUnitsParser.parse(amountText, digits) }
    val amountMinor = (parsed as? MinorUnitsParser.Result.Valid)?.minorUnits
    val amountError = amountErrorText(parsed, digits)
    val canSubmit = amountMinor != null && amountMinor > 0 && reason.isNotBlank() && !adjustment.isSubmitting &&
        !adjustment.scanning

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.md),
    ) {
        Text(
            text = stringResource(R.string.feature_wallet_adjust),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        ScanReceipt(adjustment, scan)
        // One choice of two, announced as radio buttons (as the segmented buttons were).
        Row(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
        ) {
            AdjustmentSign.entries.forEach { option ->
                SproutChip(
                    selected = sign == option,
                    role = Role.RadioButton,
                    label = stringResource(
                        when (option) {
                            AdjustmentSign.CREDIT -> R.string.feature_wallet_adjust_credit
                            AdjustmentSign.DEBIT -> R.string.feature_wallet_adjust_debit
                        },
                    ),
                    onClick = {
                        sign = option
                        signEdited = true // Even the one already selected: the parent chose it.
                    },
                )
            }
        }
        OutlinedTextField(
            value = amountText,
            onValueChange = { input ->
                if (input.isAmountInput()) {
                    amountText = input
                    amountEdited = true
                }
            },
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
            onValueChange = {
                if (it.length <= MAX_REASON_LENGTH) {
                    reason = it
                    reasonEdited = true
                }
            },
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
            horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (adjustment.isSubmitting) {
                val saving = stringResource(R.string.feature_wallet_adjust_saving)
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(18.dp)
                        .semantics { contentDescription = saving },
                    strokeWidth = 2.dp,
                )
            }
            SproutButton(
                text = stringResource(R.string.feature_wallet_adjust_cancel),
                onClick = onCancel,
                style = SproutButtonStyle.Tertiary,
            )
            SproutButton(
                text = stringResource(R.string.feature_wallet_adjust_submit),
                onClick = { if (amountMinor != null) onSubmit(amountMinor, sign, reason.trim()) },
                enabled = canSubmit,
            )
        }
    }
}

/**
 * Applies [AdjustmentUi.prefill] once: [apply] gets the amount as input text (null when the receipt had no positive
 * total, so the typed amount stays) and the receipt reason; then [onApplied] clears the prefill in the ViewModel.
 */
@Composable
private fun PrefillEffect(
    adjustment: AdjustmentUi,
    onApplied: () -> Unit,
    apply: (amountText: String?, reason: String) -> Unit,
) {
    val prefill = adjustment.prefill ?: return
    val reason = receiptReason(prefill.merchant)
    val digits = adjustment.currency.fractionDigits
    LaunchedEffect(prefill) {
        val amount = prefill.amount?.minorUnits?.takeIf { it > 0 }?.let { MinorUnitsParser.toInputText(it, digits) }
        apply(amount, reason)
        onApplied()
    }
}

/** "Scan a receipt", its plain-text note (PRIVACY.md) and the last scan's outcome; hidden without [onScan]. */
@Composable
private fun ScanReceipt(adjustment: AdjustmentUi, onScan: (() -> Unit)?) {
    val onScanReceipt = onScan ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SproutButton(
                text = stringResource(R.string.feature_wallet_receipt_scan),
                onClick = onScanReceipt,
                style = SproutButtonStyle.Tertiary,
                enabled = !adjustment.scanning && !adjustment.isSubmitting,
            )
            if (adjustment.scanning) {
                val reading = stringResource(R.string.feature_wallet_receipt_scanning)
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(16.dp)
                        .semantics {
                            contentDescription = reading
                            liveRegion = LiveRegionMode.Polite // Announced, as chat's check is (review L13).
                        },
                    strokeWidth = 2.dp,
                )
            }
        }
        if (adjustment.scanning && adjustment.scanSlow) {
            // D-M6-54: the first scan on a device waits for Play services to load its text recognition.
            Text(
                text = stringResource(R.string.feature_wallet_receipt_preparing),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Text(
            text = stringResource(R.string.feature_wallet_receipt_note),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        if (adjustment.scanFailed) {
            Text(
                text = stringResource(R.string.feature_wallet_receipt_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** "Receipt: Corner Shop" ("Receipt" without a merchant), cut to the reason's limit without splitting a character. */
@Composable
private fun receiptReason(merchant: String?): String {
    val text = merchant?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { stringResource(R.string.feature_wallet_receipt_reason, it) }
        ?: stringResource(R.string.feature_wallet_receipt_reason_no_merchant)
    return text.truncatedTo(MAX_REASON_LENGTH)
}

/** This text cut to at most [maxChars] UTF-16 units without leaving half a surrogate pair. */
internal fun String.truncatedTo(maxChars: Int): String {
    if (length <= maxChars) return this
    val end = if (maxChars > 0 && Character.isHighSurrogate(this[maxChars - 1])) maxChars - 1 else maxChars
    return substring(0, end)
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
internal const val MAX_REASON_LENGTH = 200
private const val REASON_MAX_LINES = 3

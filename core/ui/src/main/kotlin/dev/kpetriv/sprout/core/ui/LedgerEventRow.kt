package dev.kpetriv.sprout.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.KidUiTier

/**
 * One ledger entry: [icon] (an emoji glyph, decorative), [title], optional [subtitle] and the amount.
 *
 * Wording follows [tier]: Seedling reads "You got 3 coins" / "You used 30 min" with no minus signs and no
 * decimals; Sprout and Sapling show signed amounts ("+£1.50", "-£2.00"; transfers unsigned).
 *
 * @param amount the magnitude moved; the sign comes from [direction].
 */
@Composable
fun LedgerEventRow(
    icon: String,
    title: String,
    amount: Money,
    currency: Currency,
    direction: AmountDirection,
    tier: KidUiTier,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    formatter: MoneyFormatter = rememberMoneyFormatter(),
) {
    val magnitude = if (amount.isNegative) -amount else amount
    val amountText = ledgerAmountText(magnitude, currency, direction, tier, formatter)
    val description = stringResource(
        R.string.core_ui_event_row_description,
        listOfNotNull(title, subtitle).joinToString(", "),
        amountText,
    )
    val amountColor = when (direction) {
        AmountDirection.IN -> MaterialTheme.colorScheme.primary
        AmountDirection.OUT -> MaterialTheme.colorScheme.tertiary
        AmountDirection.MOVE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = SproutTheme.tierTokens.minTouchTarget)
            .clearAndSetSemantics { contentDescription = description }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = icon, style = MaterialTheme.typography.titleMedium)
        }
        Column(modifier = Modifier.weight(1f)) {
            TierScaledText(text = title, role = TierTextRole.Body, tier = tier)
            if (subtitle != null) {
                TierScaledText(
                    text = subtitle,
                    role = TierTextRole.Label,
                    tier = tier,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TierScaledText(
            text = amountText,
            role = TierTextRole.Body,
            tier = tier,
            color = amountColor,
            textAlign = TextAlign.End,
        )
    }
}

/** The amount column of [LedgerEventRow]: plain words on Seedling, signed figures otherwise. */
@Composable
fun ledgerAmountText(
    magnitude: Money,
    currency: Currency,
    direction: AmountDirection,
    tier: KidUiTier,
    formatter: MoneyFormatter,
): String = if (tier == KidUiTier.SEEDLING) {
    val words = formatter.formatForTier(magnitude, currency, tier)
    when (direction) {
        AmountDirection.IN -> stringResource(R.string.core_ui_event_got, words)
        AmountDirection.OUT -> stringResource(R.string.core_ui_event_spent, words)
        AmountDirection.MOVE -> stringResource(R.string.core_ui_event_moved, words)
    }
} else {
    when (direction) {
        AmountDirection.IN -> formatter.format(magnitude, currency, signed = true)
        AmountDirection.OUT -> formatter.format(-magnitude, currency, signed = true)
        AmountDirection.MOVE -> formatter.format(magnitude, currency)
    }
}

private class PreviewRow(
    val icon: String,
    val title: String,
    val amount: Money,
    val currency: Currency,
    val direction: AmountDirection,
    val subtitle: String? = null,
)

private val previewRows = listOf(
    PreviewRow("🧹", "Tidy bedroom", Money(150), Currency.Fiat("GBP"), AmountDirection.IN),
    PreviewRow("🛍️", "Comic book", Money(399), Currency.Fiat("GBP"), AmountDirection.OUT),
    PreviewRow("🐷", "To savings", Money(500), Currency.Fiat("GBP"), AmountDirection.MOVE),
    PreviewRow("⏱️", "Screen time", Money(30), Currency.Minutes, AmountDirection.OUT, subtitle = "Tablet"),
)

@Composable
private fun LedgerEventRowsSample(tier: KidUiTier) = TierPreviewFrame(tier) {
    Column {
        previewRows.forEach {
            LedgerEventRow(it.icon, it.title, it.amount, it.currency, it.direction, tier, subtitle = it.subtitle)
        }
    }
}

@Preview(name = "Seedling", showBackground = true)
@Composable
private fun LedgerEventRowSeedlingPreview() = LedgerEventRowsSample(KidUiTier.SEEDLING)

@Preview(name = "Sprout", showBackground = true)
@Composable
private fun LedgerEventRowSproutPreview() = LedgerEventRowsSample(KidUiTier.SPROUT)

@Preview(name = "Sapling", showBackground = true)
@Composable
private fun LedgerEventRowSaplingPreview() = LedgerEventRowsSample(KidUiTier.SAPLING)

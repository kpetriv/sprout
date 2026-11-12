package dev.kpetriv.sprout.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.KidUiTier

/**
 * The amount of one ledger row, worded for [tier]: Seedling reads "You got 3 coins" / "You used 30 min" with no minus
 * signs and no decimals; Sprout and Sapling show signed amounts ("+£1.50", "-£2.00"; transfers unsigned).
 *
 * @param magnitude the amount moved; the sign comes from [direction].
 */
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

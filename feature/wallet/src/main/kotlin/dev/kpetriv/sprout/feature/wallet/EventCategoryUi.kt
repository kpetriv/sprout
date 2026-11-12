package dev.kpetriv.sprout.feature.wallet

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.kpetriv.sprout.core.designsystem.components.LedgerRowKind
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.ui.AmountDirection
import dev.kpetriv.sprout.core.ui.AmountInputError

/**
 * The history row's icon (M9 §3 and the renders): a single-colour Material Symbol that `LedgerRow` tints by
 * [rowKind]. Decorative; the row's title carries the meaning.
 */
internal val EventCategory.icon: ImageVector
    @Composable get() = when (this) {
        EventCategory.CHORES -> SproutIcons.Checklist
        EventCategory.ALLOWANCE -> SproutIcons.Payments
        EventCategory.GAMES -> SproutIcons.SportsEsports
        EventCategory.SHOP -> SproutIcons.ShoppingBag
        EventCategory.SAVINGS -> SproutIcons.Savings
        EventCategory.INTEREST -> SproutIcons.Eco
        EventCategory.SCREEN_TIME -> SproutIcons.Timer
        EventCategory.ADJUSTMENT -> SproutIcons.Edit
    }

/**
 * The [LedgerRowKind] that tints a history row's icon circle and amount (M9 §3): screen time is Sky, moves between
 * the kid's own pots are Linen, money out is the spending tint, game credits are Honey and other money in is Leaf.
 */
internal val EventUi.rowKind: LedgerRowKind
    get() = when {
        category == EventCategory.SCREEN_TIME || currency == Currency.Minutes -> LedgerRowKind.Time
        direction == AmountDirection.MOVE || category == EventCategory.SAVINGS -> LedgerRowKind.Transfer
        direction == AmountDirection.OUT -> LedgerRowKind.Spending
        category == EventCategory.GAMES -> LedgerRowKind.Reward
        else -> LedgerRowKind.Credit
    }

@get:StringRes
internal val EventCategory.titleRes: Int
    get() = when (this) {
        EventCategory.CHORES -> R.string.feature_wallet_event_chores
        EventCategory.ALLOWANCE -> R.string.feature_wallet_event_allowance
        EventCategory.GAMES -> R.string.feature_wallet_event_games
        EventCategory.SHOP -> R.string.feature_wallet_event_shop
        EventCategory.SAVINGS -> R.string.feature_wallet_event_savings
        EventCategory.INTEREST -> R.string.feature_wallet_event_interest
        EventCategory.SCREEN_TIME -> R.string.feature_wallet_event_screen_time
        EventCategory.ADJUSTMENT -> R.string.feature_wallet_event_adjustment
    }

/** Row title: [EventCategory.titleRes], except that screen time credited (a reward's minutes) reads "Screen time". */
@get:StringRes
internal val EventUi.titleRes: Int
    get() = if (category == EventCategory.SCREEN_TIME && direction == AmountDirection.IN) {
        R.string.feature_wallet_event_screen_time_added
    } else {
        category.titleRes
    }

@get:StringRes
internal val EventCategory.filterLabelRes: Int
    get() = when (this) {
        EventCategory.CHORES -> R.string.feature_wallet_filter_chores
        EventCategory.ALLOWANCE -> R.string.feature_wallet_filter_allowance
        EventCategory.GAMES -> R.string.feature_wallet_filter_games
        EventCategory.SHOP -> R.string.feature_wallet_filter_shop
        EventCategory.SAVINGS -> R.string.feature_wallet_filter_savings
        EventCategory.INTEREST -> R.string.feature_wallet_filter_interest
        EventCategory.SCREEN_TIME -> R.string.feature_wallet_filter_screen_time
        EventCategory.ADJUSTMENT -> R.string.feature_wallet_filter_adjustment
    }

/** Display name of an account. */
@Composable
internal fun BalanceUi.label(): String = stringResource(
    when {
        kind == AccountKind.SAVINGS -> R.string.feature_wallet_account_savings
        currency == Currency.Minutes -> R.string.feature_wallet_account_minutes
        else -> R.string.feature_wallet_account_money
    },
)

@Composable
internal fun AdjustmentError.message(): String = when (this) {
    AdjustmentError.InvalidAmount -> stringResource(R.string.feature_wallet_amount_zero)
    AdjustmentError.ReasonRequired -> stringResource(R.string.feature_wallet_reason_required)
    is AdjustmentError.InsufficientFunds -> stringResource(R.string.feature_wallet_insufficient_funds, available)
    AdjustmentError.NotAllowed -> stringResource(R.string.feature_wallet_not_allowed)
    AdjustmentError.NoAccount -> stringResource(R.string.feature_wallet_no_account)
    AdjustmentError.Conflict -> stringResource(R.string.feature_wallet_adjust_conflict)
    AdjustmentError.ParentAuthUnavailable -> stringResource(R.string.feature_wallet_adjust_auth_unavailable)
    AdjustmentError.AuthorizationFailed -> stringResource(R.string.feature_wallet_adjust_auth_failed)
    AdjustmentError.KeyInvalidated -> stringResource(R.string.feature_wallet_adjust_key_invalidated)
    AdjustmentError.Failed -> stringResource(R.string.feature_wallet_adjust_failed)
}

@Composable
internal fun AmountInputError.message(fractionDigits: Int): String = when (this) {
    AmountInputError.NOT_A_NUMBER -> stringResource(R.string.feature_wallet_amount_not_a_number)

    AmountInputError.TOO_MANY_DECIMALS -> if (fractionDigits == 0) {
        stringResource(R.string.feature_wallet_amount_whole_only)
    } else {
        pluralStringResource(R.plurals.feature_wallet_amount_too_many_decimals, fractionDigits, fractionDigits)
    }

    AmountInputError.TOO_LARGE -> stringResource(R.string.feature_wallet_amount_too_large)
}

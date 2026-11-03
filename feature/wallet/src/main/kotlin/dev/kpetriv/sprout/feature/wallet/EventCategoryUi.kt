package dev.kpetriv.sprout.feature.wallet

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.ui.AmountDirection
import dev.kpetriv.sprout.core.ui.AmountInputError

/** Decorative glyph for a history row; the title carries the meaning for accessibility. */
internal val EventCategory.icon: String
    get() = when (this) {
        EventCategory.CHORES -> "🧹"
        EventCategory.ALLOWANCE -> "💰"
        EventCategory.GAMES -> "🎮"
        EventCategory.SHOP -> "🛍️"
        EventCategory.SAVINGS -> "🐷"
        EventCategory.INTEREST -> "🌱"
        EventCategory.SCREEN_TIME -> "⏱️"
        EventCategory.ADJUSTMENT -> "✏️"
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

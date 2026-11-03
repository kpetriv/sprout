package dev.kpetriv.sprout.feature.wallet

import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.AmountDirection
import kotlin.time.Instant

/** Everything the wallet screen shows. One instance per emission of [WalletViewModel.state]. */
sealed interface WalletUiState {
    data object Loading : WalletUiState

    /**
     * @property balances every account, main money first, then minutes, then savings.
     * @property events newest first: none on Seedling (the last 10 when a parent is looking), the last 10 on Sprout,
     *   up to 100 on Sapling (already narrowed by [filter]).
     * @property availableFilters categories present in the loaded history (Sapling filter chips).
     * @property interestEarned total of the loaded [EventCategory.INTEREST] events, formatted; null when none.
     * @property anomaliesCount ledger anomalies about this kid that need a parent's attention (overdrafts, excluded
     *   games, invalid events, conflicting duplicates), from [dev.kpetriv.sprout.core.domainledger.LedgerRepository
     *   .observeAnomalies].
     * @property isParentView a parent is looking ([WalletMode.PARENT]).
     * @property canAdjust a parent is looking and the kid has a main money account to adjust.
     * @property adjustment the manual adjustment sheet, when open.
     */
    data class Ready(
        val kid: Kid,
        val tier: KidUiTier,
        val balances: List<BalanceUi>,
        val coins: Long,
        val jarLevel: Float,
        val savingsGoal: SavingsGoalUi?,
        val events: List<EventUi>,
        val filter: EventCategory?,
        val availableFilters: List<EventCategory>,
        val interestEarned: String?,
        val anomaliesCount: Int,
        val isParentView: Boolean,
        val canAdjust: Boolean,
        val adjustment: AdjustmentUi?,
        val message: WalletMessage?,
    ) : WalletUiState {
        /** The day-to-day money account, if the kid has one. */
        val mainBalance: BalanceUi?
            get() = balances.firstOrNull { it.kind == AccountKind.MAIN && it.currency is Currency.Fiat }

        /** Screen-time minutes, if the kid has a minutes account. */
        val minutesBalance: BalanceUi?
            get() = balances.firstOrNull { it.currency == Currency.Minutes }
    }

    data class Error(val reason: WalletError) : WalletUiState
}

/** Why the wallet could not load. */
enum class WalletError {
    /** No kid with the requested id. */
    KID_NOT_FOUND,

    /** Reading the ledger or profile failed. */
    LOAD_FAILED,
}

/**
 * One account's balance.
 *
 * @property formatted the balance in real currency or minutes ("£12.50", "1 h 20 min").
 * @property kidFormatted the same balance as the kid's tier reads it ("12 coins" on Seedling).
 */
data class BalanceUi(
    val accountId: AccountId,
    val kind: AccountKind,
    val currency: Currency,
    val amount: Money,
    val formatted: String,
    val kidFormatted: String,
)

/**
 * The savings goal card. Goals with a name and target amount land with the savings-goal feature; until then
 * this shows the savings account balance with no target.
 */
data class SavingsGoalUi(val savedText: String, val targetText: String?, val progress: Float)

/**
 * One history row.
 *
 * @property amount the magnitude moved; [direction] gives the sign.
 * @property note free text the author attached (a manual adjustment's reason), shown under the title.
 */
data class EventUi(
    val eventId: String,
    val category: EventCategory,
    val direction: AmountDirection,
    val amount: Money,
    val currency: Currency,
    val note: String?,
    val createdAt: Instant,
)

/** What a ledger event was for; drives the row icon, title and the Sapling filter chips. */
enum class EventCategory {
    CHORES,
    ALLOWANCE,
    GAMES,
    SHOP,
    SAVINGS,
    INTEREST,
    SCREEN_TIME,
    ADJUSTMENT,
}

/** The open manual adjustment sheet. Adjustments post to the kid's main money account in [currency]. */
data class AdjustmentUi(
    val currency: Currency.Fiat,
    val isSubmitting: Boolean = false,
    val error: AdjustmentError? = null,
)

/** Why an adjustment was not saved; shown in the sheet. */
sealed interface AdjustmentError {
    /** Zero or negative amount entered. */
    data object InvalidAmount : AdjustmentError

    data object ReasonRequired : AdjustmentError

    /** A debit larger than the balance. [available] is formatted in the account's currency. */
    data class InsufficientFunds(val available: String) : AdjustmentError

    /** Only a parent may adjust balances. */
    data object NotAllowed : AdjustmentError

    /** The kid has no main money account to adjust. */
    data object NoAccount : AdjustmentError

    /**
     * Another event already uses this adjustment's id (a conflicting duplicate). The adjustment was stored, but the
     * ledger may not apply it until a parent checks the history.
     */
    data object Conflict : AdjustmentError

    /** The ledger refused or failed to store the event for another reason. */
    data object Failed : AdjustmentError
}

/** Direction of a manual adjustment. */
enum class AdjustmentSign {
    CREDIT,
    DEBIT,
}

/** One-off messages shown in a snackbar; acknowledged with [WalletEvent.MessageShown]. */
sealed interface WalletMessage {
    data object AdjustmentSaved : WalletMessage

    /** An adjustment failed after its sheet was dismissed, so the error cannot be shown in the sheet. */
    data class AdjustmentFailed(val error: AdjustmentError) : WalletMessage
}

/** Everything the wallet screen can ask the [WalletViewModel] to do. */
sealed interface WalletEvent {
    /** Re-subscribe to the data sources (retry after an error). */
    data object Refresh : WalletEvent

    data object OpenAdjust : WalletEvent

    /** Save a manual adjustment of [amountMinor] minor units (positive) in the direction of [sign]. */
    data class SubmitAdjust(val amountMinor: Long, val sign: AdjustmentSign, val reason: String) : WalletEvent

    data object DismissAdjust : WalletEvent

    /** Sapling history filter; null shows everything. */
    data class FilterChanged(val category: EventCategory?) : WalletEvent

    /** The current [WalletUiState.Ready.message] has been shown. */
    data object MessageShown : WalletEvent
}

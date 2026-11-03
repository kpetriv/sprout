package dev.kpetriv.sprout.feature.wallet

import dev.kpetriv.sprout.core.domainhousehold.SavingsGoal
import dev.kpetriv.sprout.core.domainhousehold.SavingsProgress
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.AllowancePaid
import dev.kpetriv.sprout.core.domainledger.Balance
import dev.kpetriv.sprout.core.domainledger.ChoreRewarded
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.GamePlayed
import dev.kpetriv.sprout.core.domainledger.InterestAccrued
import dev.kpetriv.sprout.core.domainledger.LedgerAnomaly
import dev.kpetriv.sprout.core.domainledger.LedgerEvent
import dev.kpetriv.sprout.core.domainledger.ManualAdjustment
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.MovesValue
import dev.kpetriv.sprout.core.domainledger.OverdraftCompensated
import dev.kpetriv.sprout.core.domainledger.RewardPurchased
import dev.kpetriv.sprout.core.domainledger.TimeBankCredited
import dev.kpetriv.sprout.core.domainledger.TimeBankStopped
import dev.kpetriv.sprout.core.domainledger.TransferToSavings
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.AmountDirection
import dev.kpetriv.sprout.core.ui.BalanceJarDefaults
import dev.kpetriv.sprout.core.ui.MoneyFormatter
import dev.kpetriv.sprout.core.ui.SavingsStarsDefaults

/** What the data sources currently say about one kid. */
internal data class WalletSnapshot(
    val kid: Kid,
    val accounts: List<Account>,
    val balances: List<Balance>,
    val events: List<LedgerEvent>,
    val anomaliesCount: Int,
    val goals: List<SavingsGoal> = emptyList(),
    val excludedGames: Map<String, GameExclusion> = emptyMap(),
)

/** Screen-local state the data sources do not own. */
internal data class WalletLocalState(
    val filter: EventCategory? = null,
    val adjustment: AdjustmentUi? = null,
    val message: WalletMessage? = null,
)

/** What the household policy means for this wallet; see [AdjustmentUi.unsignedWarning]. */
internal data class WalletPolicyState(val parentSignaturesOff: Boolean = false)

/** Pure mapping from ledger data to [WalletUiState]; no Android, no coroutines. */
internal class WalletStateMapper(private val formatter: MoneyFormatter) {
    fun ready(
        snapshot: WalletSnapshot,
        local: WalletLocalState,
        mode: WalletMode,
        policy: WalletPolicyState = WalletPolicyState(),
    ): WalletUiState.Ready {
        val tier = snapshot.kid.uiTier
        val balances = balances(snapshot.accounts, snapshot.balances, tier)
        val main = balances.firstOrNull { it.kind == AccountKind.MAIN && it.currency is Currency.Fiat }
        val coins = main?.let { MoneyFormatter.wholeCoins(it.amount, it.currency as Currency.Fiat) } ?: 0L
        val events = snapshot.events.mapNotNull { eventUi(it, snapshot.excludedGames[it.eventId.value]) }
        val filter = local.filter.takeIf { tier == KidUiTier.SAPLING }
        return WalletUiState.Ready(
            kid = snapshot.kid,
            tier = tier,
            balances = balances,
            coins = coins,
            jarLevel = BalanceJarDefaults.levelFor(coins),
            savingsGoal = savingsGoal(balances, activeGoal(snapshot.goals), tier),
            events = if (filter == null) events else events.filter { it.category == filter },
            filter = filter,
            availableFilters = EventCategory.entries.filter { category -> events.any { it.category == category } },
            interestEarned = interestEarned(snapshot.events),
            anomaliesCount = snapshot.anomaliesCount,
            isParentView = mode == WalletMode.PARENT,
            canAdjust = mode == WalletMode.PARENT && main != null,
            adjustment = local.adjustment?.copy(unsignedWarning = policy.parentSignaturesOff),
            message = local.message,
        )
    }

    private fun balances(accounts: List<Account>, balances: List<Balance>, tier: KidUiTier): List<BalanceUi> {
        val byAccount = balances.associateBy { it.accountId }
        return accounts.sortedBy(::displayOrder).map { account ->
            val amount = byAccount[account.id]?.amount ?: Money.ZERO
            BalanceUi(
                accountId = account.id,
                kind = account.kind,
                currency = account.currency,
                amount = amount,
                formatted = formatter.format(amount, account.currency),
                kidFormatted = formatter.formatForTier(amount, account.currency, tier),
            )
        }
    }

    private fun displayOrder(account: Account): Int = when {
        account.kind == AccountKind.MAIN && account.currency is Currency.Fiat -> 0
        account.kind == AccountKind.MAIN -> 1
        else -> 2
    }

    /**
     * The savings card over the SAVINGS balance: against [goal] ([SavingsProgress]) when there is one, otherwise the
     * balance alone. Null when the kid has no savings account.
     */
    private fun savingsGoal(balances: List<BalanceUi>, goal: SavingsGoal?, tier: KidUiTier): SavingsGoalUi? =
        balances.firstOrNull { it.kind == AccountKind.SAVINGS }?.let { savings ->
            val savedText = formatter.formatForTier(savings.amount, savings.currency, tier)
            if (goal == null) {
                SavingsGoalUi(savedText = savedText, targetText = null, progress = 0f)
            } else {
                val progress = SavingsProgress.of(goal, savings.amount)
                SavingsGoalUi(
                    savedText = savedText,
                    targetText = formatter.formatForTier(goal.target, savings.currency, tier),
                    // Drawing only: the exact fraction stays in SavingsProgress.
                    progress = progress.numerator.toFloat() / progress.denominator,
                    title = goal.title,
                    stars = SavingsStarsDefaults.filledStars(progress.numerator, progress.denominator),
                    isComplete = progress.isComplete,
                )
            }
        }

    /** Sum of [InterestAccrued] in the loaded events, per currency; null when there is none. */
    private fun interestEarned(events: List<LedgerEvent>): String? = events
        .filterIsInstance<InterestAccrued>()
        .groupBy { it.currency }
        .map { (currency, interest) ->
            formatter.format(interest.fold(Money.ZERO) { sum, e -> sum + e.amount }, currency)
        }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" + ")

    companion object {
        /** The goal the wallet shows: the oldest one not yet achieved. */
        fun activeGoal(goals: List<SavingsGoal>): SavingsGoal? = goals.firstOrNull { it.achievedOn == null }

        private const val SPROUT_HISTORY = 10
        private const val SAPLING_HISTORY = 100
        private const val PARENT_SEEDLING_HISTORY = 10

        /**
         * How many events each tier lists. A Seedling kid sees no history; a parent looking at a Seedling wallet
         * gets a short recent-activity list.
         */
        fun eventLimitFor(tier: KidUiTier, mode: WalletMode): Int = when (tier) {
            KidUiTier.SEEDLING -> if (mode == WalletMode.PARENT) PARENT_SEEDLING_HISTORY else 0
            KidUiTier.SPROUT -> SPROUT_HISTORY
            KidUiTier.SAPLING -> SAPLING_HISTORY
        }

        /**
         * Game plays the ledger excluded, by event id, from the kid's anomalies: one out of its played-on window or
         * over its day's cap.
         */
        fun excludedGames(anomalies: List<LedgerAnomaly>): Map<String, GameExclusion> = anomalies.mapNotNull {
            when (it) {
                is LedgerAnomaly.PlayedOnOutOfRange -> it.eventId.value to GameExclusion.PLAYED_ON_OUT_OF_RANGE
                is LedgerAnomaly.DailyCapExceeded -> it.eventId.value to GameExclusion.DAILY_CAP_EXCEEDED
                else -> null
            }
        }.toMap()

        /**
         * A history row for [event], or null for events that move no value (a time bank start). [excluded] marks a
         * game play the ledger left out of the balance; it is ignored for any other event.
         */
        fun eventUi(event: LedgerEvent, excluded: GameExclusion? = null): EventUi? {
            if (event !is MovesValue) return null
            val amount = event.amount
            return EventUi(
                eventId = event.eventId.value,
                category = categoryOf(event),
                direction = when {
                    event is TransferToSavings -> AmountDirection.MOVE
                    event is RewardPurchased || event is TimeBankStopped || amount.isNegative -> AmountDirection.OUT
                    else -> AmountDirection.IN
                },
                amount = if (amount.isNegative) -amount else amount,
                currency = event.currency,
                note = when (event) {
                    is ManualAdjustment -> event.reason
                    is OverdraftCompensated -> event.note
                    else -> null
                },
                createdAt = event.envelope.createdAt,
                excluded = excluded.takeIf { event is GamePlayed },
            )
        }

        private fun categoryOf(event: MovesValue): EventCategory = when (event) {
            is ChoreRewarded -> EventCategory.CHORES
            is AllowancePaid -> EventCategory.ALLOWANCE
            is GamePlayed -> EventCategory.GAMES
            is RewardPurchased -> EventCategory.SHOP
            is TransferToSavings -> EventCategory.SAVINGS
            is InterestAccrued -> EventCategory.INTEREST
            is TimeBankStopped, is TimeBankCredited -> EventCategory.SCREEN_TIME
            is ManualAdjustment, is OverdraftCompensated -> EventCategory.ADJUSTMENT
        }
    }
}

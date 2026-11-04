package dev.kpetriv.sprout.feature.wallet

import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.YearMonth
import dev.kpetriv.sprout.core.ui.AmountDirection
import dev.kpetriv.sprout.core.ui.BalanceJarDefaults
import dev.kpetriv.sprout.core.ui.MoneyFormatter
import dev.kpetriv.sprout.core.ui.SavingsStarsDefaults
import java.util.Locale
import kotlin.time.Instant

/** Realistic fake wallet states for previews and UI tests. */
internal object WalletPreviewData {
    private val gbp = Currency.Fiat("GBP")
    private val formatter = MoneyFormatter(Locale.UK)
    private const val DAY_MILLIS = 86_400_000L
    private const val BIRTH_MONTH = 4
    private const val MAIN_MINOR = 745L
    private const val MINUTES = 80L
    private const val SAVINGS_MINOR = 1_250L
    private const val GOAL_MINOR = 4_000L
    private const val INTEREST_MINOR = 12L
    private const val GOAL_TITLE = "a new bike"
    private val now = Instant.fromEpochMilliseconds(1_793_000_000_000L)

    private val names = mapOf(
        KidUiTier.SEEDLING to "Mia",
        KidUiTier.SPROUT to "Leo",
        KidUiTier.SAPLING to "Ava",
    )
    private val birthYears = mapOf(
        KidUiTier.SEEDLING to 2020,
        KidUiTier.SPROUT to 2016,
        KidUiTier.SAPLING to 2013,
    )

    fun kid(tier: KidUiTier) = Kid(
        id = KidId("kid-${tier.name.lowercase()}"),
        householdId = HouseholdId("household-preview"),
        firstName = names.getValue(tier),
        avatarId = "fox",
        birthYearMonth = YearMonth(birthYears.getValue(tier), BIRTH_MONTH),
        uiTier = tier,
    )

    private fun balance(id: String, kind: AccountKind, currency: Currency, minor: Long, tier: KidUiTier) = BalanceUi(
        accountId = AccountId(id),
        kind = kind,
        currency = currency,
        amount = Money(minor),
        formatted = formatter.format(Money(minor), currency),
        kidFormatted = formatter.formatForTier(Money(minor), currency, tier),
    )

    private fun event(
        index: Int,
        category: EventCategory,
        direction: AmountDirection,
        minor: Long,
        currency: Currency = gbp,
        note: String? = null,
    ) = EventUi(
        eventId = "00000000-0000-4000-8000-%012d".format(index),
        category = category,
        direction = direction,
        amount = Money(minor),
        currency = currency,
        note = note,
        createdAt = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() - index * DAY_MILLIS),
    )

    val events: List<EventUi> = listOf(
        event(1, EventCategory.CHORES, AmountDirection.IN, 150),
        event(2, EventCategory.SHOP, AmountDirection.OUT, 399),
        event(3, EventCategory.SCREEN_TIME, AmountDirection.OUT, 30, Currency.Minutes),
        event(4, EventCategory.ALLOWANCE, AmountDirection.IN, 500),
        event(5, EventCategory.SAVINGS, AmountDirection.MOVE, 200),
        event(6, EventCategory.INTEREST, AmountDirection.IN, 12),
        event(7, EventCategory.GAMES, AmountDirection.IN, 20),
        event(8, EventCategory.ADJUSTMENT, AmountDirection.IN, 1000, note = "Birthday money from Grandma"),
    )

    fun ready(
        tier: KidUiTier,
        canAdjust: Boolean = true,
        adjustment: AdjustmentUi? = null,
        filter: EventCategory? = null,
        anomaliesCount: Int = 0,
        isParentView: Boolean = canAdjust,
    ): WalletUiState.Ready {
        val balances = listOf(
            balance("acc-main", AccountKind.MAIN, gbp, MAIN_MINOR, tier),
            balance("acc-minutes", AccountKind.MAIN, Currency.Minutes, MINUTES, tier),
            balance("acc-savings", AccountKind.SAVINGS, gbp, SAVINGS_MINOR, tier),
        )
        val coins = MoneyFormatter.wholeCoins(Money(MAIN_MINOR), gbp)
        val tierEvents = when (tier) {
            KidUiTier.SEEDLING -> if (isParentView) events else emptyList()
            KidUiTier.SPROUT, KidUiTier.SAPLING -> events
        }
        return WalletUiState.Ready(
            kid = kid(tier),
            tier = tier,
            balances = balances,
            coins = coins,
            jarLevel = BalanceJarDefaults.levelFor(coins),
            savingsGoal = SavingsGoalUi(
                savedText = formatter.formatForTier(Money(SAVINGS_MINOR), gbp, tier),
                targetText = formatter.formatForTier(Money(GOAL_MINOR), gbp, tier),
                progress = SAVINGS_MINOR.toFloat() / GOAL_MINOR,
                title = GOAL_TITLE,
                stars = SavingsStarsDefaults.filledStars(SAVINGS_MINOR, GOAL_MINOR),
            ),
            events = if (filter == null) tierEvents else tierEvents.filter { it.category == filter },
            filter = filter,
            availableFilters = EventCategory.entries.filter { c -> tierEvents.any { it.category == c } },
            interestEarned = if (tier == KidUiTier.SAPLING) formatter.format(Money(INTEREST_MINOR), gbp) else null,
            anomaliesCount = anomaliesCount,
            isParentView = isParentView,
            canAdjust = canAdjust,
            adjustment = adjustment,
            message = null,
        )
    }

    val adjustment = AdjustmentUi(currency = gbp)

    /** The sheet after "Scan a receipt" read a £12.49 total from the Corner Shop. */
    val adjustmentPrefilled = adjustment.copy(prefill = AdjustmentPrefill(Money(1_249), "Corner Shop"))
}

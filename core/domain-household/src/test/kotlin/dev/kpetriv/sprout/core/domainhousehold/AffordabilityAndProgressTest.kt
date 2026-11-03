package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AllowanceCadence
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.GoalId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AffordabilityAndProgressTest {
    private val weekly =
        allowance(AllowanceCadence.Weekly(DayOfWeek.SATURDAY), startsOn = date("2026-01-01"), amount = 300)
    private val today = date("2026-11-02") // a Monday

    @Test
    fun affordableRewards() {
        val result = RewardAffordability.of(reward(cost = 500), gbp(500))
        assertTrue(result.canAfford)
        assertEquals(Money.ZERO, result.shortfall)
        assertEquals(0, result.daysUntil(weekly, today))
        assertTrue(RewardAffordability.of(reward(cost = 500), gbp(900)).canAfford)
    }

    @Test
    fun shortfallAndDaysUntilAllowanceCoversIt() {
        val result = RewardAffordability.of(reward(cost = 1_000), gbp(250))
        assertFalse(result.canAfford)
        assertEquals(Money(750), result.shortfall)
        // 750 needs three £3 payments: Saturdays 7, 14 and 21 November.
        assertEquals(19, result.daysUntil(weekly, today))
        // Exactly divisible: 600 short needs two payments.
        assertEquals(12, RewardAffordability.of(reward(cost = 850), gbp(250)).daysUntil(weekly, today))
        // On a payment day, today's payment is assumed credited already.
        assertEquals(7, RewardAffordability.of(reward(cost = 300), gbp(0)).daysUntil(weekly, date("2026-11-07")))
    }

    @Test
    fun negativeBalancesWidenTheShortfall() {
        assertEquals(Money(700), RewardAffordability.of(reward(cost = 500), gbp(-200)).shortfall)
    }

    @Test
    fun allowanceThatCannotHelpGivesNoEstimate() {
        val result = RewardAffordability.of(reward(cost = 1_000), gbp(0))
        assertNull(result.daysUntil(allowance(AllowanceCadence.Monthly(1), active = false), today))
        assertNull(result.daysUntil(allowance(AllowanceCadence.Monthly(1), currency = Currency.Minutes), today))
        assertNull(result.daysUntil(allowance(AllowanceCadence.Monthly(1), currency = Currency.Fiat("EUR")), today))
        // More than 520 payments away.
        assertNull(
            RewardAffordability.of(
                reward(cost = 1_000_000),
                gbp(0),
            ).daysUntil(allowance(AllowanceCadence.Monthly(1), amount = 1), today),
        )
    }

    @Test
    fun monthlyAllowanceEstimateAndFutureStart() {
        val monthly = allowance(AllowanceCadence.Monthly(15), startsOn = date("2027-01-01"), amount = 1_000)
        // First payment 2027-01-15, second 2027-02-15.
        assertEquals(
            date("2026-11-02").daysUntil(date("2027-02-15")).toInt(),
            RewardAffordability.of(reward(cost = 2_000), gbp(0)).daysUntil(monthly, today),
        )
    }

    @Test
    fun savingsProgressIsAnExactClampedFraction() {
        val goal = SavingsGoal(GoalId("g1"), KID, "Bike", Money(12_000), date("2026-01-01"))
        assertEquals(SavingsProgress(3_000, 12_000), SavingsProgress.of(goal, gbp(3_000)))
        assertEquals(Money(9_000), SavingsProgress.of(goal, gbp(3_000)).remaining)
        assertFalse(SavingsProgress.of(goal, gbp(11_999)).isComplete)
        assertTrue(SavingsProgress.of(goal, gbp(12_000)).isComplete)
        assertEquals(SavingsProgress(12_000, 12_000), SavingsProgress.of(goal, gbp(50_000)))
        assertEquals(SavingsProgress(0, 12_000), SavingsProgress.of(goal, gbp(-10)))
        assertEquals(Money.ZERO, SavingsProgress.of(goal, gbp(50_000)).remaining)
        assertFailsWith<IllegalArgumentException> { SavingsProgress(1, 0) }
        assertFailsWith<IllegalArgumentException> { SavingsProgress(2, 1) }
        assertFailsWith<IllegalArgumentException> { SavingsProgress(-1, 1) }
    }
}

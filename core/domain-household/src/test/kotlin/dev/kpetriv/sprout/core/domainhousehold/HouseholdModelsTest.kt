package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AllowanceCadence
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.GoalId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.MAX_DESCRIPTION_LENGTH
import dev.kpetriv.sprout.core.model.MAX_TITLE_LENGTH
import dev.kpetriv.sprout.core.model.RewardKind
import dev.kpetriv.sprout.core.model.ScheduleId
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HouseholdModelsTest {
    @Test
    fun choreRewardNeedsAPositiveAmount() {
        ChoreReward(money = Money(1))
        ChoreReward(minutes = Money(1))
        assertFailsWith<IllegalArgumentException> { ChoreReward() }
        assertFailsWith<IllegalArgumentException> { ChoreReward(money = Money(0)) }
        assertFailsWith<IllegalArgumentException> { ChoreReward(money = Money(5), minutes = Money(-1)) }
    }

    @Test
    fun choreTextLimitsAndRedaction() {
        val base = chore(ChoreSchedule.Daily.EVERY_DAY)
        base.copy(title = "t".repeat(MAX_TITLE_LENGTH), description = "d".repeat(MAX_DESCRIPTION_LENGTH))
        assertFailsWith<IllegalArgumentException> { base.copy(title = "t".repeat(MAX_TITLE_LENGTH + 1)) }
        assertFailsWith<IllegalArgumentException> { base.copy(title = "  ") }
        assertFailsWith<IllegalArgumentException> { base.copy(description = "d".repeat(MAX_DESCRIPTION_LENGTH + 1)) }
        val text = base.copy(description = "Grandma Jo's flat, 12 Elm St").toString()
        assertTrue("title=Feed the cat" in text, text)
        assertFalse("Elm" in text, text)
        assertTrue("description=<redacted>" in text, text)
        assertTrue("description=null" in base.toString())
    }

    @Test
    fun choreRoundTripsThroughJson() {
        val value = chore(ChoreSchedule.Weekly(DayOfWeek.FRIDAY), reward = ChoreReward(Money(50), Money(10)))
            .copy(description = "Wet food only")
        assertEquals(value, Json.decodeFromString(Chore.serializer(), Json.encodeToString(Chore.serializer(), value)))
    }

    @Test
    fun rewardRules() {
        reward(cost = 100)
        val screen = reward(cost = 100, kind = RewardKind.SCREEN_TIME, minutes = 30)
        assertFailsWith<IllegalArgumentException> { reward(cost = 100, kind = RewardKind.SCREEN_TIME) }
        assertFailsWith<IllegalArgumentException> { reward(cost = 100, kind = RewardKind.SCREEN_TIME, minutes = 0) }
        assertFailsWith<IllegalArgumentException> { reward(cost = 100, kind = RewardKind.TREAT, minutes = 10) }
        assertFailsWith<IllegalArgumentException> { reward(cost = 0) }
        assertFailsWith<IllegalArgumentException> { screen.copy(currency = Currency.Minutes) }
        assertFailsWith<IllegalArgumentException> { screen.copy(title = "t".repeat(MAX_TITLE_LENGTH + 1)) }
        assertTrue(screen.isAvailableTo(KID))
        assertFalse(screen.copy(active = false).isAvailableTo(KID))
        assertTrue(screen.copy(forKids = setOf(KID)).isAvailableTo(KID))
        assertFalse(screen.copy(forKids = setOf(KidId("k2"))).isAvailableTo(KID))
        assertEquals(
            screen,
            Json.decodeFromString(Reward.serializer(), Json.encodeToString(Reward.serializer(), screen)),
        )
    }

    @Test
    fun savingsGoalRules() {
        val goal = SavingsGoal(GoalId("g1"), KID, "Bike", Money(12_000), date("2026-01-01"))
        goal.copy(achievedOn = date("2026-01-01"))
        assertFailsWith<IllegalArgumentException> { goal.copy(target = Money(0)) }
        assertFailsWith<IllegalArgumentException> { goal.copy(title = "") }
        assertFailsWith<IllegalArgumentException> { goal.copy(achievedOn = date("2025-12-31")) }
        assertEquals(
            goal,
            Json.decodeFromString(SavingsGoal.serializer(), Json.encodeToString(SavingsGoal.serializer(), goal)),
        )
    }

    @Test
    fun pendingActionsSerialisePolymorphically() {
        val actions: List<PendingParentAction> = listOf(
            PendingParentAction.PayAllowance(
                ScheduleId("a1"),
                KID,
                MAIN,
                Money(500),
                GBP,
                "2026-W45",
                date("2026-11-02"),
            ),
            PendingParentAction.AccrueInterest(KID, SAVINGS.id, GBP, Money(10_000), 500, 31, "2026-11", Money(42)),
        )
        for (action in actions) {
            val json = Json.encodeToString(PendingParentAction.serializer(), action)
            assertEquals(action, Json.decodeFromString(PendingParentAction.serializer(), json))
        }
        val json = Json.encodeToString(PendingParentAction.serializer(), actions[0])
        assertTrue(json.startsWith("""{"type":"pay_allowance","""), json)
        assertTrue(""""currency":"GBP"""" in json && """"amount":500""" in json, json)
    }

    @Test
    fun allowanceScheduleRoundTrips() {
        val schedule = allowance(AllowanceCadence.Weekly(DayOfWeek.SATURDAY))
        assertEquals(
            schedule,
            Json.decodeFromString(
                AllowanceSchedule.serializer(),
                Json.encodeToString(AllowanceSchedule.serializer(), schedule),
            ),
        )
    }
}

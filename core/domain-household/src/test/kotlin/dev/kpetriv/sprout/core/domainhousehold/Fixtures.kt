package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.AllowanceCadence
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.ChoreState
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind
import dev.kpetriv.sprout.core.model.ScheduleId
import kotlin.time.Instant

val GBP = Currency.Fiat("GBP")
val HOUSEHOLD = HouseholdId("h1")
val KID = KidId("k1")
val MAIN = AccountId("k1-gbp-main")
val SAVINGS = Account(AccountId("k1-gbp-savings"), KID, GBP, AccountKind.SAVINGS)
val CREATED_AT: Instant = Instant.fromEpochMilliseconds(1_790_000_000_000)

fun date(text: String): CalendarDate = CalendarDate.parse(text)

fun gbp(minor: Long): Money = Money(minor)

fun chore(
    schedule: ChoreSchedule,
    state: ChoreState = ChoreState.ACTIVE,
    reward: ChoreReward = ChoreReward(money = Money(50)),
    id: String = "c1",
) = Chore(
    id = ChoreId(id),
    householdId = HOUSEHOLD,
    kidId = KID,
    title = "Feed the cat",
    reward = reward,
    schedule = schedule,
    createdAt = CREATED_AT,
    state = state,
)

fun allowance(
    cadence: AllowanceCadence,
    startsOn: CalendarDate = date("2026-01-01"),
    amount: Long = 500,
    active: Boolean = true,
    currency: Currency = GBP,
) = AllowanceSchedule(
    id = ScheduleId("a1"),
    kidId = KID,
    accountId = MAIN,
    amount = Money(amount),
    currency = currency,
    cadence = cadence,
    startsOn = startsOn,
    active = active,
)

fun reward(cost: Long, kind: RewardKind = RewardKind.TREAT, minutes: Int? = null) = Reward(
    id = RewardId("r1"),
    householdId = HOUSEHOLD,
    title = "Ice cream",
    kind = kind,
    cost = Money(cost),
    currency = GBP,
    minutesGranted = minutes,
)

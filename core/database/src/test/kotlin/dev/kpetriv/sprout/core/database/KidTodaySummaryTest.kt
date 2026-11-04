package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.domainhousehold.Chore
import dev.kpetriv.sprout.core.domainhousehold.ChoreReward
import dev.kpetriv.sprout.core.domainhousehold.KidToday
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.GameCreditResult
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.TimeBankCredited
import dev.kpetriv.sprout.core.games.Difficulty
import dev.kpetriv.sprout.core.games.GameId
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.games.Score
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.ChoreState
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days

/** [KidTodaySummaryUseCase] (M7 §4): the widget's view of a kid's day. */
@RunWith(RobolectricTestRunner::class)
class KidTodaySummaryTest {
    private val household = testHousehold()
    private val monday = CalendarDate(2026, 11, 2)

    @After
    fun close() = household.close()

    private fun chore(kidId: KidId, title: String, schedule: ChoreSchedule, state: ChoreState = ChoreState.ACTIVE) =
        Chore(
            id = ChoreId(title.lowercase().replace(' ', '-')),
            householdId = LocalTrial.HOUSEHOLD,
            kidId = kidId,
            title = title,
            reward = ChoreReward(money = Money(50)),
            schedule = schedule,
            createdAt = household.clock.now(),
            state = state,
        )

    @Test
    fun theSummaryHasBothBalancesAndTodaysChores() = runTest {
        val (kid, accounts) = household.addKid()
        val chores = listOf(
            chore(kid.id, "Water plants", ChoreSchedule.Daily(DayOfWeek.entries.toSet())),
            chore(kid.id, "Bins", ChoreSchedule.Weekly(DayOfWeek.MONDAY)),
            chore(kid.id, "Dishes", ChoreSchedule.Weekly(DayOfWeek.MONDAY)),
            chore(kid.id, "Wash the car", ChoreSchedule.Once(null)),
            chore(kid.id, "Tidy", ChoreSchedule.Weekly(DayOfWeek.TUESDAY)),
            chore(kid.id, "My idea", ChoreSchedule.Weekly(DayOfWeek.MONDAY), ChoreState.PROPOSED),
        )
        chores.forEach { household.chores.upsert(it) }
        household.chores.submit(ChoreId("dishes"), monday)
        val game = household.playGame(kid.id, GameId(GameKind.ARITHMETIC, Difficulty.SAPLING), PERFECT)
        assertIs<GameCreditResult.Credited>(game)
        val minutes = household.writer.append(kid.id, with(household) { accounts.minutes() }.id) {
            TimeBankCredited(it, Money(25), grantedBy = null, rewardId = "manual")
        }
        assertIs<AppendResult.Accepted>(minutes)

        val summary = assertNotNull(household.kidTodaySummary.get(kid.id))

        assertEquals(kid.id, summary.kidId)
        assertEquals("Leo", summary.kidName)
        assertEquals(kid.uiTier, summary.tier)
        assertEquals(monday, summary.date)
        assertEquals(household.gbp, summary.fiatCurrency)
        assertEquals(Money(15), summary.fiatBalance)
        assertEquals(Money(25), summary.minutes)
        assertEquals(4, summary.dueToday)
        assertEquals(3, summary.toDo)
        assertEquals(listOf("Bins", "Wash the car"), summary.firstToDoTitles)
        assertFalse("Leo" in summary.toString() || "Bins" in summary.toString())

        // The same list the kid's today screen derives (KidTodayViewModel uses KidToday too).
        val submissions = household.chores.observeSubmissions(SubmissionFilter.ForKid(kid.id)).first()
        val screen = KidToday.occurrences(household.chores.observeChores(kid.id).first(), submissions, monday)
        assertEquals(screen.size, summary.dueToday)
        assertEquals(screen.take(2).map { it.chore.title }, summary.firstToDoTitles)

        // Tomorrow (Tuesday): the daily chore, Tuesday's and the any-time one.
        household.clock.advanceBy(1.days)
        val tuesday = assertNotNull(household.kidTodaySummary.get(kid.id))
        assertEquals(monday.plusDays(1), tuesday.date)
        assertEquals(3, tuesday.dueToday)
        assertEquals(listOf("Tidy", "Wash the car"), tuesday.firstToDoTitles)
    }

    @Test
    fun anUnknownKidHasNoSummaryAndAKidWithoutChoresHasZeroes() = runTest {
        assertNull(household.kidTodaySummary.get(KidId("nobody")))
        val (kid, _) = household.addKid("kid-mia")
        val summary = assertNotNull(household.kidTodaySummary.get(kid.id))
        assertEquals(Money.ZERO, summary.fiatBalance)
        assertEquals(Money.ZERO, summary.minutes)
        assertEquals(0, summary.dueToday)
        assertEquals(emptyList(), summary.firstToDoTitles)
    }

    private companion object {
        val PERFECT = Score(correct = 10, total = 10, perfect = true, timedBonus = true)
    }
}

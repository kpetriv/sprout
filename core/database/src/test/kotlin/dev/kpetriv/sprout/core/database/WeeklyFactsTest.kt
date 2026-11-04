package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.usecase.ApprovalResult
import dev.kpetriv.sprout.core.database.usecase.ManualAdjustmentResult
import dev.kpetriv.sprout.core.database.usecase.WeeklyFactsCalculator
import dev.kpetriv.sprout.core.database.usecase.WeeklyFactsUseCase
import dev.kpetriv.sprout.core.domainhousehold.Chore
import dev.kpetriv.sprout.core.domainhousehold.ChoreReward
import dev.kpetriv.sprout.core.domainhousehold.EarnedByKind
import dev.kpetriv.sprout.core.domainhousehold.IsoWeek
import dev.kpetriv.sprout.core.domainhousehold.KidWeeklyFacts
import dev.kpetriv.sprout.core.domainhousehold.WeeklyFacts
import dev.kpetriv.sprout.core.domainhousehold.previous
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.AllowancePaid
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.ChoreRewarded
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.DeviceWriter
import dev.kpetriv.sprout.core.domainledger.Fixtures
import dev.kpetriv.sprout.core.domainledger.GamePlayed
import dev.kpetriv.sprout.core.domainledger.Ledger
import dev.kpetriv.sprout.core.domainledger.LedgerEvent
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.ChoreSubmission
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.SubmissionId
import dev.kpetriv.sprout.core.model.SubmissionStatus
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.model.YearMonth
import dev.kpetriv.sprout.core.time.FakeClock
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** [WeeklyFactsCalculator] on the ledger fixtures, and [WeeklyFactsUseCase] on a wired household. */
class WeeklyFactsCalculatorTest {
    private val leo = Kid(Fixtures.kid, Fixtures.household, "Leo", "avatar-1", YearMonth(2017, 3), KidUiTier.SPROUT)
    private val mia = Kid(KidId("kid-2"), Fixtures.household, "Mia", "avatar-2", YearMonth(2020, 5), KidUiTier.SEEDLING)
    private val week44 = IsoWeek(2026, 44)
    private val week45 = IsoWeek(2026, 45)
    private val dateOf = { instant: Instant ->
        val date = java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()).atZone(ZoneOffset.UTC).toLocalDate()
        CalendarDate(date.year, date.monthValue, date.dayOfMonth)
    }

    private val clock = FakeClock(Instant.parse("2026-10-28T10:00:00Z"))
    private val parent = DeviceWriter(Fixtures.parentPhone, clock = clock)
    private val kid = DeviceWriter(Fixtures.kidTablet, clock = clock)

    /** Week 44: one chore and one allowance. Week 45 (from Monday 2 November): a bit of everything. */
    private fun history(): List<LedgerEvent> {
        parent.chore(100)
        parent.allowance(200)
        clock.advanceBy(5.days) // Monday 2 November, week 45.
        parent.adjust(500) // Manual adjustments are not part of the facts.
        val chore = parent.chore(150)
        parent.chore(15, account = Fixtures.minutes)
        parent.allowance(200)
        repeat(4) { kid.game(15) } // The fourth goes over the 50 daily cap and is excluded.
        kid.purchase(100)
        parent.transfer(50)
        parent.interest(3)
        kid.timeStart("s1")
        kid.timeStop("s1", 10)
        // A kid device reusing the parent's chore id: a conflicting duplicate, the parent copy applies.
        kid.writeWithId(chore.eventId, Fixtures.main) { GamePlayed(it, Fixtures.gbp, Money(5), "x", 1, Fixtures.today) }
        clock.advanceBy(3.days) // Thursday 5 November.
        parent.chore(999)
        return parent.written + kid.written
    }

    private fun submission(id: String, submitted: String, status: SubmissionStatus, decided: String? = null) =
        ChoreSubmission(
            SubmissionId(id),
            ChoreId("chore-$id"),
            CalendarDate(2026, 11, 2),
            Fixtures.kid,
            Instant.parse(submitted),
            status = status,
            decidedAt = decided?.let(Instant::parse),
            decidedBy = decided?.let { UserId("parent-uid") },
        )

    private val submissions = mapOf(
        Fixtures.kid to listOf(
            submission("a", "2026-11-02T09:00:00Z", SubmissionStatus.APPROVED, "2026-11-03T09:00:00Z"),
            submission("b", "2026-11-02T09:00:00Z", SubmissionStatus.REJECTED, "2026-11-03T09:00:00Z"),
            submission("c", "2026-10-30T09:00:00Z", SubmissionStatus.APPROVED, "2026-11-02T09:00:00Z"),
            submission("d", "2026-11-03T09:00:00Z", SubmissionStatus.SUBMITTED),
        ),
    )

    private fun compute(week: IsoWeek, through: CalendarDate): WeeklyFacts {
        val events = history()
        val projection = Ledger.project(events, Fixtures.accounts, roleOf = Fixtures::roleOf)
        return WeeklyFactsCalculator.compute(
            week,
            through,
            Fixtures.gbp,
            listOf(leo, mia),
            events,
            projection,
            submissions,
            dateOf,
        )
    }

    @Test
    fun theCurrentWeekSoFarCountsOnlyAppliedEventsUpToToday() {
        val facts = compute(week45, through = CalendarDate(2026, 11, 4))

        assertEquals(CalendarDate(2026, 11, 2), facts.from)
        assertEquals(false, facts.complete)
        val expected = KidWeeklyFacts(
            Fixtures.kid,
            "Leo",
            earned = EarnedByKind(chores = Money(150), allowance = Money(200), games = Money(45), interest = Money(3)),
            choreMinutes = Money(15),
            spentOnRewards = Money(100),
            minutesUsed = Money(10),
            movedToSavings = Money(50),
            choresSubmitted = 3,
            choresApproved = 2,
            choresRejected = 1,
            gamesPlayed = 3,
            anomalies = 2, // The capped game and the conflicting copy.
        )
        assertEquals(listOf(expected, KidWeeklyFacts(mia.id, "Mia")), facts.kids)
        assertEquals(Money(398), facts.totals.earned.total)
    }

    @Test
    fun theWholeWeekIncludesLaterDays() {
        val facts = compute(week45, through = CalendarDate(2026, 11, 8))

        assertTrue(facts.complete)
        assertEquals(Money(150 + 999), facts.kids.first().earned.chores)
    }

    @Test
    fun lastWeekHasOnlyItsOwnEvents() {
        val facts = compute(week44, through = CalendarDate(2026, 11, 1))

        val leoFacts = facts.kids.first()
        assertEquals(EarnedByKind(chores = Money(100), allowance = Money(200)), leoFacts.earned)
        assertEquals(1, leoFacts.choresSubmitted)
        assertEquals(0, leoFacts.choresApproved)
        assertEquals(0, leoFacts.gamesPlayed)
        assertEquals(0, leoFacts.anomalies)
        assertTrue(facts.kids.last().isEmpty)
    }

    @Test
    fun theAppliedCopyOfAConflictIsCountedOnce() {
        val facts = compute(week45, through = CalendarDate(2026, 11, 4))
        val copies = (parent.written + kid.written).groupBy { it.eventId }.values.filter { it.size > 1 }
        assertEquals(1, copies.size)
        assertTrue(copies.single().any { it is ChoreRewarded } && copies.single().any { it is GamePlayed })
        assertEquals(Money(150), facts.kids.first().earned.chores)
    }
}

/** Review L15: minutes used, other currencies, another kid's events and the household's midnight. */
class WeeklyFactsEdgesTest {
    private val leo = Kid(Fixtures.kid, Fixtures.household, "Leo", "avatar-1", YearMonth(2017, 3), KidUiTier.SPROUT)
    private val week45 = IsoWeek(2026, 45)
    private val euro = Account(AccountId("eur-1"), Fixtures.kid, Currency.Fiat("EUR"), AccountKind.MAIN)
    private val otherKid = KidId("kid-9")
    private val otherMain = Account(AccountId("main-9"), otherKid, Fixtures.gbp, AccountKind.MAIN)
    private val accounts = Fixtures.accounts + euro + otherMain

    /** Local dates at a fixed offset from UTC, like a household two hours ahead of UTC. */
    private fun dateAt(offsetHours: Int) = { instant: Instant ->
        val date = java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds())
            .atZone(ZoneOffset.ofHours(offsetHours)).toLocalDate()
        CalendarDate(date.year, date.monthValue, date.dayOfMonth)
    }

    private fun facts(
        events: List<LedgerEvent>,
        through: CalendarDate = CalendarDate(2026, 11, 8),
        dateOf: (Instant) -> CalendarDate = dateAt(0),
        week: IsoWeek = week45,
    ): KidWeeklyFacts = WeeklyFactsCalculator.compute(
        week,
        through,
        Fixtures.gbp,
        listOf(leo),
        events,
        Ledger.project(events, accounts, roleOf = Fixtures::roleOf),
        emptyMap(),
        dateOf,
    ).kids.single()

    @Test
    fun minutesUsedAreTheTimeBankStopsOfTheWeek() {
        val clock = FakeClock(Instant.parse("2026-11-01T12:00:00Z")) // Sunday, week 44.
        val parent = DeviceWriter(Fixtures.parentPhone, clock = clock)
        val kid = DeviceWriter(Fixtures.kidTablet, clock = clock)
        parent.chore(60, account = Fixtures.minutes)
        kid.timeStart("last-week")
        kid.timeStop("last-week", 5)
        clock.advanceBy(1.days) // Monday 2 November, week 45.
        kid.timeStart("a")
        kid.timeStop("a", 10)
        kid.timeStart("b")
        kid.timeStop("b", 15)

        val leoFacts = facts(parent.written + kid.written)
        assertEquals(Money(25), leoFacts.minutesUsed)
        assertEquals(Money.ZERO, leoFacts.choreMinutes, "the minutes chore was last week")
        val lastWeek = facts(parent.written + kid.written, CalendarDate(2026, 11, 1), week = week45.previous())
        assertEquals(Money(5), lastWeek.minutesUsed)
        assertEquals(Money(60), lastWeek.choreMinutes)
    }

    @Test
    fun eventsInAnotherCurrencyAndOfOtherKidsAreLeftOut() {
        val clock = FakeClock(Instant.parse("2026-11-02T08:00:00Z"))
        val parent = DeviceWriter(Fixtures.parentPhone, clock = clock)
        parent.chore(150)
        parent.chore(700, account = euro)
        parent.write(euro) { AllowancePaid(it, euro.currency, Money(300), "weekly", "2026-W45") }
        parent.chore(999, account = otherMain)

        val leoFacts = facts(parent.written)
        assertEquals(EarnedByKind(chores = Money(150)), leoFacts.earned)
    }

    @Test
    fun anEventBelongsToTheHouseholdsLocalDayAcrossMidnight() {
        // 22:30 UTC on Sunday 1 November is 00:30 on Monday 2 November two hours ahead: week 45 there, 44 in UTC.
        val clock = FakeClock(Instant.parse("2026-11-01T22:29:00Z"))
        val parent = DeviceWriter(Fixtures.parentPhone, clock = clock)
        parent.chore(100)
        // 21:59 UTC on Sunday 1 November is 23:59 on Sunday two hours ahead: week 44 in both.
        val early = DeviceWriter(Fixtures.parentTablet, clock = FakeClock(Instant.parse("2026-11-01T21:58:00Z")))
        early.chore(40)
        val events = parent.written + early.written

        assertEquals(Money(100), facts(events, dateOf = dateAt(2)).earned.chores)
        assertEquals(Money.ZERO, facts(events, dateOf = dateAt(0)).earned.chores)
        val lastWeek = facts(events, CalendarDate(2026, 11, 1), dateAt(2), week45.previous())
        assertEquals(Money(40), lastWeek.earned.chores)
        assertEquals(Money(140), facts(events, CalendarDate(2026, 11, 1), dateAt(0), week45.previous()).earned.chores)
    }
}

@RunWith(RobolectricTestRunner::class)
class WeeklyFactsUseCaseTest {
    private val household = testHousehold()
    private val facts = WeeklyFactsUseCase(
        household.kids,
        household.chores,
        household.ledger,
        household.calendar,
        { household.gbp },
    )

    @After
    fun close() = household.close()

    @Test
    fun anApprovedChoreIsInThisWeekThenInLastWeek() = runTest {
        val (kid, _) = household.addKid()
        household.registerParentKey()
        val chore = Chore(
            ChoreId("tidy"),
            LocalTrial.HOUSEHOLD,
            kid.id,
            "Tidy your room",
            reward = ChoreReward(money = Money(50), minutes = Money(15)),
            schedule = ChoreSchedule.Weekly(DayOfWeek.MONDAY),
            createdAt = household.clock.now(),
        )
        household.chores.upsert(chore)
        val submission = household.chores.submit(chore.id, CalendarDate(2026, 11, 2))
        assertTrue(household.approve(submission.id) { household.authorization() } is ApprovalResult.Approved)
        assertEquals(
            ManualAdjustmentResult.Signed(AppendResult.Accepted),
            household.adjust(kid.id, Money(500), "jar") { household.authorization() },
        )

        val thisWeek = facts()
        assertEquals(IsoWeek(2026, 45), thisWeek.week)
        assertEquals(CalendarDate(2026, 11, 2), thisWeek.through)
        val leo = thisWeek.kids.single()
        assertEquals(EarnedByKind(chores = Money(50)), leo.earned)
        assertEquals(Money(15), leo.choreMinutes)
        assertEquals(1, leo.choresSubmitted)
        assertEquals(1, leo.choresApproved)

        household.clock.advanceBy(7.days)
        assertTrue(facts().isEmpty)
        val lastWeek = facts(facts.currentWeek().previous())
        assertEquals(thisWeek.copy(through = CalendarDate(2026, 11, 8), complete = true), lastWeek)
        assertFailsWith<IllegalArgumentException> { facts(IsoWeek(2026, 47)) }
    }
}

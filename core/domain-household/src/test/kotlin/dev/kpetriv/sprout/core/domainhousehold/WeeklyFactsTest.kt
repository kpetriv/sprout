package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.KidId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeeklyFactsTest {
    private val gbp = Currency.Fiat("GBP")
    private val week45 = IsoWeek(2026, 45)

    @Test
    fun mondayAndSundayOfKnownWeeks() {
        assertEquals(CalendarDate(2026, 11, 2), week45.monday())
        assertEquals(CalendarDate(2026, 11, 8), week45.sunday())
        // Week 1 of 2025 starts in December 2024; week 53 of 2020 ends in January 2021.
        assertEquals(CalendarDate(2024, 12, 30), IsoWeek(2025, 1).monday())
        assertEquals(CalendarDate(2021, 1, 3), IsoWeek(2020, 53).sunday())
        assertEquals(CalendarDate(2027, 1, 3), IsoWeek(2026, 53).sunday())
    }

    @Test
    fun everyWeekOfSeveralYearsRoundTrips() {
        var date = CalendarDate(2019, 12, 30)
        while (date < CalendarDate(2028, 1, 10)) {
            val week = IsoWeek.of(date)
            assertEquals(DayOfWeek.MONDAY, week.monday().dayOfWeek)
            assertTrue(date in week)
            assertTrue(date in week.monday()..week.sunday())
            date = date.plusDays(1)
        }
    }

    @Test
    fun aWeek53ThatDoesNotExistIsRejected() {
        assertFailsWith<IllegalArgumentException> { IsoWeek(2025, 53).monday() }
    }

    @Test
    fun previousCrossesYears() {
        assertEquals(IsoWeek(2026, 44), week45.previous())
        assertEquals(IsoWeek(2020, 53), IsoWeek(2021, 1).previous())
        assertEquals(IsoWeek(2024, 52), IsoWeek(2025, 1).previous())
    }

    @Test
    fun totalsSumEveryKid() {
        val leo = KidWeeklyFacts(
            KidId("leo"),
            "Leo",
            earned = EarnedByKind(chores = Money(150), allowance = Money(200), games = Money(35)),
            spentOnRewards = Money(100),
            minutesUsed = Money(30),
            choresSubmitted = 3,
            choresApproved = 2,
            choresRejected = 1,
            gamesPlayed = 4,
        )
        val mia = KidWeeklyFacts(
            KidId("mia"),
            "Mia",
            earned = EarnedByKind(interest = Money(2)),
            movedToSavings = Money(500),
            choreMinutes = Money(15),
            anomalies = 1,
        )
        val facts = WeeklyFacts(week45, week45.monday(), week45.sunday(), complete = true, gbp, listOf(leo, mia))

        with(facts.totals) {
            assertEquals(Money(387), earned.total)
            assertEquals(EarnedByKind(Money(150), Money(200), Money(35), Money(2)), earned)
            assertEquals(Money(100), spentOnRewards)
            assertEquals(Money(30), minutesUsed)
            assertEquals(Money(500), movedToSavings)
            assertEquals(Money(15), choreMinutes)
            assertEquals(3, choresSubmitted)
            assertEquals(2, choresApproved)
            assertEquals(1, choresRejected)
            assertEquals(4, gamesPlayed)
            assertEquals(1, anomalies)
        }
        assertFalse(facts.isEmpty)
    }

    @Test
    fun aWeekSoFarEndsBeforeSunday() {
        val soFar = WeeklyFacts(week45, week45.monday(), CalendarDate(2026, 11, 4), complete = false, gbp, emptyList())
        assertTrue(soFar.isEmpty)
        assertFailsWith<IllegalArgumentException> {
            WeeklyFacts(week45, week45.monday(), CalendarDate(2026, 11, 4), complete = true, gbp, emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            WeeklyFacts(week45, week45.monday(), CalendarDate(2026, 11, 9), complete = false, gbp, emptyList())
        }
    }

    @Test
    fun toStringRedactsTheName() {
        val kid = KidWeeklyFacts(KidId("leo"), "Leo")
        assertFalse("Leo" in kid.toString())
        assertTrue(kid.isEmpty)
        assertFailsWith<IllegalArgumentException> { KidWeeklyFacts(KidId("leo"), "Leo", gamesPlayed = -1) }
        assertFailsWith<IllegalArgumentException> { KidWeeklyFacts(KidId("leo"), "Leo", minutesUsed = Money(-1)) }
    }
}

package dev.kpetriv.sprout.core.ai

import dev.kpetriv.sprout.core.domainhousehold.EarnedByKind
import dev.kpetriv.sprout.core.domainhousehold.IsoWeek
import dev.kpetriv.sprout.core.domainhousehold.KidWeeklyFacts
import dev.kpetriv.sprout.core.domainhousehold.WeeklyFacts
import dev.kpetriv.sprout.core.domainhousehold.monday
import dev.kpetriv.sprout.core.domainhousehold.sunday
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.KidId

internal object TestFacts {
    val gbp = Currency.Fiat("GBP")
    val week = IsoWeek(2026, 45)

    val leo = KidWeeklyFacts(
        KidId("leo"),
        "Leo",
        earned = EarnedByKind(chores = Money(150), allowance = Money(200), games = Money(45), interest = Money(3)),
        choreMinutes = Money(15),
        spentOnRewards = Money(100),
        minutesUsed = Money(30),
        movedToSavings = Money(50),
        choresSubmitted = 3,
        choresApproved = 2,
        choresRejected = 1,
        gamesPlayed = 4,
        anomalies = 1,
    )

    val mia = KidWeeklyFacts(KidId("mia"), "Mia", earned = EarnedByKind(chores = Money(50)), choresApproved = 1)

    fun facts(
        vararg kids: KidWeeklyFacts = arrayOf(leo),
        complete: Boolean = false,
        currency: Currency.Fiat = gbp,
    ): WeeklyFacts = WeeklyFacts(
        week,
        week.monday(),
        if (complete) week.sunday() else CalendarDate(2026, 11, 4),
        complete,
        currency,
        kids.toList(),
    )
}

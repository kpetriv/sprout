package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.KidId

/**
 * What the household did in one ISO week, computed deterministically from the ledger and the chore submissions
 * (`WeeklyFactsUseCase` in core/database, D-M6-3). The weekly summary only ever rewrites these facts: every number in
 * a summary must be one of them. Never persisted (D-M6-7).
 *
 * @property week the ISO week the facts cover.
 * @property from the week's Monday.
 * @property through the last day counted: the week's Sunday, or today while the week is still running.
 * @property complete false for the current week so far, true once [through] is the week's Sunday.
 * @property currency the household's money currency; every [Money] here except minutes is in it, and events in any
 *   other fiat currency are left out.
 * @property kids one entry per active kid, in the kid list's order (kids with no activity included, all zeros).
 */
data class WeeklyFacts(
    val week: IsoWeek,
    val from: CalendarDate,
    val through: CalendarDate,
    val complete: Boolean,
    val currency: Currency.Fiat,
    val kids: List<KidWeeklyFacts>,
) {
    init {
        require(from == week.monday()) { "from must be the week's Monday" }
        require(through in from..week.sunday()) { "through must fall in the week" }
        require(complete == (through == week.sunday())) { "complete must say whether through is the week's Sunday" }
    }

    /** The household's totals over [kids]. */
    val totals: WeeklyTotals get() = WeeklyTotals.of(kids)

    /** True when nothing at all happened in the week (or the household has no kids). */
    val isEmpty: Boolean get() = kids.all { it.isEmpty }
}

/**
 * Money a kid earned in the week, by where it came from, in the household currency.
 *
 * @property chores approved chores (`ChoreRewarded`, money part).
 * @property allowance allowance payments (`AllowancePaid`).
 * @property games brain-game credits (`GamePlayed`).
 * @property interest savings interest (`InterestAccrued`).
 */
data class EarnedByKind(
    val chores: Money = Money.ZERO,
    val allowance: Money = Money.ZERO,
    val games: Money = Money.ZERO,
    val interest: Money = Money.ZERO,
) {
    val total: Money get() = chores + allowance + games + interest

    operator fun plus(other: EarnedByKind): EarnedByKind = EarnedByKind(
        chores + other.chores,
        allowance + other.allowance,
        games + other.games,
        interest + other.interest,
    )
}

/**
 * One kid's week. [firstName] is personal data: [toString] redacts it.
 *
 * @property earned money earned by kind.
 * @property choreMinutes screen-time minutes earned from approved chores (`ChoreRewarded` in minutes).
 * @property spentOnRewards money spent in the rewards shop (`RewardPurchased`).
 * @property minutesUsed screen-time minutes used (`TimeBankStopped`).
 * @property movedToSavings money moved from the main account to savings goals (`TransferToSavings`).
 * @property choresSubmitted chore occurrences the kid marked done in the week (by submission time).
 * @property choresApproved submissions a parent approved in the week (by decision time).
 * @property choresRejected submissions a parent sent back in the week (by decision time).
 * @property gamesPlayed brain-game plays credited in the week (`GamePlayed`; a play that earns nothing writes no event,
 *   so it is not counted).
 * @property anomalies ledger anomalies about this kid whose events were created in the week (overdrafts, excluded
 *   games, invalid events, conflicting copies).
 */
data class KidWeeklyFacts(
    val kidId: KidId,
    val firstName: String,
    val earned: EarnedByKind = EarnedByKind(),
    val choreMinutes: Money = Money.ZERO,
    val spentOnRewards: Money = Money.ZERO,
    val minutesUsed: Money = Money.ZERO,
    val movedToSavings: Money = Money.ZERO,
    val choresSubmitted: Int = 0,
    val choresApproved: Int = 0,
    val choresRejected: Int = 0,
    val gamesPlayed: Int = 0,
    val anomalies: Int = 0,
) {
    init {
        require(firstName.isNotBlank()) { "firstName must not be blank" }
        val counts = listOf(choresSubmitted, choresApproved, choresRejected, gamesPlayed, anomalies)
        require(counts.all { it >= 0 }) { "counts must not be negative" }
        val amounts = listOf(
            earned.chores,
            earned.allowance,
            earned.games,
            earned.interest,
            choreMinutes,
            spentOnRewards,
            minutesUsed,
            movedToSavings,
        )
        require(amounts.none { it.isNegative }) { "amounts must not be negative" }
    }

    val isEmpty: Boolean
        get() = earned.total == Money.ZERO && choreMinutes == Money.ZERO && spentOnRewards == Money.ZERO &&
            minutesUsed == Money.ZERO && movedToSavings == Money.ZERO && choresSubmitted == 0 &&
            choresApproved == 0 && choresRejected == 0 && gamesPlayed == 0 && anomalies == 0

    override fun toString(): String =
        "KidWeeklyFacts(kidId=$kidId, firstName=$REDACTED, earned=$earned, choreMinutes=$choreMinutes, " +
            "spentOnRewards=$spentOnRewards, minutesUsed=$minutesUsed, movedToSavings=$movedToSavings, " +
            "choresSubmitted=$choresSubmitted, choresApproved=$choresApproved, choresRejected=$choresRejected, " +
            "gamesPlayed=$gamesPlayed, anomalies=$anomalies)"
}

/** The household's sums over every kid of a [WeeklyFacts]. */
data class WeeklyTotals(
    val earned: EarnedByKind,
    val choreMinutes: Money,
    val spentOnRewards: Money,
    val minutesUsed: Money,
    val movedToSavings: Money,
    val choresSubmitted: Int,
    val choresApproved: Int,
    val choresRejected: Int,
    val gamesPlayed: Int,
    val anomalies: Int,
) {
    companion object {
        fun of(kids: List<KidWeeklyFacts>): WeeklyTotals = WeeklyTotals(
            earned = kids.fold(EarnedByKind()) { sum, kid -> sum + kid.earned },
            choreMinutes = kids.sumMoney { it.choreMinutes },
            spentOnRewards = kids.sumMoney { it.spentOnRewards },
            minutesUsed = kids.sumMoney { it.minutesUsed },
            movedToSavings = kids.sumMoney { it.movedToSavings },
            choresSubmitted = kids.sumOf { it.choresSubmitted },
            choresApproved = kids.sumOf { it.choresApproved },
            choresRejected = kids.sumOf { it.choresRejected },
            gamesPlayed = kids.sumOf { it.gamesPlayed },
            anomalies = kids.sumOf { it.anomalies },
        )

        private fun List<KidWeeklyFacts>.sumMoney(of: (KidWeeklyFacts) -> Money): Money =
            fold(Money.ZERO) { sum, kid -> sum + of(kid) }
    }
}

/** The Monday that starts this ISO week. Throws [IllegalArgumentException] for week 53 of a 52-week year. */
fun IsoWeek.monday(): CalendarDate {
    // 4 January is always in week 1, so week 1 starts on the Monday on or before it.
    val january4 = CalendarDate(weekBasedYear, 1, JANUARY_4)
    val monday = january4.plusDays(-january4.dayOfWeek.ordinal).plusDays((week - 1) * DayOfWeek.DAYS_PER_WEEK)
    require(IsoWeek.of(monday) == this) { "$this does not exist" }
    return monday
}

/** The Sunday that ends this ISO week. */
fun IsoWeek.sunday(): CalendarDate = monday().plusDays(DayOfWeek.DAYS_PER_WEEK - 1)

/** The ISO week before this one. */
fun IsoWeek.previous(): IsoWeek = IsoWeek.of(monday().plusDays(-1))

/** Whether [date] falls in this ISO week. */
operator fun IsoWeek.contains(date: CalendarDate): Boolean = IsoWeek.of(date) == this

private const val JANUARY_4 = 4

package dev.kpetriv.sprout.core.database.usecase

import dev.kpetriv.sprout.core.database.ChoreRepository
import dev.kpetriv.sprout.core.database.FiatCurrencyProvider
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.database.RoomLedgerRepository
import dev.kpetriv.sprout.core.database.SubmissionFilter
import dev.kpetriv.sprout.core.domainhousehold.EarnedByKind
import dev.kpetriv.sprout.core.domainhousehold.IsoWeek
import dev.kpetriv.sprout.core.domainhousehold.KidWeeklyFacts
import dev.kpetriv.sprout.core.domainhousehold.WeeklyFacts
import dev.kpetriv.sprout.core.domainhousehold.monday
import dev.kpetriv.sprout.core.domainhousehold.sunday
import dev.kpetriv.sprout.core.domainledger.AllowancePaid
import dev.kpetriv.sprout.core.domainledger.ChoreRewarded
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.GamePlayed
import dev.kpetriv.sprout.core.domainledger.InterestAccrued
import dev.kpetriv.sprout.core.domainledger.LedgerAnomaly
import dev.kpetriv.sprout.core.domainledger.LedgerEvent
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.MovesValue
import dev.kpetriv.sprout.core.domainledger.Projection
import dev.kpetriv.sprout.core.domainledger.RewardPurchased
import dev.kpetriv.sprout.core.domainledger.TimeBankStopped
import dev.kpetriv.sprout.core.domainledger.TransferToSavings
import dev.kpetriv.sprout.core.domainledger.anomaliesFor
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreSubmission
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.SubmissionStatus
import dev.kpetriv.sprout.core.time.HouseholdCalendar
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import kotlin.time.Instant

/**
 * The facts of one ISO week for the parent dashboard's weekly summary (M6 §2, D-M6-3): per active kid, money earned by
 * kind, spent and moved to savings, screen-time minutes earned and used, chores submitted, approved and rejected,
 * games played and ledger anomalies; [WeeklyFacts.totals] sums the household.
 *
 * Read-only and recomputed on every call (nothing is persisted, D-M6-7). Each event and submission belongs to the
 * household-local day of its timestamp ([HouseholdCalendar.dateOf]): an event's `createdAt` (wall clock, display only,
 * ADR-0002, so an event written offline with a wrong device clock lands on the wrong day), a submission's
 * `submittedAt` for "submitted" and its `decidedAt` for "approved" or "rejected". Only events the projection applied
 * count: invalid ones, games over the daily cap or outside their played-on window and the losing copies of a
 * conflicting duplicate are left out. Money is the household policy's currency, read on every call
 * ([FiatCurrencyProvider.currentFiatCurrency], D-REL-10); events in another fiat currency are ignored.
 *
 * Cost (review L15): submissions are read per active kid; the ledger is read once as the verified snapshot, because
 * only the household projection says which events were applied (caps, conflicts and invalid events span the stream).
 * The calculator then keeps only the active kids' events of the week before folding. Recomputation is bounded by the
 * app (D-M6-28: only when the facts' tables change while the card is shown).
 */
class WeeklyFactsUseCase @Inject constructor(
    private val kids: KidRepository,
    private val chores: ChoreRepository,
    private val ledger: RoomLedgerRepository,
    private val calendar: HouseholdCalendar,
    private val currency: FiatCurrencyProvider,
) {
    /** The ISO week holding the household's today. */
    fun currentWeek(): IsoWeek = IsoWeek.of(calendar.today())

    /**
     * The facts of [week]: up to today for the current week ([WeeklyFacts.complete] false), the whole week for a past
     * one. A week after the current one is refused with [IllegalArgumentException].
     */
    suspend operator fun invoke(week: IsoWeek = currentWeek()): WeeklyFacts {
        val today = calendar.today()
        require(week <= IsoWeek.of(today)) { "$week has not started yet" }
        val through = minOf(week.sunday(), today)
        val snapshot = ledger.snapshot()
        val activeKids = kids.observeKids().first()
        val submissions = activeKids.associate { kid ->
            kid.id to chores.observeSubmissions(SubmissionFilter.ForKid(kid.id)).first()
        }
        return WeeklyFactsCalculator.compute(
            week = week,
            through = through,
            currency = currency.currentFiatCurrency(),
            kids = activeKids,
            events = snapshot.events,
            projection = snapshot.projection,
            submissions = submissions,
            dateOf = calendar::dateOf,
        )
    }
}

/** The pure part of [WeeklyFactsUseCase]: folds events and submissions into [WeeklyFacts]. */
internal object WeeklyFactsCalculator {
    @Suppress("LongParameterList") // The week, its data and how timestamps map to days.
    fun compute(
        week: IsoWeek,
        through: CalendarDate,
        currency: Currency.Fiat,
        kids: List<Kid>,
        events: List<LedgerEvent>,
        projection: Projection,
        submissions: Map<KidId, List<ChoreSubmission>>,
        dateOf: (Instant) -> CalendarDate,
    ): WeeklyFacts {
        val from = week.monday()
        val days = from..through
        val inWeek = { instant: Instant -> dateOf(instant) in days }
        val kidIds = kids.mapTo(HashSet()) { it.id }
        val ofKids = events.filter { it.envelope.kidId in kidIds }
        val applied = appliedEvents(ofKids, projection.anomalies).filter { inWeek(it.envelope.createdAt) }
        val createdById = ofKids.groupBy({ it.eventId }, { it.envelope.createdAt })
        val facts = kids.map { kid ->
            val anomalies = projection.anomaliesFor(kid.id).count { anomaly ->
                anomaly.eventIds().any { id -> createdById[id].orEmpty().any(inWeek) }
            }
            kidFacts(kid, currency, applied.filter { it.envelope.kidId == kid.id }, anomalies)
                .withSubmissions(submissions[kid.id].orEmpty(), inWeek)
        }
        return WeeklyFacts(week, from, through, complete = through == week.sunday(), currency, facts)
    }

    /**
     * One copy of every event the projection applied: invalid events, excluded games and the copies of a conflicting
     * duplicate other than the applied one are dropped.
     */
    private fun appliedEvents(events: List<LedgerEvent>, anomalies: List<LedgerAnomaly>): List<LedgerEvent> {
        val excluded = anomalies.flatMap { anomaly ->
            when (anomaly) {
                is LedgerAnomaly.InvalidEvent -> listOf(anomaly.eventId)
                is LedgerAnomaly.DailyCapExceeded -> listOf(anomaly.eventId)
                is LedgerAnomaly.PlayedOnOutOfRange -> listOf(anomaly.eventId)
                is LedgerAnomaly.OverdraftDetected, is LedgerAnomaly.ConflictingDuplicate -> emptyList()
            }
        }.toSet()
        val appliedCopy = anomalies.filterIsInstance<LedgerAnomaly.ConflictingDuplicate>()
            .associate { it.eventId to it.applied?.hash }
        return events
            .filter { it.eventId !in excluded }
            .filter { event -> event.eventId !in appliedCopy || appliedCopy[event.eventId] == event.envelope.hash }
            .distinctBy { it.eventId }
    }

    private fun LedgerAnomaly.eventIds(): List<EventId> = when (this) {
        is LedgerAnomaly.OverdraftDetected -> causedBy
        is LedgerAnomaly.DailyCapExceeded -> listOf(eventId)
        is LedgerAnomaly.PlayedOnOutOfRange -> listOf(eventId)
        is LedgerAnomaly.InvalidEvent -> listOf(eventId)
        is LedgerAnomaly.ConflictingDuplicate -> listOf(eventId)
    }

    private fun kidFacts(kid: Kid, currency: Currency.Fiat, events: List<LedgerEvent>, anomalies: Int): KidWeeklyFacts {
        val fiat = events.filter { it.currency == currency }
        val minutes = events.filter { it.currency == Currency.Minutes }
        return KidWeeklyFacts(
            kidId = kid.id,
            firstName = kid.firstName,
            earned = EarnedByKind(
                chores = fiat.filterIsInstance<ChoreRewarded>().total(),
                allowance = fiat.filterIsInstance<AllowancePaid>().total(),
                games = fiat.filterIsInstance<GamePlayed>().total(),
                interest = fiat.filterIsInstance<InterestAccrued>().total(),
            ),
            choreMinutes = minutes.filterIsInstance<ChoreRewarded>().total(),
            spentOnRewards = fiat.filterIsInstance<RewardPurchased>().total(),
            minutesUsed = minutes.filterIsInstance<TimeBankStopped>().total(),
            movedToSavings = fiat.filterIsInstance<TransferToSavings>().total(),
            gamesPlayed = fiat.count { it is GamePlayed },
            anomalies = anomalies,
        )
    }

    private fun List<MovesValue>.total(): Money = fold(Money.ZERO) { sum, event -> sum + event.amount }

    private fun KidWeeklyFacts.withSubmissions(
        submissions: List<ChoreSubmission>,
        inWeek: (Instant) -> Boolean,
    ): KidWeeklyFacts {
        fun decidedInWeek(status: SubmissionStatus) =
            submissions.count { it.status == status && it.decidedAt?.let(inWeek) == true }
        return copy(
            choresSubmitted = submissions.count { inWeek(it.submittedAt) },
            choresApproved = decidedInWeek(SubmissionStatus.APPROVED),
            choresRejected = decidedInWeek(SubmissionStatus.REJECTED),
        )
    }
}

package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.domainhousehold.KidToday
import dev.kpetriv.sprout.core.domainhousehold.TodayStatus
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.LedgerRepository
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.time.HouseholdCalendar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * A kid's day at a glance (M7 §4, the widget): the money balance (the MAIN fiat account), the screen-time minutes
 * (the MAIN minutes account), and today's chores ([KidToday], the same list the kid's today screen shows). Kid names
 * and chore titles are personal data: `toString` prints counts only.
 *
 * @property fiatBalance the MAIN fiat account's balance in [fiatCurrency]; null when the kid has no such account.
 * @property minutes the MAIN minutes account's balance; null when the kid has none.
 * @property dueToday every chore due today, whatever its status.
 * @property toDo the chores due today still to do ([TodayStatus.TO_DO]).
 * @property firstToDoTitles the titles of the first two of [toDo], in the today screen's order.
 */
@Suppress("LongParameterList") // One field per fact the widget shows.
class KidTodaySummary(
    val kidId: KidId,
    val kidName: String,
    val tier: KidUiTier,
    val date: CalendarDate,
    val fiatCurrency: Currency.Fiat?,
    val fiatBalance: Money?,
    val minutes: Money?,
    val dueToday: Int,
    val toDo: Int,
    val firstToDoTitles: List<String>,
) {
    override fun equals(other: Any?): Boolean = other is KidTodaySummary && kidId == other.kidId &&
        kidName == other.kidName && tier == other.tier && date == other.date && fiatCurrency == other.fiatCurrency &&
        fiatBalance == other.fiatBalance && minutes == other.minutes && dueToday == other.dueToday &&
        toDo == other.toDo && firstToDoTitles == other.firstToDoTitles

    override fun hashCode(): Int = listOf(kidId, date, fiatBalance, minutes, dueToday, toDo).hashCode()

    override fun toString(): String = "KidTodaySummary(kidId=$kidId, date=$date, dueToday=$dueToday, toDo=$toDo)"

    companion object {
        /** How many to-do titles a summary carries. */
        const val TITLES: Int = 2
    }
}

/**
 * [KidTodaySummary] of a kid from the repositories (M7 §4): what the home screen widget shows on that kid's linked
 * phone. "Today" is the household calendar's ([HouseholdCalendar]) and moves on at midnight. No session check here:
 * the widget decides where it shows kid data (D-M7-1).
 */
class KidTodaySummaryUseCase @Inject constructor(
    private val kids: KidRepository,
    private val chores: ChoreRepository,
    private val accounts: AccountRepository,
    private val ledger: LedgerRepository,
    private val calendar: HouseholdCalendar,
) {
    /** [kidId]'s summary, re-emitted on every change; null while the kid is unknown or archived. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(kidId: KidId): Flow<KidTodaySummary?> =
        kids.observeKids().map { list -> list.firstOrNull { it.id == kidId } }.distinctUntilChanged()
            .flatMapLatest { kid ->
                if (kid == null) {
                    flowOf(null)
                } else {
                    combine(
                        chores.observeChores(kidId),
                        chores.observeSubmissions(SubmissionFilter.ForKid(kidId)),
                        combine(accounts.accountsFor(kidId), ledger.observeBalances(kidId), ::Pair),
                        calendar.todayUpdates(),
                    ) { choreList, submissions, (accountList, balances), today ->
                        val occurrences = KidToday.occurrences(choreList, submissions, today)
                        val toDo = occurrences.filter { it.status == TodayStatus.TO_DO }
                        val byAccount = balances.associateBy { it.accountId }
                        val fiat = accountList.firstOrNull {
                            it.kind == AccountKind.MAIN && it.currency is Currency.Fiat
                        }
                        val minutes = accountList.firstOrNull {
                            it.kind == AccountKind.MAIN && it.currency == Currency.Minutes
                        }
                        KidTodaySummary(
                            kidId = kidId,
                            kidName = kid.firstName,
                            tier = kid.uiTier,
                            date = today,
                            fiatCurrency = fiat?.currency as? Currency.Fiat,
                            fiatBalance = fiat?.let { byAccount[it.id]?.amount ?: Money.ZERO },
                            minutes = minutes?.let { byAccount[it.id]?.amount ?: Money.ZERO },
                            dueToday = occurrences.size,
                            toDo = toDo.size,
                            firstToDoTitles = toDo.take(KidTodaySummary.TITLES).map { it.chore.title },
                        )
                    }
                }
            }.distinctUntilChanged()

    /** [kidId]'s summary now (one read of [observe]). */
    suspend fun get(kidId: KidId): KidTodaySummary? = observe(kidId).first()
}

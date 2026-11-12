package dev.kpetriv.sprout.core.database.usecase

import dev.kpetriv.sprout.core.database.RoomLedgerRepository
import dev.kpetriv.sprout.core.domainledger.GamePlayed
import dev.kpetriv.sprout.core.domainledger.LedgerOrdering
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.games.GameId
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.time.HouseholdCalendar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import javax.inject.Inject

/**
 * One credited game play as the ledger records it ([GamePlayed]): the household-local day it was played, the game,
 * how many rounds were right, and what it credited (after the daily cap). No time per play is recorded (D-M9-G6).
 */
data class GamePlaySummary(val playedOn: CalendarDate, val gameId: GameId, val score: Int, val amount: Money)

/**
 * A kid's past plays of one game (M9-games §6.2): the Sapling start card's "Your last plays" and the Times tables
 * setup's "Best this week". Read from the ledger's [GamePlayed] events the household projection credits
 * ([RoomLedgerRepository.creditedGamePlays]: a play over the cap or outside its played-on window is left out, as in
 * the balance), never from a separate history table. Re-reads whenever the kid's ledger caches change (a play here,
 * or one ingested from another device), like [GameCapReader]; the weekly best also moves on at household-local
 * midnight ([HouseholdCalendar.todayUpdates]). Both flows skip repeats.
 *
 * A play is a game of a [GameKind] at any difficulty (a kid who moved up a tier sees both); only plays that credited
 * something exist, since a play that earned nothing or hit the cap appends no event (D-M5-7).
 */
class GameHistoryReader @Inject constructor(
    private val ledger: RoomLedgerRepository,
    private val calendar: HouseholdCalendar,
) {
    /** The newest [limit] plays of [kind] by [kidId], newest first (by `playedOn`, then ledger order). */
    fun observeRecent(kidId: KidId, kind: GameKind, limit: Int = DEFAULT_RECENT): Flow<List<GamePlaySummary>> {
        require(limit > 0) { "limit must be positive" }
        return plays(kidId, kind).map { it.take(limit) }.distinctUntilChanged()
    }

    /**
     * The best `score` of [kidId]'s plays of [kind] in the last seven household-local days (today and the six before
     * it), or null when there was none.
     */
    fun observeBestScoreThisWeek(kidId: KidId, kind: GameKind): Flow<Int?> =
        combine(plays(kidId, kind), calendar.todayUpdates()) { plays, today ->
            val since = today.plusDays(-(WEEK_DAYS - 1))
            plays.filter { it.playedOn in since..today }.maxOfOrNull { it.score }
        }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class) // mapLatest: a newer change supersedes a pending read.
    private fun plays(kidId: KidId, kind: GameKind): Flow<List<GamePlaySummary>> =
        ledger.observeBalances(kidId)
            .mapLatest {
                ledger.creditedGamePlays(kidId)
                    .sortedWith(NEWEST_FIRST)
                    .mapNotNull { game -> game.summary()?.takeIf { it.gameId.kind == kind } }
            }
            .distinctUntilChanged()

    /** Null for an id no catalogue game has (the projection already excludes those as invalid). */
    private fun GamePlayed.summary(): GamePlaySummary? = GameId.parse(gameId)?.let { id ->
        GamePlaySummary(playedOn, id, score.toInt(), amount)
    }

    private companion object {
        const val DEFAULT_RECENT = 3
        const val WEEK_DAYS = 7

        val NEWEST_FIRST: Comparator<GamePlayed> =
            compareByDescending<GamePlayed> { it.playedOn }.then { a, b -> LedgerOrdering.compare(b, a) }
    }
}

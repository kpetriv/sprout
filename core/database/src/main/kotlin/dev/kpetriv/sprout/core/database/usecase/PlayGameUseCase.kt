package dev.kpetriv.sprout.core.database.usecase

import dev.kpetriv.sprout.core.database.AccountRepository
import dev.kpetriv.sprout.core.database.RoomLedgerRepository
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.GameCreditResult
import dev.kpetriv.sprout.core.domainledger.GamePlayed
import dev.kpetriv.sprout.core.domainledger.LedgerWriter
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.Projection
import dev.kpetriv.sprout.core.domainledger.Violation
import dev.kpetriv.sprout.core.games.GameCatalogue
import dev.kpetriv.sprout.core.games.GameCredit
import dev.kpetriv.sprout.core.games.GameId
import dev.kpetriv.sprout.core.games.Score
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.time.HouseholdCalendar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import javax.inject.Inject

/** [dev.kpetriv.sprout.core.domainledger.AppendResult.Rejected.reason] for a score outside its game's rounds. */
const val INVALID_SCORE_REJECTION: String = "score out of range"

/**
 * Credits one finished game play (M5 §3, ADR-0009). Computes what [score] earns in [gameId] ([GameCredit.earned]),
 * reads what is left of today's game cap for the kid's fiat MAIN account (games credit fiat only, D-M5-4) from the
 * ledger projection, and appends one kid-authored [GamePlayed] for `min(earned, remaining)`: the ledger allows any
 * amount up to what the play earns, so a clamped play is still valid on every device. Nothing is appended when the
 * cap is used up ([GameCreditResult.CapReached], the play was "for fun", D-M5-7) or the play earned nothing.
 *
 * `playedOn` is the household's today ([HouseholdCalendar]); the ledger refuses a play dated outside the played-on
 * window of the device date (D-M5-5). Two plays submitted at once may both read the same remaining cap; the second
 * one's append then fails the cap rule and comes back [GameCreditResult.Refused].
 */
class PlayGameUseCase @Inject constructor(
    private val accounts: AccountRepository,
    private val ledger: RoomLedgerRepository,
    private val writer: LedgerWriter,
    private val calendar: HouseholdCalendar,
) {
    @Suppress("ReturnCount") // One early return per GameCreditResult outcome.
    suspend operator fun invoke(kidId: KidId, gameId: GameId, score: Score): GameCreditResult {
        val main = accounts.accountsFor(kidId).first().fiatMain() ?: return GameCreditResult.NoAccount
        val earned = GameCredit.earned(gameId, score)?.let(::Money)
            ?: return GameCreditResult.Refused(invalidScore(gameId, score))
        val today = calendar.today()
        val remaining = remainingCap(ledger.projection(), kidId, main, today)
        val amount = minOf(earned, remaining)
        return when {
            earned == Money.ZERO -> GameCreditResult.Credited(Money.ZERO, remaining, earned)

            amount == Money.ZERO -> GameCreditResult.CapReached

            else -> when (val result = append(kidId, main, gameId, score, amount, today)) {
                AppendResult.Accepted -> GameCreditResult.Credited(amount, remaining - amount, earned)
                else -> GameCreditResult.Refused(result)
            }
        }
    }

    @Suppress("LongParameterList") // The event's fields.
    private suspend fun append(
        kidId: KidId,
        main: Account,
        gameId: GameId,
        score: Score,
        amount: Money,
        today: CalendarDate,
    ): AppendResult = writer.appendAsKid(kidId, main.id) { envelope ->
        GamePlayed(envelope, main.currency, amount, gameId.wire, score.correct.toLong(), today)
    }

    private fun invalidScore(gameId: GameId, score: Score) = AppendResult.Rejected(
        INVALID_SCORE_REJECTION,
        listOf(Violation.ScoreOutOfRange(score.correct.toLong(), GameCatalogue.rounds(gameId))),
    )
}

/**
 * What is left of today's game cap for a kid (M5 §3), for the games hub. Projection-backed: re-emits whenever the
 * kid's ledger caches change (a play here, or one ingested from another device) and after every household-local
 * midnight ([HouseholdCalendar.todayUpdates]), without repeats. Zero when the kid has no fiat MAIN account.
 */
class GameCapReader @Inject constructor(
    private val accounts: AccountRepository,
    private val ledger: RoomLedgerRepository,
    private val calendar: HouseholdCalendar,
) {
    @OptIn(ExperimentalCoroutinesApi::class) // mapLatest: a newer change or day supersedes a pending read.
    fun observeRemaining(kidId: KidId): Flow<Money> = combine(
        accounts.accountsFor(kidId),
        ledger.observeBalances(kidId),
        calendar.todayUpdates(),
    ) { kidAccounts, _, today -> kidAccounts.fiatMain() to today }
        .mapLatest { (main, today) ->
            main?.let { remainingCap(ledger.projection(), kidId, it, today) } ?: Money.ZERO
        }
        .distinctUntilChanged()
}

/** Today's cap for [main]'s currency minus what [kidId]'s games already credited on [today]; never negative. */
private fun remainingCap(projection: Projection, kidId: KidId, main: Account, today: CalendarDate): Money {
    val cap = projection.policy.dailyGameCapFor(main.currency)
    val credited = projection.dailyGameCredits(kidId, today, main.currency)
    // The projection never credits past the cap, so this is never negative; both are bounded, so it cannot overflow.
    return maxOf(cap - credited, Money.ZERO)
}

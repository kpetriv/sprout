package dev.kpetriv.sprout.core.database.usecase

import dev.kpetriv.sprout.core.database.AccountRepository
import dev.kpetriv.sprout.core.database.RoomLedgerRepository
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.GameCreditResult
import dev.kpetriv.sprout.core.domainledger.GamePlayed
import dev.kpetriv.sprout.core.domainledger.LedgerWriter
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.PlayedOnWindow
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
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/** [dev.kpetriv.sprout.core.domainledger.AppendResult.Rejected.reason] for a score outside its game's rounds. */
const val INVALID_SCORE_REJECTION: String = "score out of range"

/**
 * [dev.kpetriv.sprout.core.domainledger.AppendResult.Rejected.reason] when the device's date is before the played-on
 * window of the newest server stamp the device holds: its clock was moved back, and every receiver would exclude
 * the play (M5 audit L1).
 */
const val DEVICE_DATE_BEHIND_REJECTION: String = "device date is behind the household's server time"

/**
 * Credits one finished game play (M5 §3, ADR-0009). Computes what [score] earns in [gameId] ([GameCredit.earned]),
 * reads what is left of today's game cap for the kid's fiat MAIN account (games credit fiat only, D-M5-4,
 * [RoomLedgerRepository.remainingGameCap]), and appends one kid-authored [GamePlayed] for `min(earned, remaining)`:
 * the ledger allows any amount up to what the play earns, so a clamped play is still valid on every device. Nothing
 * is appended when the cap is used up ([GameCreditResult.CapReached], the play was "for fun", D-M5-7) or the play
 * earned nothing.
 *
 * `playedOn` is the household's today ([HouseholdCalendar]); the ledger refuses a play dated outside the played-on
 * window of the device date (D-M5-5). A play is also refused ([DEVICE_DATE_BEHIND_REJECTION]) when today is before
 * the window of the newest server stamp the device holds ([PlayedOnWindow.isBeforeWindowOf]): the device date is
 * kid-controlled, the server's is not.
 *
 * Plays on one database are serialised ([RoomLedgerRepository.gamePlays]), so two submitted at once read the cap in
 * turn. Should the append still fail the cap rule alone (an event ingested in between), the play is clamped again
 * against the new remainder once (M5 review L1), so it comes back [GameCreditResult.Credited] or
 * [GameCreditResult.CapReached] rather than [GameCreditResult.Refused].
 */
class PlayGameUseCase @Inject constructor(
    private val accounts: AccountRepository,
    private val ledger: RoomLedgerRepository,
    private val writer: LedgerWriter,
    private val calendar: HouseholdCalendar,
) {
    suspend operator fun invoke(kidId: KidId, gameId: GameId, score: Score): GameCreditResult =
        ledger.gamePlays.withLock {
            val first = play(kidId, gameId, score)
            if (first.failedOnlyTheCap()) play(kidId, gameId, score) else first
        }

    @Suppress("ReturnCount") // One early return per GameCreditResult outcome.
    private suspend fun play(kidId: KidId, gameId: GameId, score: Score): GameCreditResult {
        val main = accounts.accountsFor(kidId).first().fiatMain() ?: return GameCreditResult.NoAccount
        val earned = GameCredit.earned(gameId, score)?.let(::Money)
            ?: return GameCreditResult.Refused(invalidScore(gameId, score))
        val today = calendar.today()
        val remaining = ledger.remainingGameCap(kidId, today, main.currency)
        val amount = minOf(earned, remaining)
        return when {
            earned == Money.ZERO -> GameCreditResult.Credited(Money.ZERO, remaining, earned)

            amount == Money.ZERO -> GameCreditResult.CapReached

            else -> deviceDateBehind(today)?.let(GameCreditResult::Refused)
                ?: when (val result = append(kidId, main, gameId, score, amount, today)) {
                    AppendResult.Accepted -> GameCreditResult.Credited(amount, remaining - amount, earned)
                    else -> GameCreditResult.Refused(result)
                }
        }
    }

    /** A rejection when [today] is before the played-on window of the newest server stamp held; null otherwise. */
    private suspend fun deviceDateBehind(today: CalendarDate): AppendResult.Rejected? {
        val serverDate = ledger.newestServerStamp()?.let(PlayedOnWindow::utcDateOf)
            ?.takeIf { PlayedOnWindow.isBeforeWindowOf(today, it) } ?: return null
        val violation = Violation.PlayedOnOutOfRange(today, serverDate)
        return AppendResult.Rejected(DEVICE_DATE_BEHIND_REJECTION, listOf(violation))
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

    /** Refused for the daily cap and nothing else: another play or an ingested one took the remainder meanwhile. */
    private fun GameCreditResult.failedOnlyTheCap(): Boolean {
        val violations = ((this as? GameCreditResult.Refused)?.result as? AppendResult.Rejected)?.violations
        return !violations.isNullOrEmpty() && violations.all { it is Violation.DailyCapExceeded }
    }
}

/**
 * What is left of today's game cap for a kid (M5 §3), for the games hub. Re-emits whenever the kid's ledger caches
 * change (a play here, or one ingested from another device) and after every household-local midnight
 * ([HouseholdCalendar.todayUpdates]), without repeats. Each read is [RoomLedgerRepository.remainingGameCap]: the
 * kid's games of the day, not a rebuilt and re-verified household projection (M5 review L7). Zero when the kid has no
 * fiat MAIN account.
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
            main?.let { ledger.remainingGameCap(kidId, today, it.currency) } ?: Money.ZERO
        }
        .distinctUntilChanged()
}

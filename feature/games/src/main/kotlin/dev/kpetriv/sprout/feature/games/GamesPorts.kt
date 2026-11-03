package dev.kpetriv.sprout.feature.games

import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.games.GameId
import dev.kpetriv.sprout.core.games.Score
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.coroutines.flow.Flow
import kotlin.random.Random

/*
 * Ports of the brain games screens. The app adapts them to `PlayGameUseCase`, `GameCapReader` and the household's
 * fiat currency in `core/database` (M5 §3); this module depends on none of them.
 */

/** Credits a finished play (`PlayGameUseCase`): the ledger re-derives and clamps the amount (D-M5-3, D-M5-7). */
interface GameCreditSubmitter {
    suspend fun submit(kidId: KidId, gameId: GameId, score: Score): GameCreditResult
}

/** What [GameCreditSubmitter.submit] did. */
sealed interface GameCreditResult {
    /**
     * [amount] (> 0, possibly clamped below what the score earned) was credited to the kid's fiat account;
     * [remainingAfter] is what the kid can still earn from games today.
     */
    data class Credited(val amount: Money, val remainingAfter: Money) : GameCreditResult

    /** Today's game cap was already reached: nothing was appended, the play was "for fun" (D-M5-7). */
    data object CapReached : GameCreditResult

    /** The kid has no fiat MAIN account to credit. */
    data object NoAccount : GameCreditResult

    /**
     * The ledger refused the event; [reason] is for diagnostics only and is never shown or logged with personal data.
     * Nothing was credited, so the screen offers a retry.
     */
    data class Refused(val reason: String) : GameCreditResult
}

/** How much the kid can still earn from games today (`GameCapReader`); updates on ledger changes and at midnight. */
fun interface GameCapSource {
    fun observeRemaining(kidId: KidId): Flow<Money>
}

/** Seeds for new plays. Bound to [Random] by `GamesModule`; tests inject fixed seeds so sessions replay. */
fun interface GameSeedSource {
    fun next(): Long

    companion object {
        /** A fresh seed from [Random.Default] for every play. */
        val Random: GameSeedSource = GameSeedSource { kotlin.random.Random.Default.nextLong() }
    }
}

/** The household's fiat currency, for showing credit amounts (`FiatCurrencyProvider`). */
fun interface GameCurrencySource {
    fun fiatCurrency(): Currency.Fiat
}

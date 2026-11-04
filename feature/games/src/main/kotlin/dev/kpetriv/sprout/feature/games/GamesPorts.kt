package dev.kpetriv.sprout.feature.games

import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.GameCreditResult
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.games.GameId
import dev.kpetriv.sprout.core.games.Score
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlin.random.Random

/*
 * Ports of the brain games screens. The app adapts them to `PlayGameUseCase`, `GameCapReader` and the household's
 * fiat currency in `core/database` (M5 §3); this module depends on none of them.
 */

/**
 * Credits a finished play (`PlayGameUseCase`): the ledger re-derives and clamps the amount (D-M5-3, D-M5-7). The
 * result is the ledger's own [GameCreditResult] (one type for both sides, M5 review L3); the play screen reads it as:
 *
 * - [GameCreditResult.Credited] with a positive `amount`: credited. It was clamped to today's cap when
 *   `amount < earned` ("You reached today's limit"); `remainingAfter` is what is left of the cap.
 * - [GameCreditResult.Credited] with a zero `amount`: the play earned nothing (no round right), nothing was appended
 *   and the cap is untouched. Shown as a play "for fun", never as "+£0.00" (or as the cap reached, should
 *   `remainingAfter` be zero too).
 * - [GameCreditResult.CapReached]: today's cap was already used up; nothing appended, the play was "for fun".
 * - [GameCreditResult.NoAccount]: the kid has no fiat MAIN account.
 * - [GameCreditResult.Refused] whose `result` is an `AppendResult.Rejected` carrying
 *   `Violation.PlayedOnOutOfRange`: the device's date is outside the household's window (`PlayGameUseCase`'s
 *   device-date guard, `DEVICE_DATE_BEHIND_REJECTION`, D-M5-13); the screen asks to check the phone's date and offers
 *   a retry.
 * - Any other [GameCreditResult.Refused], or a failure thrown by [submit]: nothing was credited; the screen offers a
 *   retry. The refusal is never shown or logged.
 */
interface GameCreditSubmitter {
    suspend fun submit(kidId: KidId, gameId: GameId, score: Score): GameCreditResult
}

/**
 * Where a finished play is credited: a process-wide scope (the app's `@ApplicationScope`), so leaving the result
 * screen while it says "Saving…" never cancels the credit (M5 review M1). The play screen waits for the result there
 * but does not own the work.
 */
class GameCreditScope(val scope: CoroutineScope)

/**
 * A monotonic millisecond count for how long each round took (timed mode and its bonus): `SystemClock.elapsedRealtime`
 * in the app, so moving the wall clock mid-round changes nothing (M5 audit I2). It keeps counting across process
 * death and restarts at zero on a reboot.
 */
fun interface GameTimer {
    fun elapsedMillis(): Long
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

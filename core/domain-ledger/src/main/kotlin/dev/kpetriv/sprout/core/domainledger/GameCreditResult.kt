package dev.kpetriv.sprout.core.domainledger

/**
 * Outcome of submitting one finished game play for credit (core/database `PlayGameUseCase`, M5 §3). Lives here,
 * next to [AppendResult], so `feature/games` can use it without depending on core/database.
 */
sealed interface GameCreditResult {
    /**
     * [amount] was credited to the kid's fiat MAIN account by one kid-authored [GamePlayed]: what the play earned,
     * clamped to what was left of today's game cap. [earned] is what the play earned before clamping (so
     * `amount < earned` means it was clamped), and [remainingAfter] what is left of today's cap now. When the play
     * earned nothing ([earned] zero) no event is appended and [amount] is zero.
     */
    data class Credited(val amount: Money, val remainingAfter: Money, val earned: Money = amount) : GameCreditResult

    /** Today's game cap was already used up: nothing was appended (the play was "for fun", D-M5-7). */
    data object CapReached : GameCreditResult

    /** The kid has no fiat MAIN account to credit. */
    data object NoAccount : GameCreditResult

    /** The ledger refused the event ([result]); nothing was written. */
    data class Refused(val result: AppendResult) : GameCreditResult
}

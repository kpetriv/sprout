package dev.kpetriv.sprout.games

import dev.kpetriv.sprout.core.database.usecase.PlayGameUseCase
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.games.GameId
import dev.kpetriv.sprout.core.games.Score
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.feature.games.GameCreditResult
import dev.kpetriv.sprout.feature.games.GameCreditSubmitter
import javax.inject.Inject
import dev.kpetriv.sprout.core.domainledger.GameCreditResult as LedgerGameCreditResult

/**
 * [GameCreditSubmitter] over [PlayGameUseCase] (M5 §5): the use case re-derives the credit, clamps it to today's cap
 * and appends one kid-authored `GamePlayed`; its result is mapped to the screen's.
 */
class UseCaseGameCreditSubmitter @Inject constructor(private val play: PlayGameUseCase) : GameCreditSubmitter {
    override suspend fun submit(kidId: KidId, gameId: GameId, score: Score): GameCreditResult =
        play(kidId, gameId, score).toPort()

    internal companion object {
        /**
         * The screen's view of a ledger result. A play that earned nothing comes back `Credited` with zero (nothing
         * appended, the cap untouched), which the screen shows as a zero credit rather than "for fun": only
         * [LedgerGameCreditResult.CapReached] means the cap was used up. A refusal carries a code naming the result
         * and violation types only (no ids, amounts or names), since it may end up in diagnostics.
         */
        fun LedgerGameCreditResult.toPort(): GameCreditResult = when (this) {
            is LedgerGameCreditResult.Credited -> GameCreditResult.Credited(amount, remainingAfter)
            LedgerGameCreditResult.CapReached -> GameCreditResult.CapReached
            LedgerGameCreditResult.NoAccount -> GameCreditResult.NoAccount
            is LedgerGameCreditResult.Refused -> GameCreditResult.Refused(refusalCode(result))
        }

        fun refusalCode(result: AppendResult): String = when (result) {
            AppendResult.Accepted -> "accepted"

            AppendResult.Duplicate -> "duplicate"

            is AppendResult.Conflict -> "conflict"

            is AppendResult.Rejected ->
                result.violations
                    .mapNotNull { it::class.simpleName }
                    .joinToString(separator = ",", prefix = "rejected:")
        }
    }
}

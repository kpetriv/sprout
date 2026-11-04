package dev.kpetriv.sprout.games

import dev.kpetriv.sprout.core.database.usecase.DEVICE_DATE_BEHIND_REJECTION
import dev.kpetriv.sprout.core.database.usecase.PlayGameUseCase
import dev.kpetriv.sprout.core.domainledger.GameCreditResult
import dev.kpetriv.sprout.core.games.GameId
import dev.kpetriv.sprout.core.games.Score
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.feature.games.GameCreditSubmitter
import javax.inject.Inject

/**
 * [GameCreditSubmitter] over [PlayGameUseCase] (M5 §5): the use case re-derives the credit, clamps it to today's cap
 * and appends one kid-authored `GamePlayed`. Its [GameCreditResult] goes to the screen unchanged (one type on both
 * sides, M5 review L3); the port's KDoc says how the screen reads each case. In particular a zero credit (the play
 * earned nothing) shows as a play "for fun", and the device-date guard ([DEVICE_DATE_BEHIND_REJECTION], a
 * `Rejected` carrying `Violation.PlayedOnOutOfRange`) asks the kid to check the phone's date.
 *
 * The screen runs this on the app's scope ([dev.kpetriv.sprout.feature.games.GameCreditScope]), so a credit is never
 * cut off by leaving the screen.
 */
class UseCaseGameCreditSubmitter @Inject constructor(private val play: PlayGameUseCase) : GameCreditSubmitter {
    override suspend fun submit(kidId: KidId, gameId: GameId, score: Score): GameCreditResult =
        play(kidId, gameId, score)
}

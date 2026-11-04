package dev.kpetriv.sprout.games

import android.app.Application
import dev.kpetriv.sprout.core.database.TestHousehold
import dev.kpetriv.sprout.core.database.usecase.INVALID_SCORE_REJECTION
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.GameCreditResult
import dev.kpetriv.sprout.core.domainledger.GamePlayed
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.Violation
import dev.kpetriv.sprout.core.games.Difficulty
import dev.kpetriv.sprout.core.games.GameId
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.games.Score
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.feature.games.GameCapSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The games adapters over a real Room household (the trial one): credit, clamp, cap and refusals end to end. The
 * device-date refusal's shape is pinned in core/database's `GameAuditRegressionTest`, and how the screen reads every
 * result in feature/games' `GameViewModelTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class) // Not the Hilt app: its startup opens the encrypted database.
class UseCaseGameCreditSubmitterTest {
    private val household = TestHousehold(RuntimeEnvironment.getApplication())
    private val kidId = KidId("kid-leo")
    private val submitter = UseCaseGameCreditSubmitter(household.playGame)
    private val cap = GameCapSource(household.gameCap::observeRemaining)
    private val sprout = GameId(GameKind.ARITHMETIC, Difficulty.SPROUT)

    /** 8 of 8 at Sprout: 8 + 3 for perfect = 11. */
    private val perfect = Score(correct = 8, total = 8, perfect = true, timedBonus = false)

    @After
    fun close() = household.close()

    private suspend fun gamesPlayed(): List<GamePlayed> =
        household.ledger.observeEvents(kidId, LIMIT).first().filterIsInstance<GamePlayed>()

    @Test
    fun `credits until the daily cap, clamps the last play, then plays are for fun`() = runTest {
        val (_, accounts) = household.addKid(kidId.value)
        val main = with(household) { accounts.money() }
        assertEquals(Money(DAILY_CAP), cap.observeRemaining(kidId).first())

        repeat(4) { assertEquals(GameCreditResult.Credited(Money(11), Money(DAILY_CAP - 11L * (it + 1))), play()) }
        assertEquals(Money(6), cap.observeRemaining(kidId).first())

        val clamped = GameCreditResult.Credited(Money(6), Money.ZERO, earned = Money(11))
        assertEquals(clamped, play(), "clamped to what is left, below what it earned")
        assertEquals(Money.ZERO, cap.observeRemaining(kidId).first())
        assertEquals(GameCreditResult.CapReached, play())

        assertEquals(listOf(6L, 11L, 11L, 11L, 11L), gamesPlayed().map { it.amount.minorUnits })
        val balance = household.ledger.observeBalances(kidId).first().single { it.accountId == main.id }
        assertEquals(Money(DAILY_CAP), balance.amount, "the kid's wallet shows every credit")
    }

    @Test
    fun `a play that earned nothing is a zero credit, appends nothing and leaves the cap alone`() = runTest {
        household.addKid(kidId.value)

        val result = submitter.submit(kidId, sprout, Score(correct = 0, total = 8, perfect = false, timedBonus = false))

        // The screen shows this as a play for fun, never "+£0.00".
        assertEquals(GameCreditResult.Credited(Money.ZERO, Money(DAILY_CAP), earned = Money.ZERO), result)
        assertEquals(emptyList(), gamesPlayed())
        assertEquals(Money(DAILY_CAP), cap.observeRemaining(kidId).first())
    }

    @Test
    fun `no account and an impossible score reach the screen as such`() = runTest {
        assertEquals(GameCreditResult.NoAccount, play())
        assertEquals(Money.ZERO, cap.observeRemaining(kidId).first())

        household.addKid(kidId.value)
        val impossible = submitter.submit(kidId, sprout, Score(99, 8, perfect = true, timedBonus = false))
        val rejected = (impossible as GameCreditResult.Refused).result as AppendResult.Rejected
        assertEquals(INVALID_SCORE_REJECTION, rejected.reason)
        assertEquals(listOf<Violation>(Violation.ScoreOutOfRange(99, 8)), rejected.violations)
        assertEquals(emptyList(), gamesPlayed())
    }

    private suspend fun play(): GameCreditResult = submitter.submit(kidId, sprout, perfect)

    private companion object {
        const val DAILY_CAP = 50L
        const val LIMIT = 100
    }
}

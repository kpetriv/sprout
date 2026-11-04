package dev.kpetriv.sprout.games

import android.app.Application
import dev.kpetriv.sprout.core.database.TestHousehold
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.GamePlayed
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.Violation
import dev.kpetriv.sprout.core.games.Difficulty
import dev.kpetriv.sprout.core.games.GameId
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.games.Score
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.feature.games.GameCapSource
import dev.kpetriv.sprout.feature.games.GameCreditResult
import dev.kpetriv.sprout.games.UseCaseGameCreditSubmitter.Companion.refusalCode
import dev.kpetriv.sprout.games.UseCaseGameCreditSubmitter.Companion.toPort
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import dev.kpetriv.sprout.core.domainledger.GameCreditResult as LedgerGameCreditResult

/** The games adapters over a real Room household (the trial one): credit, clamp, cap and refusals end to end. */
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

        assertEquals(GameCreditResult.Credited(Money(6), Money.ZERO), play(), "clamped to what is left")
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

        assertEquals(GameCreditResult.Credited(Money.ZERO, Money(DAILY_CAP)), result)
        assertEquals(emptyList(), gamesPlayed())
        assertEquals(Money(DAILY_CAP), cap.observeRemaining(kidId).first())
    }

    @Test
    fun `no account and an impossible score reach the screen as such`() = runTest {
        assertEquals(GameCreditResult.NoAccount, play())
        assertEquals(Money.ZERO, cap.observeRemaining(kidId).first())

        household.addKid(kidId.value)
        val impossible = submitter.submit(kidId, sprout, Score(99, 8, perfect = true, timedBonus = false))
        assertEquals(GameCreditResult.Refused("rejected:ScoreOutOfRange"), impossible)
        assertEquals(emptyList(), gamesPlayed())
    }

    @Test
    fun `ledger results map one to one and refusal codes carry no data`() {
        assertEquals(
            GameCreditResult.Credited(Money(4), Money(9)),
            LedgerGameCreditResult.Credited(Money(4), Money(9), earned = Money(7)).toPort(),
        )
        assertEquals(GameCreditResult.CapReached, LedgerGameCreditResult.CapReached.toPort())
        assertEquals(GameCreditResult.NoAccount, LedgerGameCreditResult.NoAccount.toPort())
        val refused = LedgerGameCreditResult.Refused(
            AppendResult.Rejected("daily game cap", listOf(Violation.GameCreditTooHigh(Money(3), Money(9)))),
        ).toPort()
        assertEquals(GameCreditResult.Refused("rejected:GameCreditTooHigh"), refused)
        assertEquals("duplicate", refusalCode(AppendResult.Duplicate))
        val eventId = EventId("00000000-0000-4000-8000-000000000001")
        assertEquals("conflict", refusalCode(AppendResult.Conflict(eventId, "stored", "incoming")))
        assertEquals("rejected:", refusalCode(AppendResult.Rejected("no reason given")))
    }

    private suspend fun play(): GameCreditResult = submitter.submit(kidId, sprout, perfect)

    private companion object {
        const val DAILY_CAP = 50L
        const val LIMIT = 100
    }
}

package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.AnomalyKind
import dev.kpetriv.sprout.core.database.entity.EventOrigin
import dev.kpetriv.sprout.core.database.usecase.INVALID_SCORE_REJECTION
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.GameCreditResult
import dev.kpetriv.sprout.core.domainledger.GamePlayed
import dev.kpetriv.sprout.core.domainledger.LedgerAnomaly
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.domainledger.LedgerPolicy
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.Violation
import dev.kpetriv.sprout.core.games.Difficulty
import dev.kpetriv.sprout.core.games.GameId
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.games.Score
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.sync.EventApplyReport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Brain-game credits in the data layer (M5 §2, §3): [dev.kpetriv.sprout.core.database.usecase.PlayGameUseCase] on a
 * kid device, [dev.kpetriv.sprout.core.database.usecase.GameCapReader], and what a parent device does with game events
 * a kid device sends: a forged amount, and a play that reached the server outside its played-on window.
 */
@RunWith(RobolectricTestRunner::class)
class GamesTest {
    private val parent = parentDevice()
    private val kid = kidDevice()
    private val today = CalendarDate(2026, 11, 2)
    private val cap = LedgerPolicy.DEFAULT_DAILY_GAME_CAP

    private val saplingSums = GameId(GameKind.ARITHMETIC, Difficulty.SAPLING)
    private val sproutWords = GameId(GameKind.WORDS, Difficulty.SPROUT)

    /** 10 of 10 at Sapling, timed: the most a play earns (15). */
    private val perfectTimed = Score(correct = 10, total = 10, perfect = true, timedBonus = true)

    /** 8 of 8 at Sprout: 8 + 3 = 11. */
    private val perfectSprout = Score(correct = 8, total = 8, perfect = true, timedBonus = false)

    @After
    fun close() {
        parent.close()
        kid.close()
    }

    /** Registers both devices, creates [KID_X] with its accounts on the parent and applies them on the kid. */
    private suspend fun linked(): dev.kpetriv.sprout.core.domainledger.Account {
        registerEachOther(parent, kid)
        val (_, accounts) = parent.addKid(KID_X.value)
        kid.applier.applyDocuments(parent.queuedDocuments())
        return with(parent) { accounts.money() }
    }

    private suspend fun balanceOf(on: TestHousehold): Money = on.ledger.observeBalances(KID_X).first()
        .filter { it.currency == on.gbp }.sumOf { it.amount.minorUnits }.let(::Money)

    private suspend fun games(on: TestHousehold): List<GamePlayed> = on.database.ledgerEventDao().all()
        .mapNotNull { LedgerEventMapper.toDomain(it) as? GamePlayed }

    @Test
    fun aPlayIsCreditedAsOneKidAuthoredGameEvent() = runTest {
        val main = linked()

        val result = kid.playGame(KID_X, saplingSums, perfectTimed)

        assertEquals(GameCreditResult.Credited(Money(15), cap - Money(15), earned = Money(15)), result)
        val game = games(kid).single()
        assertEquals("arithmetic.sapling", game.gameId)
        assertEquals(10L, game.score)
        assertEquals(today, game.playedOn)
        assertEquals(main.id, game.envelope.accountId)
        assertEquals(dev.kpetriv.sprout.core.model.Role.KID, game.envelope.authorRole)
        assertEquals(Money(15), balanceOf(kid))
        // The parent device applies it as it is: the amount is exactly what the score earns.
        assertEquals(EventApplyReport(applied = 1), parent.applier.applyEvents(kid.queuedEvents()))
        assertEquals(Money(15), balanceOf(parent))
    }

    @Test
    fun aPlayIsClampedToWhatIsLeftOfTodaysCapThenTheCapIsReached() = runTest {
        linked()
        repeat(3) { assertIs<GameCreditResult.Credited>(kid.playGame(KID_X, saplingSums, perfectTimed)) }

        // 45 of 50 used: an 11-point play is clamped to 5, which the ledger allows (amount <= earned).
        val clamped = kid.playGame(KID_X, sproutWords, perfectSprout)

        assertEquals(GameCreditResult.Credited(Money(5), Money.ZERO, earned = Money(11)), clamped)
        assertEquals(Money(5), games(kid).last().amount)
        assertEquals(Money(50), balanceOf(kid))
        val stored = kid.database.ledgerEventDao().count()

        assertEquals(GameCreditResult.CapReached, kid.playGame(KID_X, sproutWords, perfectSprout))
        assertEquals(stored, kid.database.ledgerEventDao().count(), "nothing appended past the cap")
        // A clamped play is valid on every device.
        assertEquals(EventApplyReport(applied = 4), parent.applier.applyEvents(kid.queuedEvents()))
        assertEquals(Money(50), balanceOf(parent))
    }

    @Test
    fun aPlayThatEarnsNothingOrHasAnImpossibleScoreAppendsNothing() = runTest {
        linked()

        val none = Score(correct = 0, total = 8, perfect = false, timedBonus = false)
        val nothing = kid.playGame(KID_X, sproutWords, none)
        assertEquals(GameCreditResult.Credited(Money.ZERO, cap, earned = Money.ZERO), nothing)

        val tooMany = Score(correct = 9, total = 8, perfect = true, timedBonus = false)
        val impossible = kid.playGame(KID_X, sproutWords, tooMany)
        val refused = assertIs<AppendResult.Rejected>(assertIs<GameCreditResult.Refused>(impossible).result)
        assertEquals(INVALID_SCORE_REJECTION, refused.reason)
        assertEquals(listOf<Violation>(Violation.ScoreOutOfRange(9, 8)), refused.violations)
        assertTrue(games(kid).isEmpty())
    }

    @Test
    fun aKidWithoutAMoneyAccountGetsNoAccount() = runTest {
        registerEachOther(parent, kid)

        assertEquals(GameCreditResult.NoAccount, kid.playGame(KID_X, saplingSums, perfectTimed))
    }

    @Test
    fun aGameDatedOutsideTheWindowOfTheDeviceDateIsRejectedBeforeItIsStored() = runTest {
        val main = linked()
        for (date in listOf(today.plusDays(-7), today, today.plusDays(1))) {
            assertEquals(
                AppendResult.Accepted,
                kid.writer.appendAsKid(KID_X, main.id) { GamePlayed(it, kid.gbp, Money(1), "memory.sprout", 1, date) },
            )
        }
        for (date in listOf(today.plusDays(-8), today.plusDays(2))) {
            val result = kid.writer.appendAsKid(KID_X, main.id) {
                GamePlayed(it, kid.gbp, Money(1), "memory.sprout", 1, date)
            }
            assertEquals(
                listOf<Violation>(Violation.PlayedOnOutOfRange(date, today)),
                assertIs<AppendResult.Rejected>(result).violations,
            )
        }
        assertEquals(3, games(kid).size)
    }

    @Test
    fun aForgedAmountFromTheKidDeviceIsQuarantinedByTheParentDevice() = runTest {
        val main = linked()
        // Signed by the real kid device, but claiming more than 8 of 8 at Sprout can earn (11).
        val forged = kid.craft(KID_X, main.id) { GamePlayed(it, kid.gbp, Money(50), "words.sprout", 8, today) }

        assertEquals(EventApplyReport(quarantined = 1), parent.applier.applyEvents(listOf(forged)))

        val row = parent.database.ledgerEventDao().all().single()
        assertTrue(row.quarantined)
        assertEquals(RoomLedgerRepository.INVARIANT_QUARANTINE + "GameCreditTooHigh", row.quarantineReason)
        assertEquals(forged, LedgerJson.json.parseToJsonElement(row.payloadJson), "kept as evidence")
        assertEquals(Money.ZERO, balanceOf(parent))
        // The kid device itself would never have stored it: the same rule refuses it locally.
        val local = kid.writer.appendAsKid(KID_X, main.id) {
            GamePlayed(it, kid.gbp, Money(50), "words.sprout", 8, today)
        }
        assertEquals(
            listOf<Violation>(Violation.GameCreditTooHigh(Money(11), Money(50))),
            assertIs<AppendResult.Rejected>(local).violations,
        )
    }

    @Test
    fun aGameThatReachedTheServerTooLateIsStoredButExcludedAndTheChainGoesOn() = runTest {
        linked()
        assertIs<GameCreditResult.Credited>(kid.playGame(KID_X, saplingSums, perfectTimed))
        assertIs<GameCreditResult.Credited>(kid.playGame(KID_X, sproutWords, perfectSprout))
        val (late, next) = kid.queuedEvents()
        val stampDay = { days: Int -> Instant.parse("2026-11-02T23:00:00Z") + days.days }

        // The first play reached the server 8 days after its date (backdated, or a tablet offline too long).
        assertEquals(EventApplyReport(applied = 1), parent.applier.applyEvents(listOf(late), stampDay(8)))
        // The kid's next event still links to it, and an offline week is fine.
        assertEquals(EventApplyReport(applied = 1), parent.applier.applyEvents(listOf(next), stampDay(7)))

        val rows = parent.database.ledgerEventDao().all()
        assertTrue(rows.none { it.quarantined })
        assertTrue(rows.all { it.origin == EventOrigin.REMOTE && it.serverReceivedAtEpochMs != null })
        assertEquals(Money(11), balanceOf(parent))
        val lateId = LedgerJson.decodeOrNull(late)!!.eventId
        assertEquals(
            listOf<LedgerAnomaly>(LedgerAnomaly.PlayedOnOutOfRange(KID_X, lateId, today, today.plusDays(8))),
            parent.ledger.observeAnomalies(KID_X).first(),
        )
        assertEquals(AnomalyKind.PLAYED_ON_OUT_OF_RANGE, parent.database.anomalyDao().all().single().kind)
        // Rebuilt from storage (stamps read back from the rows), the projection decides the same.
        assertEquals(Money(11), parent.ledger.projection().balanceOf(rows.first().accountId))
        // The author applies its own copy: the residual divergence of ADR-0009.
        assertEquals(Money(26), balanceOf(kid))
    }

    @Test
    fun aHeldGameKeepsItsServerStampUntilItIsApplied() = runTest {
        linked()
        assertIs<GameCreditResult.Credited>(kid.playGame(KID_X, saplingSums, perfectTimed))
        assertIs<GameCreditResult.Credited>(kid.playGame(KID_X, sproutWords, perfectSprout))
        val (first, second) = kid.queuedEvents()
        val late = Instant.parse("2026-11-12T00:00:00Z")

        assertEquals(EventApplyReport(held = 1), parent.applier.applyEvents(listOf(second), late))
        val held = parent.database.ledgerInboxDao().all().single()
        assertEquals(late.toEpochMilliseconds(), held.serverReceivedAtEpochMs)
        assertEquals(
            EventApplyReport(applied = 2),
            parent.applier.applyEvents(listOf(first), Instant.parse("2026-11-02T09:00:00Z")),
        )

        assertEquals(Money(15), balanceOf(parent), "the held play kept its late stamp and is excluded")
        assertIs<LedgerAnomaly.PlayedOnOutOfRange>(parent.ledger.observeAnomalies(KID_X).first().single())
    }

    @Test
    fun theRemainingCapFollowsPlaysAndStartsAgainAtMidnight() = runTest {
        linked()
        val seen = Channel<Money>(Channel.UNLIMITED)
        val collecting = launch { kid.gameCap.observeRemaining(KID_X).collect { seen.send(it) } }
        assertEquals(cap, seen.receive())

        assertIs<GameCreditResult.Credited>(kid.playGame(KID_X, saplingSums, perfectTimed))
        assertEquals(cap - Money(15), seen.receive())

        // 08:00 UTC plus the writes' minutes: 16 hours later is the next household (UTC) day.
        kid.clock.advanceBy(16.hours)
        assertEquals(cap, seen.receive())
        collecting.cancel()
    }
}

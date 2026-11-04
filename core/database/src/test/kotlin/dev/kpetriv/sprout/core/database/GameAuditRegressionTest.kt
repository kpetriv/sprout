package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.EventOrigin
import dev.kpetriv.sprout.core.database.usecase.DEVICE_DATE_BEHIND_REJECTION
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.GameCreditResult
import dev.kpetriv.sprout.core.domainledger.GamePlayed
import dev.kpetriv.sprout.core.domainledger.LedgerAnomaly
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.domainledger.LedgerPolicy
import dev.kpetriv.sprout.core.domainledger.ManualAdjustment
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.Violation
import dev.kpetriv.sprout.core.games.Difficulty
import dev.kpetriv.sprout.core.games.GameId
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.games.Score
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.sync.EventApplyReport
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Regressions for the M5 audit and review findings on the data layer: the author device learning its own server stamps
 * (audit M1, review L5), games into screen-time minutes (audit H1), a device date moved behind the server's (audit
 * L1), two plays racing the cap (review L1) and the games hub reading the cap without a household projection (review
 * L7).
 */
@RunWith(RobolectricTestRunner::class)
class GameAuditRegressionTest {
    private val parent = parentDevice()
    private val kid = kidDevice()
    private val today = CalendarDate(2026, 11, 2)
    private val cap = LedgerPolicy.DEFAULT_DAILY_GAME_CAP

    private val saplingSums = GameId(GameKind.ARITHMETIC, Difficulty.SAPLING)
    private val sproutWords = GameId(GameKind.WORDS, Difficulty.SPROUT)

    /** 10 of 10 at Sapling, timed: 15. */
    private val perfectTimed = Score(correct = 10, total = 10, perfect = true, timedBonus = true)

    /** 8 of 8 at Sprout: 11. */
    private val perfectSprout = Score(correct = 8, total = 8, perfect = true, timedBonus = false)

    @After
    fun close() {
        parent.close()
        kid.close()
    }

    /** Registers both devices, creates [KID_X] with its default accounts on the parent and applies them on the kid. */
    private suspend fun linked(): List<Account> {
        registerEachOther(parent, kid)
        val (_, accounts) = parent.addKid(KID_X.value)
        kid.applier.applyDocuments(parent.queuedDocuments())
        return accounts
    }

    private suspend fun balanceOf(on: TestHousehold): Money = on.ledger.observeBalances(KID_X).first()
        .filter { it.currency == on.gbp }.sumOf { it.amount.minorUnits }.let(::Money)

    private fun stamp(text: String): Instant = Instant.parse(text)

    @Test
    fun theAuthorConvergesOnceItPullsItsOwnStampedEventsBack() = runTest {
        linked()
        assertIs<GameCreditResult.Credited>(kid.playGame(KID_X, saplingSums, perfectTimed))
        assertIs<GameCreditResult.Credited>(kid.playGame(KID_X, sproutWords, perfectSprout))
        val (late, next) = kid.queuedEvents()
        // Both plays reached the server 8 days after their date: every receiver excludes them.
        val eightDaysLate = stamp("2026-11-02T23:00:00Z") + 8.days
        parent.applier.applyEvents(listOf(late, next), eightDaysLate)
        assertEquals(Money.ZERO, balanceOf(parent))
        assertEquals(Money(26), balanceOf(kid), "before the pull the author still counts its own plays")

        // The kid device pulls its own events back with the stamps every receiver saw.
        val report = kid.applier.applyEvents(listOf(late, next), eightDaysLate)

        assertEquals(EventApplyReport(duplicates = 2), report)
        val rows = kid.database.ledgerEventDao().all().filter { it.type == "game_played" }
        assertTrue(rows.all { it.origin == EventOrigin.LOCAL })
        assertEquals(listOf(eightDaysLate.toEpochMilliseconds()), rows.map { it.serverReceivedAtEpochMs }.distinct())
        assertEquals(balanceOf(parent), balanceOf(kid))
        assertEquals(
            parent.ledger.observeAnomalies(KID_X).first().toSet(),
            kid.ledger.observeAnomalies(KID_X).first().toSet(),
        )
        assertTrue(kid.ledger.observeAnomalies(KID_X).first().all { it is LedgerAnomaly.PlayedOnOutOfRange })
        // The games hub agrees: neither excluded play used today's cap.
        assertEquals(cap, kid.gameCap.observeRemaining(KID_X).first())

        // A stamp is set once: a later copy with another stamp changes nothing.
        kid.applier.applyEvents(listOf(late), stamp("2026-11-02T09:00:00Z"))
        assertEquals(
            listOf(eightDaysLate.toEpochMilliseconds()),
            kid.database.ledgerEventDao().all().filter { it.type == "game_played" }
                .map { it.serverReceivedAtEpochMs }.distinct(),
        )
    }

    @Test
    fun aHeldRowWithoutAStampTakesTheStampOfAPulledCopy() = runTest {
        linked()
        assertIs<GameCreditResult.Credited>(kid.playGame(KID_X, saplingSums, perfectTimed))
        assertIs<GameCreditResult.Credited>(kid.playGame(KID_X, sproutWords, perfectSprout))
        val (first, second) = kid.queuedEvents()
        // Held without a stamp, as a row held before schema v7 is.
        assertEquals(EventApplyReport(held = 1), parent.applier.applyEvents(listOf(second)))
        assertEquals(null, parent.database.ledgerInboxDao().all().single().serverReceivedAtEpochMs)

        val late = stamp("2026-11-12T00:00:00Z")
        assertEquals(EventApplyReport(duplicates = 1), parent.applier.applyEvents(listOf(second), late))
        val held = parent.database.ledgerInboxDao().all().single()
        assertEquals(late.toEpochMilliseconds(), held.serverReceivedAtEpochMs)

        assertEquals(
            EventApplyReport(applied = 2),
            parent.applier.applyEvents(listOf(first), stamp("2026-11-02T09:00:00Z")),
        )
        assertEquals(Money(15), balanceOf(parent), "the held play kept the stamp it learnt and is excluded")
        assertIs<LedgerAnomaly.PlayedOnOutOfRange>(parent.ledger.observeAnomalies(KID_X).first().single())
    }

    @Test
    fun aGameIntoScreenTimeMinutesIsRefusedLocallyAndQuarantinedOnIngest() = runTest {
        val accounts = linked()
        val minutes = with(parent) { accounts.minutes() }
        repeat(4) { assertIs<GameCreditResult.Credited>(kid.playGame(KID_X, sproutWords, perfectSprout)) }
        assertEquals(EventApplyReport(applied = 4), parent.applier.applyEvents(kid.queuedEvents()))
        // Signed by the real kid device: a modified client opening a second daily cap in minutes.
        val forged = kid.craft(KID_X, minutes.id) {
            GamePlayed(it, Currency.Minutes, Money(12), "arithmetic.sapling", 10, today)
        }

        assertEquals(EventApplyReport(quarantined = 1), parent.applier.applyEvents(listOf(forged)))

        val row = parent.database.ledgerEventDao().all().single { it.quarantined }
        assertEquals(RoomLedgerRepository.INVARIANT_QUARANTINE + "GameCurrencyNotAllowed", row.quarantineReason)
        assertEquals(Money(44), balanceOf(parent))
        val minutesBalance = parent.ledger.observeBalances(KID_X).first().filter { it.currency == Currency.Minutes }
        assertTrue(minutesBalance.all { it.amount == Money.ZERO })
        val local = kid.writer.appendAsKid(KID_X, minutes.id) {
            GamePlayed(it, Currency.Minutes, Money(12), "arithmetic.sapling", 10, today)
        }
        assertEquals(
            listOf<Violation>(Violation.GameCurrencyNotAllowed(Currency.Minutes)),
            assertIs<AppendResult.Rejected>(local).violations,
        )
    }

    @Test
    fun aPlayIsRefusedWhenTheDeviceDateIsBehindTheWindowOfTheServerTime() = runTest {
        val main = with(parent) { linked().money() }
        assertIs<GameCreditResult.Credited>(kid.playGame(KID_X, saplingSums, perfectTimed))
        // The server has been seen 7 days after the device's today: still inside the window, so plays go on.
        kid.applier.applyEvents(kid.queuedEvents().take(1), stamp("2026-11-09T12:00:00Z"))
        assertIs<GameCreditResult.Credited>(kid.playGame(KID_X, sproutWords, perfectSprout))

        // 8 days: the device date was moved back (or is wrong); every receiver would exclude a play dated today.
        val serverDate = CalendarDate(2026, 11, 10)
        kid.applier.applyEvents(kid.queuedEvents().drop(1).take(1), stamp("2026-11-10T12:00:00Z"))
        val refused = assertIs<GameCreditResult.Refused>(kid.playGame(KID_X, sproutWords, perfectSprout))
        val rejected = assertIs<AppendResult.Rejected>(refused.result)
        assertEquals(DEVICE_DATE_BEHIND_REJECTION, rejected.reason)
        assertEquals(listOf<Violation>(Violation.PlayedOnOutOfRange(today, serverDate)), rejected.violations)
        // The repository's local window refuses it too, whoever appends it.
        val direct = kid.writer.appendAsKid(KID_X, main.id) {
            GamePlayed(it, kid.gbp, Money(1), "memory.sprout", 1, today)
        }
        assertEquals(
            listOf<Violation>(Violation.PlayedOnOutOfRange(today, serverDate)),
            assertIs<AppendResult.Rejected>(direct).violations,
        )
        assertEquals(2, kid.database.ledgerEventDao().all().count { it.type == "game_played" })
    }

    @Test
    fun twoPlaysRacingTheCapAreClampedInTurnNeverRefused() = runTest {
        linked()
        repeat(3) { assertIs<GameCreditResult.Credited>(kid.playGame(KID_X, saplingSums, perfectTimed)) }

        // 45 of 50 used; both plays earn 11.
        val one = async { kid.playGame(KID_X, sproutWords, perfectSprout) }
        val two = async { kid.playGame(KID_X, sproutWords, perfectSprout) }

        assertEquals(
            setOf(GameCreditResult.Credited(Money(5), Money.ZERO, earned = Money(11)), GameCreditResult.CapReached),
            setOf(one.await(), two.await()),
        )
        assertEquals(Money(50), balanceOf(kid))
    }

    @Test
    fun theHubReadsTheCapWithoutRebuildingTheVerifiedProjection() = runTest {
        val main = with(parent) { linked().money() }
        assertIs<GameCreditResult.Credited>(kid.playGame(KID_X, saplingSums, perfectTimed))
        // A row stored as this device's own but signed by another device: any verified projection quarantines it.
        val foreign = parent.craft(KID_X, main.id, role = Role.PARENT) {
            ManualAdjustment(it, parent.gbp, Money(5), "planted")
        }
        val planted = requireNotNull(LedgerJson.decodeOrNull(foreign))
        kid.database.ledgerEventDao().insert(LedgerEventMapper.toEntity(planted, EventOrigin.LOCAL, 0, null, foreign))
        suspend fun plantedRow() = kid.database.ledgerEventDao().all().single { it.eventId == planted.eventId }

        val seen = Channel<Money>(Channel.UNLIMITED)
        val collecting = launch { kid.gameCap.observeRemaining(KID_X).collect { seen.send(it) } }
        assertEquals(cap - Money(15), seen.receive())
        kid.clock.advanceBy(16.hours)
        assertEquals(cap, seen.receive())
        collecting.cancel()

        assertFalse(plantedRow().quarantined, "no emission rebuilt and re-verified the projection")
        kid.ledger.projection()
        assertTrue(plantedRow().quarantined, "a projection would have")
    }
}

package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.usecase.GamePlaySummary
import dev.kpetriv.sprout.core.domainledger.GameCreditResult
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.domainledger.Money
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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * [dev.kpetriv.sprout.core.database.usecase.GameHistoryReader] (M9-games §6.2): a kid's last plays and best score of
 * the week, read from the credited [dev.kpetriv.sprout.core.domainledger.GamePlayed] events.
 */
@RunWith(RobolectricTestRunner::class)
class GameHistoryReaderTest {
    private val parent = parentDevice()
    private val kid = kidDevice()
    private val today = CalendarDate(2026, 11, 2)

    private val sproutTables = GameId(GameKind.TABLES, Difficulty.SPROUT)
    private val saplingTables = GameId(GameKind.TABLES, Difficulty.SAPLING)
    private val saplingSums = GameId(GameKind.ARITHMETIC, Difficulty.SAPLING)

    @After
    fun close() {
        parent.close()
        kid.close()
    }

    private suspend fun linked() {
        registerEachOther(parent, kid)
        parent.addKid(KID_X.value)
        kid.applier.applyDocuments(parent.queuedDocuments())
    }

    /** [correct] of 20 Times tables questions. */
    private fun tables(correct: Int) = Score(correct, 20, perfect = correct == 20, timedBonus = false)

    private suspend fun play(gameId: GameId, score: Score) =
        assertIs<GameCreditResult.Credited>(kid.playGame(KID_X, gameId, score))

    @Test
    fun recentPlaysOfOneKindAreNewestFirstWithTheirScoreAndClampedCredit() = runTest {
        linked()
        play(sproutTables, tables(20))
        play(saplingSums, Score(10, 10, perfect = true, timedBonus = true))
        play(saplingTables, tables(12))
        // 15 + 15 + 12 of the cap of 50 used: 17 right earns 15, clamped to the 8 left.
        play(sproutTables, tables(17))
        // The cap is reached: nothing is appended, so the play is not in the history.
        assertEquals(GameCreditResult.CapReached, kid.playGame(KID_X, sproutTables, tables(19)))

        val recent = kid.gameHistory.observeRecent(KID_X, GameKind.TABLES).first()

        assertEquals(
            listOf(
                GamePlaySummary(today, sproutTables, 17, Money(8)),
                GamePlaySummary(today, saplingTables, 12, Money(12)),
                GamePlaySummary(today, sproutTables, 20, Money(15)),
            ),
            recent,
        )
        assertEquals(recent.take(2), kid.gameHistory.observeRecent(KID_X, GameKind.TABLES, limit = 2).first())
        assertEquals(
            listOf(GamePlaySummary(today, saplingSums, 10, Money(15))),
            kid.gameHistory.observeRecent(KID_X, GameKind.ARITHMETIC).first(),
        )
        assertEquals(emptyList(), kid.gameHistory.observeRecent(KID_X, GameKind.MEMORY).first())
        assertFailsWith<IllegalArgumentException> { kid.gameHistory.observeRecent(KID_X, GameKind.TABLES, limit = 0) }
    }

    @Test
    fun anOlderDayComesAfterANewerOne() = runTest {
        linked()
        play(sproutTables, tables(9))
        kid.clock.advanceBy(1.days)
        play(sproutTables, tables(5))

        assertEquals(
            listOf(today.plusDays(1) to 5, today to 9),
            kid.gameHistory.observeRecent(KID_X, GameKind.TABLES).first().map { it.playedOn to it.score },
        )
    }

    @Test
    fun recentPlaysFollowNewPlays() = runTest {
        linked()
        val seen = Channel<List<Int>>(Channel.UNLIMITED)
        val collecting = launch {
            kid.gameHistory.observeRecent(KID_X, GameKind.TABLES).collect { plays -> seen.send(plays.map { it.score }) }
        }
        assertEquals(emptyList(), seen.receive())

        play(sproutTables, tables(14))
        assertEquals(listOf(14), seen.receive())
        // Another game does not change the Times tables history, so nothing is emitted for it.
        play(saplingSums, Score(3, 10, perfect = false, timedBonus = false))
        play(saplingTables, tables(20))
        assertEquals(listOf(20, 14), seen.receive())
        collecting.cancel()
    }

    @Test
    fun bestThisWeekIsTheHighestScoreOfTheLastSevenDays() = runTest {
        linked()
        assertNull(kid.gameHistory.observeBestScoreThisWeek(KID_X, GameKind.TABLES).first())

        play(sproutTables, tables(20))
        play(saplingSums, Score(10, 10, perfect = true, timedBonus = true))
        kid.clock.advanceBy(3.days)
        play(sproutTables, tables(11))
        assertEquals(20, kid.gameHistory.observeBestScoreThisWeek(KID_X, GameKind.TABLES).first())
        assertEquals(10, kid.gameHistory.observeBestScoreThisWeek(KID_X, GameKind.ARITHMETIC).first())

        // Six days after the 20: still this week. Seven days after: only the 11 is left.
        kid.clock.advanceBy(3.days)
        assertEquals(20, kid.gameHistory.observeBestScoreThisWeek(KID_X, GameKind.TABLES).first())
        kid.clock.advanceBy(1.days)
        assertEquals(11, kid.gameHistory.observeBestScoreThisWeek(KID_X, GameKind.TABLES).first())
        kid.clock.advanceBy(4.days)
        assertNull(kid.gameHistory.observeBestScoreThisWeek(KID_X, GameKind.TABLES).first())
        // The history itself keeps every play.
        assertEquals(listOf(11, 20), kid.gameHistory.observeRecent(KID_X, GameKind.TABLES).first().map { it.score })
    }

    @Test
    fun aPlayTheProjectionExcludesIsNotInTheHistory() = runTest {
        linked()
        play(sproutTables, tables(20))
        play(saplingTables, tables(16))
        val (late, next) = kid.queuedEvents()
        val stampDay = { days: Int -> Instant.parse("2026-11-02T23:00:00Z") + days.days }

        // The first play reached the server 8 days after its date: every receiver excludes it (D-M5-5).
        assertEquals(EventApplyReport(applied = 1), parent.applier.applyEvents(listOf(late), stampDay(8)))
        assertEquals(EventApplyReport(applied = 1), parent.applier.applyEvents(listOf(next), stampDay(0)))

        assertEquals(
            listOf(GamePlaySummary(today, saplingTables, 16, Money(15))),
            parent.gameHistory.observeRecent(KID_X, GameKind.TABLES).first(),
        )
        // The author counts its own copy until it pulls the stamp back (ADR-0009), and then agrees.
        assertEquals(2, kid.gameHistory.observeRecent(KID_X, GameKind.TABLES).first().size)
        kid.applier.applyEvents(listOf(late), stampDay(8))
        assertEquals(
            parent.gameHistory.observeRecent(KID_X, GameKind.TABLES).first(),
            kid.gameHistory.observeRecent(KID_X, GameKind.TABLES).first(),
        )
        val lateId = LedgerJson.decodeOrNull(late)!!.eventId
        assertEquals(1, parent.database.ledgerEventDao().all().count { it.eventId == lateId }, "stored, excluded")
    }
}

package dev.kpetriv.sprout.feature.wallet

import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.GamePlayed
import dev.kpetriv.sprout.core.domainledger.LedgerAnomaly
import dev.kpetriv.sprout.core.domainledger.ManualAdjustment
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.OverdraftCompensated
import dev.kpetriv.sprout.core.domainledger.TimeBankCredited
import dev.kpetriv.sprout.core.domainledger.TimeBankStarted
import dev.kpetriv.sprout.core.domainledger.TimeBankStopped
import dev.kpetriv.sprout.core.domainledger.TransferToSavings
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.AmountDirection
import dev.kpetriv.sprout.core.ui.MoneyFormatter
import dev.kpetriv.sprout.feature.wallet.TestData.envelope
import dev.kpetriv.sprout.feature.wallet.TestData.gbp
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WalletStateMapperTest {
    @Test
    fun `history length per tier`() {
        assertEquals(0, WalletStateMapper.eventLimitFor(KidUiTier.SEEDLING, WalletMode.KID))
        assertEquals(10, WalletStateMapper.eventLimitFor(KidUiTier.SEEDLING, WalletMode.PARENT))
        assertEquals(10, WalletStateMapper.eventLimitFor(KidUiTier.SPROUT, WalletMode.KID))
        assertEquals(100, WalletStateMapper.eventLimitFor(KidUiTier.SAPLING, WalletMode.KID))
        assertEquals(100, WalletStateMapper.eventLimitFor(KidUiTier.SAPLING, WalletMode.PARENT))
    }

    @Test
    fun `negative manual adjustment is an outgoing magnitude with its reason`() {
        val row =
            assertNotNull(WalletStateMapper.eventUi(ManualAdjustment(envelope(1), gbp, Money(-250), "Broken mug")))
        assertEquals(EventCategory.ADJUSTMENT, row.category)
        assertEquals(AmountDirection.OUT, row.direction)
        assertEquals(Money(250), row.amount)
        assertEquals("Broken mug", row.note)
        assertEquals(envelope(1).eventId.value, row.eventId)
    }

    @Test
    fun `categories and directions per event type`() {
        val transfer = TransferToSavings(envelope(1), gbp, Money(100), TestData.savingsId)
        val stopped = TimeBankStopped(envelope(2, accountId = TestData.minutesId), "s1", Money(30))
        val game = GamePlayed(envelope(3), gbp, Money(20), "g1", 10, CalendarDate(2026, 11, 2))
        val compensated = OverdraftCompensated(envelope(4), gbp, Money(50), listOf(EventId(envelope(9).eventId.value)))

        assertEquals(
            EventCategory.SAVINGS to AmountDirection.MOVE,
            WalletStateMapper.eventUi(transfer)?.let {
                it.category to
                    it.direction
            },
        )
        val minutes = assertNotNull(WalletStateMapper.eventUi(stopped))
        assertEquals(EventCategory.SCREEN_TIME to AmountDirection.OUT, minutes.category to minutes.direction)
        assertEquals(Currency.Minutes, minutes.currency)
        assertEquals(EventCategory.GAMES, WalletStateMapper.eventUi(game)?.category)
        assertEquals(AmountDirection.IN, WalletStateMapper.eventUi(compensated)?.direction)
        assertNull(WalletStateMapper.eventUi(TimeBankStarted(envelope(5, accountId = TestData.minutesId), "s1")))
    }

    @Test
    fun `a game the ledger excluded is marked with why, any other event never is`() {
        val late = GamePlayed(envelope(1), gbp, Money(15), "arithmetic.sapling", 10, CalendarDate(2026, 10, 1))
        val overCap = GamePlayed(envelope(2), gbp, Money(11), "words.sprout", 8, CalendarDate(2026, 11, 2))
        val counted = GamePlayed(envelope(3), gbp, Money(5), "memory.sprout", 5, CalendarDate(2026, 11, 2))
        val purchase = TransferToSavings(envelope(4), gbp, Money(100), TestData.savingsId)
        val kid = TestData.kid(KidUiTier.SAPLING)
        val anomalies = listOf(
            LedgerAnomaly.PlayedOnOutOfRange(kid.id, late.eventId, late.playedOn, CalendarDate(2026, 11, 2)),
            LedgerAnomaly.DailyCapExceeded(kid.id, overCap.playedOn, gbp, overCap.eventId, Money(6)),
            LedgerAnomaly.OverdraftDetected(TestData.savingsId, Money(1), listOf(purchase.eventId)),
        )
        val excluded = WalletStateMapper.excludedGames(anomalies)
        assertEquals(
            mapOf(
                late.eventId.value to GameExclusion.PLAYED_ON_OUT_OF_RANGE,
                overCap.eventId.value to GameExclusion.DAILY_CAP_EXCEEDED,
            ),
            excluded,
        )

        val snapshot = WalletSnapshot(
            kid = kid,
            accounts = emptyList(),
            balances = emptyList(),
            events = listOf(late, overCap, counted, purchase),
            anomaliesCount = anomalies.size,
            excludedGames = excluded + (purchase.eventId.value to GameExclusion.DAILY_CAP_EXCEEDED),
        )
        val rows = WalletStateMapper(MoneyFormatter(Locale.UK))
            .ready(snapshot, WalletLocalState(), WalletMode.PARENT).events.associateBy { it.eventId }
        assertEquals(GameExclusion.PLAYED_ON_OUT_OF_RANGE, rows.getValue(late.eventId.value).excluded)
        assertEquals(GameExclusion.DAILY_CAP_EXCEEDED, rows.getValue(overCap.eventId.value).excluded)
        assertNull(rows.getValue(counted.eventId.value).excluded)
        assertNull(rows.getValue(purchase.eventId.value).excluded)
    }

    @Test
    fun `screen time credited by a reward is incoming minutes`() {
        val credited = TimeBankCredited(
            envelope(6, accountId = TestData.minutesId),
            Money(30),
            grantedBy = envelope(5).eventId,
            rewardId = "reward-tablet",
        )
        val row = assertNotNull(WalletStateMapper.eventUi(credited))
        assertEquals(EventCategory.SCREEN_TIME, row.category)
        assertEquals(AmountDirection.IN, row.direction)
        assertEquals(Money(30), row.amount)
        assertEquals(Currency.Minutes, row.currency)
    }
}

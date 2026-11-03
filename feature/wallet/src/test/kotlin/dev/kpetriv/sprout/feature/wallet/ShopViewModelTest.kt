package dev.kpetriv.sprout.feature.wallet

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import dev.kpetriv.sprout.core.database.RoomLedgerRepository
import dev.kpetriv.sprout.core.database.usecase.PurchaseResult
import dev.kpetriv.sprout.core.database.usecase.PurchaseStep
import dev.kpetriv.sprout.core.domainhousehold.AllowanceSchedule
import dev.kpetriv.sprout.core.domainhousehold.Reward
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.Violation
import dev.kpetriv.sprout.core.model.AllowanceCadence
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind
import dev.kpetriv.sprout.core.model.ScheduleId
import dev.kpetriv.sprout.core.time.FakeClock
import dev.kpetriv.sprout.core.time.HouseholdCalendar
import dev.kpetriv.sprout.core.ui.MoneyFormatter
import dev.kpetriv.sprout.feature.wallet.TestData.gbp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ShopViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val source = InMemoryWalletDataSource()
    private val ledger = FakeLedgerRepository()
    private val rewards = FakeRewardRepository()
    private val allowances = FakeAllowanceRepository()
    private val purchaser = FakeRewardPurchaser()

    // Tuesday 3 November 2026; the weekly allowance is paid on Saturdays (7th, 14th, 21st ...).
    private val clock = FakeClock(Instant.parse("2026-11-03T12:00:00Z"))

    private val sticker = reward("reward-sticker", "Sticker pack", RewardKind.OTHER, 150)
    private val tablet = reward("reward-tablet", "Tablet time", RewardKind.SCREEN_TIME, 300, minutes = 30)
    private val iceCream = reward("reward-ice-cream", "Ice cream", RewardKind.TREAT, 450)
    private val weekly = AllowanceSchedule(
        id = ScheduleId("schedule-1"),
        kidId = TestData.kidId,
        accountId = TestData.mainId,
        amount = Money(200),
        currency = gbp,
        cadence = AllowanceCadence.Weekly(DayOfWeek.SATURDAY),
        startsOn = CalendarDate(2026, 1, 1),
    )

    private fun reward(id: String, title: String, kind: RewardKind, cost: Long, minutes: Int? = null) = Reward(
        id = RewardId(id),
        householdId = HouseholdId("household-1"),
        title = title,
        kind = kind,
        cost = Money(cost),
        currency = gbp,
        minutesGranted = minutes,
    )

    private fun viewModel(
        tier: KidUiTier = KidUiTier.SPROUT,
        mode: WalletMode = WalletMode.KID,
        handle: SavedStateHandle = SavedStateHandle(
            mapOf(ShopViewModel.KID_ID_KEY to TestData.kidId.value, ShopViewModel.MODE_KEY to mode.name),
        ),
        mainMinor: Long = 120,
    ): ShopViewModel {
        TestData.seed(source, ledger, tier)
        ledger.balances.value = listOf(TestData.balance(TestData.mainId, gbp, mainMinor))
        rewards.rewards.value = listOf(iceCream, sticker, tablet)
        return ShopViewModel(
            savedStateHandle = handle,
            dataSource = source,
            ledgerRepository = ledger,
            rewards = rewards,
            allowances = allowances,
            purchaser = purchaser,
            formatter = MoneyFormatter(Locale.UK),
            calendar = HouseholdCalendar(clock) { ZoneOffset.UTC },
        )
    }

    private suspend fun ReceiveTurbine<ShopUiState>.awaitReady(): ShopUiState.Ready {
        var item = awaitItem()
        while (item == ShopUiState.Loading) item = awaitItem()
        return assertIs<ShopUiState.Ready>(item)
    }

    private fun ShopUiState.Ready.item(reward: Reward) = items.single { it.rewardId == reward.id }

    @Test
    fun `catalogue is priced against the balance, cheapest first`() = runTest {
        viewModel(mainMinor = 200).state.test {
            val ready = awaitReady()
            assertEquals(listOf(sticker.id, tablet.id, iceCream.id), ready.items.map { it.rewardId })
            assertEquals("£2.00", ready.balanceText)
            val stickerItem = ready.item(sticker)
            assertTrue(stickerItem.canAfford)
            assertNull(stickerItem.shortfallText)
            assertEquals(0, stickerItem.daysUntil)
            assertEquals(0, stickerItem.weeksToAfford)
            val tabletItem = ready.item(tablet)
            assertFalse(tabletItem.canAfford)
            assertEquals("£1.00", tabletItem.shortfallText)
            assertEquals("30 min", tabletItem.minutesText)
            assertNull(tabletItem.daysUntil, "no allowance, no estimate")
            assertFalse(ready.hasAllowance)
            assertTrue(ready.canPurchase)
        }
    }

    @Test
    fun `days until uses the kid's active allowance and today's date`() = runTest {
        allowances.schedules.value = listOf(weekly)

        viewModel(mainMinor = 120).state.test {
            val ready = awaitReady()
            assertTrue(ready.hasAllowance)
            // £0.30 short: Saturday's £2.00 covers it, 4 days away.
            assertEquals(4, ready.item(sticker).daysUntil)
            assertEquals(1, ready.item(sticker).weeksToAfford)
            // £3.30 short: two payments, the second on the 14th.
            assertEquals("£3.30", ready.item(iceCream).shortfallText)
            assertEquals(11, ready.item(iceCream).daysUntil)
            assertEquals(2, ready.item(iceCream).weeksToAfford)
        }
    }

    @Test
    fun `an inactive or foreign-currency allowance gives no estimate`() = runTest {
        allowances.schedules.value = listOf(weekly.copy(currency = Currency.Fiat("EUR")))

        viewModel().state.test {
            val ready = awaitReady()
            assertNull(ready.item(iceCream).daysUntil)
            assertNull(ready.item(iceCream).weeksToAfford)
        }
        allowances.schedules.value = listOf(weekly.copy(active = false))
        viewModel().state.test {
            val ready = awaitReady()
            assertFalse(ready.hasAllowance)
            assertNull(ready.item(iceCream).daysUntil)
        }
    }

    @Test
    fun `seedling prices read in coins`() = runTest {
        viewModel(tier = KidUiTier.SEEDLING, mainMinor = 745).state.test {
            val ready = awaitReady()
            assertEquals("7 coins", ready.balanceText)
            assertEquals("5 coins", ready.item(iceCream).priceText, "part coins round up on prices")
            assertTrue(ready.item(iceCream).canAfford)
        }
    }

    @Test
    fun `buying a screen-time reward shows enjoy with the minutes credited`() = runTest {
        purchaser.nextResult = PurchaseResult.Purchased(
            EventId("00000000-0000-4000-8000-000000000001"),
            timeCreditId = EventId("00000000-0000-4000-8000-000000000011"),
        )
        val viewModel = viewModel(mainMinor = 1_000)

        viewModel.state.test {
            awaitReady()
            viewModel.onEvent(ShopEvent.Select(tablet.id))
            assertEquals(tablet.id, (awaitItem() as ShopUiState.Ready).selected?.rewardId)
            viewModel.onEvent(ShopEvent.ConfirmPurchase)
            val done = expectMostRecentItem() as ShopUiState.Ready
            assertNull(done.selected)
            assertFalse(done.isPurchasing)
            assertEquals(ShopMessage.Purchased("Tablet time", minutesText = "30 min"), done.message)

            viewModel.onEvent(ShopEvent.MessageShown)
            assertNull((awaitItem() as ShopUiState.Ready).message)
        }
        assertEquals(listOf(TestData.kidId to tablet.id), purchaser.purchases)
    }

    @Test
    fun `a screen-time purchase without its credit is reported as pending`() = runTest {
        purchaser.nextResult = PurchaseResult.Purchased(EventId("00000000-0000-4000-8000-000000000001"), null)
        val viewModel = viewModel(mainMinor = 1_000)

        viewModel.state.test {
            awaitReady()
            viewModel.onEvent(ShopEvent.Select(tablet.id))
            viewModel.onEvent(ShopEvent.ConfirmPurchase)
            val message = assertIs<ShopMessage.Purchased>((expectMostRecentItem() as ShopUiState.Ready).message)
            assertTrue(message.minutesPending)
        }
    }

    @Test
    fun `a treat purchase has no minutes`() = runTest {
        purchaser.nextResult = PurchaseResult.Purchased(EventId("00000000-0000-4000-8000-000000000002"), null)
        val viewModel = viewModel(mainMinor = 1_000)

        viewModel.state.test {
            awaitReady()
            viewModel.onEvent(ShopEvent.Select(iceCream.id))
            viewModel.onEvent(ShopEvent.ConfirmPurchase)
            assertEquals(
                ShopMessage.Purchased("Ice cream", minutesText = null),
                (expectMostRecentItem() as ShopUiState.Ready).message,
            )
        }
    }

    @Test
    fun `rejections map to friendly failures`() = runTest {
        val viewModel = viewModel(mainMinor = 1_000)
        val cases = listOf(
            PurchaseResult.Refused(AppendResult.Rejected(RoomLedgerRepository.CATALOGUE_REJECTION)) to
                PurchaseFailure.CatalogueChanged,
            PurchaseResult.Refused(
                AppendResult.Rejected(
                    "rules",
                    listOf(
                        Violation.InsufficientFunds(TestData.mainId, available = Money(120), requested = Money(450)),
                    ),
                ),
            ) to PurchaseFailure.NotEnoughMoney("£3.30"),
            PurchaseResult.Unavailable to PurchaseFailure.CatalogueChanged,
            PurchaseResult.NoAccount to PurchaseFailure.NoAccount,
            PurchaseResult.Refused(AppendResult.Conflict(EventId("00000000-0000-4000-8000-000000000003"), "a", "b")) to
                PurchaseFailure.Failed,
            // A refused time credit rolled the purchase back: a generic failure, never "not enough money".
            PurchaseResult.Refused(
                AppendResult.Rejected(
                    "rules",
                    listOf(
                        Violation.InsufficientFunds(TestData.mainId, available = Money(120), requested = Money(450)),
                    ),
                ),
                PurchaseStep.TIME_CREDIT,
            ) to PurchaseFailure.Failed,
        )

        viewModel.state.test {
            awaitReady()
            for ((result, failure) in cases) {
                purchaser.nextResult = result
                viewModel.onEvent(ShopEvent.Select(iceCream.id))
                viewModel.onEvent(ShopEvent.ConfirmPurchase)
                assertEquals(ShopMessage.Failed(failure), (expectMostRecentItem() as ShopUiState.Ready).message)
                viewModel.onEvent(ShopEvent.MessageShown)
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a storage failure is a generic failure, and a reward gone from the catalogue is caught first`() = runTest {
        purchaser.failure = IOException("disk full")
        val viewModel = viewModel(mainMinor = 1_000)

        viewModel.state.test {
            awaitReady()
            viewModel.onEvent(ShopEvent.Select(iceCream.id))
            viewModel.onEvent(ShopEvent.ConfirmPurchase)
            assertEquals(
                ShopMessage.Failed(PurchaseFailure.Failed),
                (expectMostRecentItem() as ShopUiState.Ready).message,
            )
            viewModel.onEvent(ShopEvent.MessageShown)

            viewModel.onEvent(ShopEvent.Select(sticker.id))
            rewards.rewards.value = listOf(iceCream, tablet)
            val removed = expectMostRecentItem() as ShopUiState.Ready
            assertNull(removed.selected, "a reward that left the catalogue closes its dialog")
            viewModel.onEvent(ShopEvent.ConfirmPurchase)
            expectNoEvents()
        }
        assertTrue(purchaser.purchases.isEmpty())
    }

    @Test
    fun `confirm while purchasing is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        purchaser.gate = gate
        purchaser.nextResult = PurchaseResult.Purchased(EventId("00000000-0000-4000-8000-000000000004"), null)
        val viewModel = viewModel(mainMinor = 1_000)

        viewModel.state.test {
            awaitReady()
            viewModel.onEvent(ShopEvent.Select(sticker.id))
            viewModel.onEvent(ShopEvent.ConfirmPurchase)
            viewModel.onEvent(ShopEvent.ConfirmPurchase)
            viewModel.onEvent(ShopEvent.DismissConfirm)
            assertTrue((expectMostRecentItem() as ShopUiState.Ready).isPurchasing)
            gate.complete(Unit)
            assertIs<ShopMessage.Purchased>((awaitItem() as ShopUiState.Ready).message)
        }
        assertEquals(1, purchaser.purchases.size)
    }

    @Test
    fun `a parent browsing cannot buy`() = runTest {
        val viewModel = viewModel(mode = WalletMode.PARENT, mainMinor = 1_000)

        viewModel.state.test {
            assertFalse(awaitReady().canPurchase)
            viewModel.onEvent(ShopEvent.Select(sticker.id))
            viewModel.onEvent(ShopEvent.ConfirmPurchase)
            assertEquals(
                ShopMessage.Failed(PurchaseFailure.NotAllowed),
                (expectMostRecentItem() as ShopUiState.Ready).message,
            )
        }
        assertTrue(purchaser.purchases.isEmpty())
    }

    @Test
    fun `wishlist toggles and lives in the saved state`() = runTest {
        val handle = SavedStateHandle(mapOf(ShopViewModel.KID_ID_KEY to TestData.kidId.value))
        val viewModel = viewModel(tier = KidUiTier.SAPLING, handle = handle)

        viewModel.state.test {
            assertTrue(awaitReady().wishlist.isEmpty())
            viewModel.onEvent(ShopEvent.ToggleWishlist(iceCream.id))
            val wished = awaitItem() as ShopUiState.Ready
            assertEquals(listOf(iceCream.id), wished.wishlist.map { it.rewardId })
            assertTrue(wished.item(iceCream).wishlisted)
            assertEquals(arrayListOf(iceCream.id.value), handle.get<ArrayList<String>>(ShopViewModel.WISHLIST_KEY))

            viewModel.onEvent(ShopEvent.ToggleWishlist(iceCream.id))
            assertTrue((awaitItem() as ShopUiState.Ready).wishlist.isEmpty())
        }
    }

    @Test
    fun `wishlist survives a new ViewModel on the same saved state`() = runTest {
        val handle = SavedStateHandle(
            mapOf(
                ShopViewModel.KID_ID_KEY to TestData.kidId.value,
                ShopViewModel.WISHLIST_KEY to arrayListOf(sticker.id.value),
            ),
        )

        viewModel(tier = KidUiTier.SAPLING, handle = handle).state.test {
            assertEquals(listOf(sticker.id), awaitReady().wishlist.map { it.rewardId })
        }
    }

    @Test
    fun `missing kid and load failures are errors, and refresh retries`() = runTest {
        val missing = viewModel(
            handle = SavedStateHandle(mapOf(ShopViewModel.KID_ID_KEY to KidId("someone-else").value)),
        )
        missing.state.test {
            var item = awaitItem()
            while (item == ShopUiState.Loading) item = awaitItem()
            assertEquals(ShopUiState.Error(ShopError.KID_NOT_FOUND), item)
        }

        val viewModel = viewModel()
        rewards.failing = true
        viewModel.state.test {
            var item = awaitItem()
            while (item == ShopUiState.Loading) item = awaitItem()
            assertEquals(ShopUiState.Error(ShopError.LOAD_FAILED), item)
            rewards.failing = false
            viewModel.onEvent(ShopEvent.Refresh)
            assertNotNull(awaitItem() as? ShopUiState.Ready)
        }
    }
}

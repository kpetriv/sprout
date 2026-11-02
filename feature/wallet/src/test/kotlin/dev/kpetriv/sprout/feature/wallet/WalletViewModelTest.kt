package dev.kpetriv.sprout.feature.wallet

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import dev.kpetriv.sprout.core.domainledger.AllowancePaid
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.ChoreRewarded
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.InterestAccrued
import dev.kpetriv.sprout.core.domainledger.LedgerAnomaly
import dev.kpetriv.sprout.core.domainledger.ManualAdjustment
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.RewardPurchased
import dev.kpetriv.sprout.core.domainledger.TimeBankStarted
import dev.kpetriv.sprout.core.domainledger.Violation
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.ui.AmountDirection
import dev.kpetriv.sprout.core.ui.MoneyFormatter
import dev.kpetriv.sprout.feature.wallet.TestData.gbp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalletViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val source = InMemoryWalletDataSource()
    private val repository = FakeLedgerRepository()
    private val writer = FakeLedgerWriter()

    private fun viewModel(mode: WalletMode = WalletMode.PARENT, kidId: KidId = TestData.kidId) = WalletViewModel(
        savedStateHandle = SavedStateHandle(
            mapOf(WalletViewModel.KID_ID_KEY to kidId.value, WalletViewModel.MODE_KEY to mode.name),
        ),
        dataSource = source,
        ledgerRepository = repository,
        ledgerWriter = writer,
        formatter = MoneyFormatter(Locale.UK),
    )

    /** The first item after [WalletUiState.Loading] (which StateFlow may conflate away). */
    private suspend fun ReceiveTurbine<WalletUiState>.awaitLoaded(): WalletUiState {
        var item = awaitItem()
        while (item == WalletUiState.Loading) item = awaitItem()
        return item
    }

    private suspend fun ReceiveTurbine<WalletUiState>.awaitReady(): WalletUiState.Ready =
        assertIs<WalletUiState.Ready>(awaitLoaded())

    private val history = listOf(
        ChoreRewarded(TestData.envelope(6), gbp, Money(150), "chore-1"),
        RewardPurchased(TestData.envelope(5), gbp, Money(399), "reward-1"),
        TimeBankStarted(TestData.envelope(4, accountId = TestData.minutesId), "session-1"),
        InterestAccrued(TestData.envelope(3, accountId = TestData.savingsId), gbp, Money(12), Money(1_200), 500, 73),
        InterestAccrued(TestData.envelope(2, accountId = TestData.savingsId), gbp, Money(8), Money(1_000), 500, 60),
        AllowancePaid(TestData.envelope(1), gbp, Money(500), "weekly"),
    )

    @Test
    fun `starts loading`() = runTest {
        TestData.seed(source, repository, KidUiTier.SPROUT)
        assertEquals(WalletUiState.Loading, viewModel().state.value)
    }

    @Test
    fun `seedling shows whole coins and loads no history for the kid`() = runTest {
        TestData.seed(source, repository, KidUiTier.SEEDLING)
        repository.events.value = history

        viewModel(mode = WalletMode.KID).state.test {
            val ready = awaitReady()
            assertEquals(KidUiTier.SEEDLING, ready.tier)
            assertEquals(7, ready.coins)
            assertEquals(0.7f, ready.jarLevel)
            assertEquals("7 coins", ready.mainBalance?.kidFormatted)
            assertEquals("£7.45", ready.mainBalance?.formatted)
            assertEquals("1 h 20 min", ready.minutesBalance?.kidFormatted)
            assertEquals("12 coins", ready.savingsGoal?.savedText)
            assertTrue(ready.events.isEmpty())
            assertTrue(repository.requestedLimits.isEmpty(), "Seedling must not query history")
            assertFalse(ready.isParentView)
        }
    }

    @Test
    fun `a parent looking at a seedling wallet gets recent activity`() = runTest {
        TestData.seed(source, repository, KidUiTier.SEEDLING)
        repository.events.value = history

        viewModel(mode = WalletMode.PARENT).state.test {
            val ready = awaitReady()
            assertTrue(ready.isParentView)
            assertEquals(listOf(10), repository.requestedLimits)
            assertEquals(5, ready.events.size)
            assertEquals("7 coins", ready.mainBalance?.kidFormatted, "the kid's own numbers stay whole coins")
        }
    }

    @Test
    fun `sprout shows decimals and the last 10 events`() = runTest {
        TestData.seed(source, repository, KidUiTier.SPROUT)
        repository.events.value = List(15) { ChoreRewarded(TestData.envelope(it + 1), gbp, Money(100), "c$it") }

        viewModel().state.test {
            val ready = awaitReady()
            assertEquals(listOf(10), repository.requestedLimits)
            assertEquals(10, ready.events.size)
            assertEquals("£7.45", ready.mainBalance?.kidFormatted)
            assertEquals("£12.50", ready.savingsGoal?.savedText)
            assertNull(ready.savingsGoal?.targetText)
            assertEquals(
                listOf(TestData.mainId, TestData.minutesId, TestData.savingsId),
                ready.balances.map {
                    it.accountId
                },
            )
        }
    }

    @Test
    fun `sapling loads 100 events, sums interest and skips value-less events`() = runTest {
        TestData.seed(source, repository, KidUiTier.SAPLING)
        repository.events.value = history

        viewModel().state.test {
            val ready = awaitReady()
            assertEquals(listOf(100), repository.requestedLimits)
            assertEquals(5, ready.events.size, "TimeBankStarted moves no value and is not listed")
            assertEquals("£0.20", ready.interestEarned)
            assertEquals(
                listOf(EventCategory.CHORES, EventCategory.ALLOWANCE, EventCategory.SHOP, EventCategory.INTEREST),
                ready.availableFilters,
            )
            val shop = ready.events.single { it.category == EventCategory.SHOP }
            assertEquals(AmountDirection.OUT, shop.direction)
            assertEquals(Money(399), shop.amount)
        }
    }

    @Test
    fun `sapling filter narrows the history`() = runTest {
        TestData.seed(source, repository, KidUiTier.SAPLING)
        repository.events.value = history
        val viewModel = viewModel()

        viewModel.state.test {
            awaitReady()
            viewModel.onEvent(WalletEvent.FilterChanged(EventCategory.INTEREST))
            val filtered = awaitItem() as WalletUiState.Ready
            assertEquals(EventCategory.INTEREST, filtered.filter)
            assertEquals(2, filtered.events.size)
            assertTrue(filtered.events.all { it.category == EventCategory.INTEREST })
            assertEquals(4, filtered.availableFilters.size, "chips stay available while filtered")

            viewModel.onEvent(WalletEvent.FilterChanged(null))
            assertEquals(5, (awaitItem() as WalletUiState.Ready).events.size)
        }
    }

    @Test
    fun `a tier change re-subscribes with the new history limit`() = runTest {
        TestData.seed(source, repository, KidUiTier.SEEDLING)
        repository.events.value = history

        viewModel(mode = WalletMode.KID).state.test {
            assertEquals(KidUiTier.SEEDLING, awaitReady().tier)
            TestData.setTier(source, KidUiTier.SAPLING)
            val sapling = awaitItem() as WalletUiState.Ready
            assertEquals(KidUiTier.SAPLING, sapling.tier)
            assertEquals("£7.45", sapling.mainBalance?.kidFormatted)
            assertEquals(listOf(100), repository.requestedLimits)
            assertEquals(5, sapling.events.size)
        }
    }

    @Test
    fun `balances update live`() = runTest {
        TestData.seed(source, repository, KidUiTier.SPROUT)

        viewModel().state.test {
            awaitReady()
            repository.balances.value = listOf(TestData.balance(TestData.mainId, gbp, 1_000))
            val updated = awaitItem() as WalletUiState.Ready
            assertEquals("£10.00", updated.mainBalance?.formatted)
            assertEquals("0 min", updated.minutesBalance?.formatted, "accounts without a balance show zero")
        }
    }

    @Test
    fun `anomalies come from the ledger and update live`() = runTest {
        TestData.seed(source, repository, KidUiTier.SPROUT)
        repository.balances.value = listOf(TestData.balance(TestData.mainId, gbp, -50))
        repository.anomalies.value = listOf(
            LedgerAnomaly.OverdraftDetected(TestData.mainId, Money(50), listOf(TestData.envelope(1).eventId)),
            LedgerAnomaly.InvalidEvent(TestData.envelope(2).eventId, listOf(Violation.ZeroAdjustment)),
        )

        viewModel().state.test {
            assertEquals(2, awaitReady().anomaliesCount)
            repository.anomalies.value = emptyList()
            assertEquals(0, (awaitItem() as WalletUiState.Ready).anomaliesCount)
        }
    }

    @Test
    fun `a conflicting append explains the clash`() = runTest {
        TestData.seed(source, repository, KidUiTier.SPROUT)
        writer.nextResult = AppendResult.Conflict(TestData.envelope(1).eventId, "a".repeat(64), "b".repeat(64))
        val viewModel = viewModel()

        viewModel.state.test {
            awaitReady()
            viewModel.onEvent(WalletEvent.OpenAdjust)
            viewModel.onEvent(WalletEvent.SubmitAdjust(100, AdjustmentSign.CREDIT, "Bonus"))
            val state = expectMostRecentItem() as WalletUiState.Ready
            assertEquals(AdjustmentError.Conflict, state.adjustment?.error)
            assertNull(state.message)
        }
    }

    @Test
    fun `a failure after the sheet was dismissed becomes a snackbar message`() = runTest {
        TestData.seed(source, repository, KidUiTier.SPROUT)
        val gate = CompletableDeferred<Unit>()
        writer.gate = gate
        writer.nextResult = AppendResult.Rejected(reason = "storage")
        val viewModel = viewModel()

        viewModel.state.test {
            awaitReady()
            viewModel.onEvent(WalletEvent.OpenAdjust)
            viewModel.onEvent(WalletEvent.SubmitAdjust(100, AdjustmentSign.CREDIT, "Bonus"))
            viewModel.onEvent(WalletEvent.DismissAdjust)
            assertNull((expectMostRecentItem() as WalletUiState.Ready).adjustment)

            gate.complete(Unit)
            val after = awaitItem() as WalletUiState.Ready
            assertNull(after.adjustment)
            assertEquals(WalletMessage.AdjustmentFailed(AdjustmentError.Failed), after.message)

            viewModel.onEvent(WalletEvent.MessageShown)
            assertNull((awaitItem() as WalletUiState.Ready).message)
        }
    }

    @Test
    fun `missing kid is an error`() = runTest {
        TestData.seed(source, repository, KidUiTier.SPROUT)

        viewModel(kidId = KidId("someone-else")).state.test {
            assertEquals(WalletUiState.Error(WalletError.KID_NOT_FOUND), awaitLoaded())
        }
    }

    @Test
    fun `load failure is an error and refresh retries`() = runTest {
        TestData.seed(source, repository, KidUiTier.SPROUT)
        source.failing = true
        val viewModel = viewModel()

        viewModel.state.test {
            assertEquals(WalletUiState.Error(WalletError.LOAD_FAILED), awaitLoaded())
            source.failing = false
            viewModel.onEvent(WalletEvent.Refresh)
            assertIs<WalletUiState.Ready>(awaitItem())
        }
    }

    @Test
    fun `kid mode cannot adjust`() = runTest {
        TestData.seed(source, repository, KidUiTier.SPROUT)
        val viewModel = viewModel(mode = WalletMode.KID)

        viewModel.state.test {
            assertFalse(awaitReady().canAdjust)
            viewModel.onEvent(WalletEvent.OpenAdjust)
            expectNoEvents()
        }
        assertTrue(writer.appended.isEmpty())
    }

    @Test
    fun `parent cannot adjust a kid without a money account`() = runTest {
        TestData.seed(source, repository, KidUiTier.SPROUT)
        source.accounts.value = mapOf(TestData.kidId to TestData.accounts.filter { it.currency == Currency.Minutes })

        viewModel().state.test {
            assertFalse(awaitReady().canAdjust)
        }
    }

    @Test
    fun `credit adjustment is appended to the main account and closes the sheet`() = runTest {
        TestData.seed(source, repository, KidUiTier.SPROUT)
        val viewModel = viewModel()

        viewModel.state.test {
            assertTrue(awaitReady().canAdjust)
            viewModel.onEvent(WalletEvent.OpenAdjust)
            assertEquals(AdjustmentUi(gbp), (awaitItem() as WalletUiState.Ready).adjustment)

            viewModel.onEvent(WalletEvent.SubmitAdjust(250, AdjustmentSign.CREDIT, "  Birthday money "))
            val done = expectMostRecentItem() as WalletUiState.Ready
            assertNull(done.adjustment)
            assertEquals(WalletMessage.AdjustmentSaved, done.message)

            viewModel.onEvent(WalletEvent.MessageShown)
            assertNull((awaitItem() as WalletUiState.Ready).message)
        }
        val event = assertIs<ManualAdjustment>(writer.appended.single())
        assertEquals(Money(250), event.amount)
        assertEquals(gbp, event.currency)
        assertEquals("Birthday money", event.reason)
        assertEquals(TestData.mainId, event.envelope.accountId)
        assertEquals(TestData.kidId, event.envelope.kidId)
    }

    @Test
    fun `debit adjustment posts a negative amount`() = runTest {
        TestData.seed(source, repository, KidUiTier.SAPLING)
        val viewModel = viewModel()

        viewModel.state.test {
            awaitReady()
            viewModel.onEvent(WalletEvent.OpenAdjust)
            viewModel.onEvent(WalletEvent.SubmitAdjust(300, AdjustmentSign.DEBIT, "Lost library book"))
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(Money(-300), assertIs<ManualAdjustment>(writer.appended.single()).amount)
    }

    @Test
    fun `insufficient funds rejection keeps the sheet open with the available amount`() = runTest {
        TestData.seed(source, repository, KidUiTier.SPROUT)
        writer.nextResult = AppendResult.Rejected(
            reason = "rules",
            violations = listOf(Violation.InsufficientFunds(TestData.mainId, Money(745), Money(-1_000))),
        )
        val viewModel = viewModel()

        viewModel.state.test {
            awaitReady()
            viewModel.onEvent(WalletEvent.OpenAdjust)
            viewModel.onEvent(WalletEvent.SubmitAdjust(1_000, AdjustmentSign.DEBIT, "Broken window"))
            val rejected = expectMostRecentItem() as WalletUiState.Ready
            val adjustment = assertNotNull(rejected.adjustment)
            assertFalse(adjustment.isSubmitting)
            assertEquals(AdjustmentError.InsufficientFunds("£7.45"), adjustment.error)
            assertNull(rejected.message)
        }
    }

    @Test
    fun `parent-only rejection maps to not allowed`() = runTest {
        TestData.seed(source, repository, KidUiTier.SPROUT)
        writer.nextResult = AppendResult.Rejected(
            reason = "rules",
            violations = listOf(Violation.ParentOnly("ManualAdjustment", Role.KID)),
        )
        val viewModel = viewModel()

        viewModel.state.test {
            awaitReady()
            viewModel.onEvent(WalletEvent.OpenAdjust)
            viewModel.onEvent(WalletEvent.SubmitAdjust(100, AdjustmentSign.CREDIT, "Bonus"))
            assertEquals(AdjustmentError.NotAllowed, (expectMostRecentItem() as WalletUiState.Ready).adjustment?.error)
        }
    }

    @Test
    fun `rejection without violations and writer failures are generic errors`() = runTest {
        TestData.seed(source, repository, KidUiTier.SPROUT)
        writer.nextResult = AppendResult.Rejected(reason = "signature failed")
        val viewModel = viewModel()

        viewModel.state.test {
            awaitReady()
            viewModel.onEvent(WalletEvent.OpenAdjust)
            viewModel.onEvent(WalletEvent.SubmitAdjust(100, AdjustmentSign.CREDIT, "Bonus"))
            assertEquals(AdjustmentError.Failed, (expectMostRecentItem() as WalletUiState.Ready).adjustment?.error)

            writer.failure = IOException("disk full")
            viewModel.onEvent(WalletEvent.SubmitAdjust(100, AdjustmentSign.CREDIT, "Bonus"))
            assertEquals(AdjustmentError.Failed, (expectMostRecentItem() as WalletUiState.Ready).adjustment?.error)
        }
    }

    @Test
    fun `invalid input is refused before reaching the ledger`() = runTest {
        TestData.seed(source, repository, KidUiTier.SPROUT)
        val viewModel = viewModel()

        viewModel.state.test {
            awaitReady()
            viewModel.onEvent(WalletEvent.OpenAdjust)
            viewModel.onEvent(WalletEvent.SubmitAdjust(0, AdjustmentSign.CREDIT, "Nothing"))
            assertEquals(
                AdjustmentError.InvalidAmount,
                (expectMostRecentItem() as WalletUiState.Ready).adjustment?.error,
            )
            viewModel.onEvent(WalletEvent.SubmitAdjust(100, AdjustmentSign.CREDIT, "   "))
            assertEquals(
                AdjustmentError.ReasonRequired,
                (expectMostRecentItem() as WalletUiState.Ready).adjustment?.error,
            )
        }
        assertTrue(writer.appended.isEmpty())
    }

    @Test
    fun `dismiss closes the sheet and submit without an open sheet is ignored`() = runTest {
        TestData.seed(source, repository, KidUiTier.SPROUT)
        val viewModel = viewModel()

        viewModel.state.test {
            awaitReady()
            viewModel.onEvent(WalletEvent.OpenAdjust)
            assertNotNull((awaitItem() as WalletUiState.Ready).adjustment)
            viewModel.onEvent(WalletEvent.DismissAdjust)
            assertNull((awaitItem() as WalletUiState.Ready).adjustment)
            viewModel.onEvent(WalletEvent.SubmitAdjust(100, AdjustmentSign.CREDIT, "Bonus"))
            expectNoEvents()
        }
        assertTrue(writer.appended.isEmpty())
    }
}

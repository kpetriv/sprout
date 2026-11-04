package dev.kpetriv.sprout.feature.wallet

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import dev.kpetriv.sprout.core.database.usecase.RecordManualAdjustmentUseCase
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.MoneyFormatter
import kotlinx.coroutines.CancellationException
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

/** "Scan a receipt" in the manual adjustment sheet (M6 §3.4): [WalletViewModel] with a [FakeReceiptScanner]. */
class WalletReceiptViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val source = InMemoryWalletDataSource()
    private val repository = FakeLedgerRepository()
    private val writer = FakeLedgerWriter()
    private val policies = FakeHouseholdPolicyRepository(signaturesRequired = true)
    private val scanner = FakeReceiptScanner()

    private fun viewModel(mode: WalletMode = WalletMode.PARENT) = WalletViewModel(
        savedStateHandle = SavedStateHandle(
            mapOf(WalletViewModel.KID_ID_KEY to TestData.kidId.value, WalletViewModel.MODE_KEY to mode.name),
        ),
        dataSource = source,
        ledgerRepository = repository,
        savingsGoals = FakeSavingsGoalRepository(),
        policies = policies,
        recordAdjustment = RecordManualAdjustmentUseCase(DataSourceAccountRepository(source), writer, policies),
        authorizations = FakeParentAuthorizationRequester(),
        promptText = { name, amount -> "Adjust $name's balance by $amount" },
        formatter = MoneyFormatter(Locale.UK),
        receiptScanner = scanner,
    )

    private suspend fun ReceiveTurbine<WalletUiState>.awaitReady(): WalletUiState.Ready {
        var item = awaitItem()
        while (item == WalletUiState.Loading) item = awaitItem()
        return assertIs<WalletUiState.Ready>(item)
    }

    @Test
    fun `a scanned receipt becomes the sheet's prefill until the form takes it`() = runTest {
        TestData.seed(source, repository, KidUiTier.SPROUT)
        val gate = CompletableDeferred<Unit>()
        scanner.gate = gate
        scanner.result = AdjustmentPrefill(Money(1_249), "Corner Shop")
        val viewModel = viewModel()

        viewModel.state.test {
            awaitReady()
            viewModel.onEvent(WalletEvent.OpenAdjust)
            viewModel.onEvent(WalletEvent.ReceiptPicked("content://picker/receipt-1"))
            val scanning = assertNotNull((expectMostRecentItem() as WalletUiState.Ready).adjustment)
            assertTrue(scanning.scanning)
            viewModel.onEvent(WalletEvent.ReceiptPicked("content://picker/receipt-2"))
            assertEquals(listOf("content://picker/receipt-1"), scanner.scanned, "one scan at a time")

            gate.complete(Unit)
            val done = assertNotNull((awaitItem() as WalletUiState.Ready).adjustment)
            assertFalse(done.scanning)
            assertFalse(done.scanFailed)
            assertEquals(AdjustmentPrefill(Money(1_249), "Corner Shop"), done.prefill)

            viewModel.onEvent(WalletEvent.PrefillApplied)
            val taken = assertNotNull((awaitItem() as WalletUiState.Ready).adjustment)
            assertNull(taken.prefill)
            assertTrue(writer.appended.isEmpty(), "a scan never saves anything")
        }
    }

    @Test
    fun `a receipt with nothing usable, a failing scanner or a slow one says so`() = runTest {
        TestData.seed(source, repository, KidUiTier.SPROUT)
        val viewModel = viewModel()

        viewModel.state.test {
            awaitReady()
            viewModel.onEvent(WalletEvent.OpenAdjust)

            scanner.result = AdjustmentPrefill(amount = null, merchant = " ")
            viewModel.onEvent(WalletEvent.ReceiptPicked("content://a"))
            var sheet = assertNotNull((expectMostRecentItem() as WalletUiState.Ready).adjustment)
            assertTrue(sheet.scanFailed)
            assertNull(sheet.prefill)

            scanner.failure = IOException("unreadable")
            viewModel.onEvent(WalletEvent.ReceiptPicked("content://b"))
            sheet = assertNotNull((expectMostRecentItem() as WalletUiState.Ready).adjustment)
            assertTrue(sheet.scanFailed)
            assertFalse(sheet.scanning)

            scanner.failure = null
            scanner.result = AdjustmentPrefill(Money(500), null)
            scanner.delayMillis = WalletViewModel.SCAN_TIMEOUT_MILLIS + 1
            viewModel.onEvent(WalletEvent.ReceiptPicked("content://c"))
            testScheduler.advanceTimeBy(WalletViewModel.SCAN_TIMEOUT_MILLIS + 2)
            sheet = assertNotNull((expectMostRecentItem() as WalletUiState.Ready).adjustment)
            assertTrue(sheet.scanFailed)
            assertFalse(sheet.scanning)
        }
    }

    @Test
    fun `a merchant without a total prefills the reason and still says no total was found (review L14)`() = runTest {
        TestData.seed(source, repository, KidUiTier.SPROUT)
        scanner.result = AdjustmentPrefill(amount = null, merchant = "Corner Shop")
        val viewModel = viewModel()

        viewModel.state.test {
            awaitReady()
            viewModel.onEvent(WalletEvent.OpenAdjust)
            viewModel.onEvent(WalletEvent.ReceiptPicked("content://a"))
            val sheet = assertNotNull((expectMostRecentItem() as WalletUiState.Ready).adjustment)
            assertTrue(sheet.scanFailed)
            assertEquals(AdjustmentPrefill(null, "Corner Shop"), sheet.prefill)
        }
    }

    @Test
    fun `a cancellation thrown by the scanner ends the scan instead of leaving it running (review M6)`() = runTest {
        TestData.seed(source, repository, KidUiTier.SPROUT)
        // An ML Kit task cancelled by Play services while the wallet's own coroutine is still active.
        scanner.failure = CancellationException("play services cancelled the task")
        scanner.delayMillis = 100
        val viewModel = viewModel()

        viewModel.state.test {
            awaitReady()
            viewModel.onEvent(WalletEvent.OpenAdjust)
            viewModel.onEvent(WalletEvent.ReceiptPicked("content://a"))
            assertTrue(assertNotNull((expectMostRecentItem() as WalletUiState.Ready).adjustment).scanning)

            testScheduler.advanceTimeBy(101)
            val sheet = assertNotNull((expectMostRecentItem() as WalletUiState.Ready).adjustment)
            assertFalse(sheet.scanning)
            assertTrue(sheet.scanFailed)
            assertNull(sheet.prefill)
        }
    }

    @Test
    fun `a slow first scan says the scanner is being prepared and still lands within the budget (D-M6-54)`() = runTest {
        TestData.seed(source, repository, KidUiTier.SPROUT)
        scanner.result = AdjustmentPrefill(Money(1_249), "Corner Shop")
        scanner.delayMillis = 13_000 // The emulator walk's first scan.
        val viewModel = viewModel()

        viewModel.state.test {
            awaitReady()
            viewModel.onEvent(WalletEvent.OpenAdjust)
            viewModel.onEvent(WalletEvent.ReceiptPicked("content://a"))
            testScheduler.advanceTimeBy(WalletViewModel.SCAN_SLOW_MILLIS - 1)
            var sheet = assertNotNull((expectMostRecentItem() as WalletUiState.Ready).adjustment)
            assertTrue(sheet.scanning)
            assertFalse(sheet.scanSlow)

            testScheduler.advanceTimeBy(2)
            sheet = assertNotNull((expectMostRecentItem() as WalletUiState.Ready).adjustment)
            assertTrue(sheet.scanning)
            assertTrue(sheet.scanSlow)

            testScheduler.advanceTimeBy(13_000)
            sheet = assertNotNull((expectMostRecentItem() as WalletUiState.Ready).adjustment)
            assertFalse(sheet.scanning)
            assertFalse(sheet.scanSlow)
            assertFalse(sheet.scanFailed)
            assertEquals(AdjustmentPrefill(Money(1_249), "Corner Shop"), sheet.prefill)
        }
        assertEquals(25_000L, WalletViewModel.SCAN_TIMEOUT_MILLIS)
    }

    @Test
    fun `with the On-device AI switch off the sheet offers no scan and ignores a picked image (D-M6-51)`() = runTest {
        TestData.seed(source, repository, KidUiTier.SPROUT)
        scanner.available = false
        val viewModel = viewModel()

        viewModel.state.test {
            awaitReady()
            viewModel.onEvent(WalletEvent.OpenAdjust)
            val sheet = assertNotNull((expectMostRecentItem() as WalletUiState.Ready).adjustment)
            assertFalse(sheet.canScan)
            viewModel.onEvent(WalletEvent.ReceiptPicked("content://a"))
            assertFalse(assertNotNull((viewModel.state.value as WalletUiState.Ready).adjustment).scanning)
        }
        assertTrue(scanner.scanned.isEmpty())
    }

    @Test
    fun `nothing is saved while a receipt is being read (audit L5)`() = runTest {
        TestData.seed(source, repository, KidUiTier.SPROUT)
        val gate = CompletableDeferred<Unit>()
        scanner.gate = gate
        scanner.result = AdjustmentPrefill(Money(1_249), "Corner Shop")
        val viewModel = viewModel()

        viewModel.state.test {
            awaitReady()
            viewModel.onEvent(WalletEvent.OpenAdjust)
            viewModel.onEvent(WalletEvent.ReceiptPicked("content://a"))
            viewModel.onEvent(WalletEvent.SubmitAdjust(500, AdjustmentSign.CREDIT, "Pocket money"))
            val sheet = assertNotNull((expectMostRecentItem() as WalletUiState.Ready).adjustment)
            assertTrue(sheet.scanning)
            assertFalse(sheet.isSubmitting)
            gate.complete(Unit)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(writer.appended.isEmpty())
    }

    @Test
    fun `receipts are for parents with the sheet open`() = runTest {
        TestData.seed(source, repository, KidUiTier.SPROUT)
        val parent = viewModel()
        parent.state.test {
            awaitReady()
            parent.onEvent(WalletEvent.ReceiptPicked("content://a"))
            assertNull((parent.state.value as WalletUiState.Ready).adjustment)
        }
        val kid = viewModel(mode = WalletMode.KID)
        kid.state.test {
            awaitReady()
            kid.onEvent(WalletEvent.OpenAdjust)
            kid.onEvent(WalletEvent.ReceiptPicked("content://b"))
            assertNull((kid.state.value as WalletUiState.Ready).adjustment)
        }
        assertTrue(scanner.scanned.isEmpty())
    }

    @Test
    fun `receipt events keep the image and its contents out of toString`() {
        assertEquals("ReceiptPicked", WalletEvent.ReceiptPicked("content://media/secret").toString())
        assertFalse(AdjustmentPrefill(Money(1), "Dr Smith Clinic").toString().contains("Smith"))
    }
}

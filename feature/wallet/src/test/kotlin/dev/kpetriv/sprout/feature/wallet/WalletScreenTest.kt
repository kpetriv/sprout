package dev.kpetriv.sprout.feature.wallet

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Renders [WalletScreen] per tier under Robolectric. A tall screen keeps every section composed. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2400dp")
class WalletScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<WalletEvent>()
    private val spoken = mutableListOf<String>()
    private var exports = 0

    private fun show(state: WalletUiState, readAloud: ReadAloud = ReadAloud { spoken += it }) {
        compose.setContent {
            SproutTheme {
                WalletScreen(
                    state = state,
                    onEvent = { events += it },
                    readAloud = readAloud,
                    onExport = { exports++ },
                )
            }
        }
    }

    @Test
    fun `seedling shows the jar, the sun and big buttons but no history`() {
        show(WalletPreviewData.ready(KidUiTier.SEEDLING, canAdjust = false))

        compose.onNodeWithText("Hi Mia!").assertIsDisplayed()
        compose.onNodeWithContentDescription("Your coin jar has 7 coins")
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(
                        0.7f,
                        0f..1f,
                    ),
                ),
            )
        compose.onNodeWithContentDescription("1 h 20 min of screen time").assertIsDisplayed()
        compose.onNodeWithText("Chores").assertIsDisplayed()
        compose.onNodeWithText("Shop").assertIsDisplayed()
        compose.onNodeWithText("Recent").assertDoesNotExist()
        compose.onNodeWithText("History").assertDoesNotExist()
        compose.onNodeWithText("£7.45").assertDoesNotExist()
        compose.onNodeWithText("Recent activity").assertDoesNotExist()
    }

    @Test
    fun `a parent sees a seedling kid's recent activity in real currency`() {
        show(WalletPreviewData.ready(KidUiTier.SEEDLING))

        compose.onNodeWithText("Recent activity").assertIsDisplayed()
        compose.onNodeWithContentDescription("Chore reward, +£1.50").assertIsDisplayed()
        compose.onNodeWithContentDescription("Shop, -£3.99").assertIsDisplayed()
    }

    @Test
    fun `seedling hides read aloud without a speech implementation`() {
        show(WalletPreviewData.ready(KidUiTier.SEEDLING), readAloud = ReadAloud.None)
        compose.onNodeWithText("Read to me").assertDoesNotExist()
    }

    @Test
    fun `seedling buttons meet the 72dp touch target`() {
        show(WalletPreviewData.ready(KidUiTier.SEEDLING))

        val bounds = compose.onNodeWithText("Read to me").fetchSemanticsNode().let { node ->
            generateSequence(node) { it.parent }.first { SemanticsProperties.Role in it.config }.size
        }
        val density = compose.density
        assertTrue(with(density) { bounds.height.toDp().value } >= 72f, "height was ${bounds.height}px")
    }

    @Test
    fun `seedling read aloud speaks the balance`() {
        show(WalletPreviewData.ready(KidUiTier.SEEDLING))

        compose.onNodeWithText("Read to me").performClick()

        assertEquals(listOf("You have 7 coins and 1 h 20 min of screen time."), spoken)
    }

    @Test
    fun `sprout shows currency, savings goal and recent history`() {
        show(WalletPreviewData.ready(KidUiTier.SPROUT))

        compose.onNodeWithText("£7.45").assertIsDisplayed()
        compose.onNodeWithText("Savings").assertIsDisplayed()
        compose.onNodeWithText("£12.50").assertIsDisplayed()
        compose.onNodeWithText("Recent").assertIsDisplayed()
        compose.onNodeWithContentDescription("Chore reward, +£1.50").assertIsDisplayed()
        compose.onNodeWithContentDescription("Shop, -£3.99").assertIsDisplayed()
        compose.onNodeWithText("Export CSV").assertDoesNotExist()
        compose.onNodeWithText("Hi Leo!").assertDoesNotExist()
    }

    @Test
    fun `sapling shows accounts, interest, filters and export`() {
        show(WalletPreviewData.ready(KidUiTier.SAPLING))

        compose.onNodeWithText("Accounts").assertIsDisplayed()
        compose.onNodeWithText("You've earned £0.12 interest so far.").assertIsDisplayed()
        compose.onNodeWithText("Budget and spending charts are coming soon.").assertIsDisplayed()
        compose.onNodeWithText("All").assertIsSelected()

        compose.onNode(hasText("Shop") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick()
        compose.onNodeWithText("Export CSV").performClick()

        assertEquals(listOf<WalletEvent>(WalletEvent.FilterChanged(EventCategory.SHOP)), events)
        assertEquals(1, exports)
    }

    @Test
    fun `parent sees the adjust action and anomalies, kid mode does not`() {
        show(WalletPreviewData.ready(KidUiTier.SPROUT, anomaliesCount = 1))
        compose.onNodeWithText("1 ledger issue needs a look").assertIsDisplayed()
        compose.onNodeWithText("Adjust balance").performClick()
        assertEquals(listOf<WalletEvent>(WalletEvent.OpenAdjust), events)
    }

    @Test
    fun `kid mode hides parent controls`() {
        show(WalletPreviewData.ready(KidUiTier.SPROUT, canAdjust = false, anomaliesCount = 1))
        compose.onNodeWithText("Adjust balance").assertDoesNotExist()
        compose.onNodeWithText("1 ledger issue needs a look").assertDoesNotExist()
    }

    @Test
    fun `adjustment sheet submits minor units, sign and reason`() {
        show(WalletPreviewData.ready(KidUiTier.SPROUT, adjustment = WalletPreviewData.adjustment))

        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Take away").performClick()
        compose.onNode(hasSetTextAction() and hasText("Amount")).performTextInput("2.5")
        compose.onNode(hasSetTextAction() and hasText("Reason")).performTextInput("Lost library book")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()

        assertEquals(
            listOf<WalletEvent>(WalletEvent.SubmitAdjust(250, AdjustmentSign.DEBIT, "Lost library book")),
            events,
        )
    }

    @Test
    fun `adjustment sheet rejects too many decimals and letters`() {
        show(WalletPreviewData.ready(KidUiTier.SPROUT, adjustment = WalletPreviewData.adjustment))

        val amount = compose.onNode(hasSetTextAction() and hasText("Amount"))
        amount.performTextInput("1.234")
        compose.onNodeWithText("Use at most 2 decimal places").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Reason")).performTextInput("Pocket money")
        compose.onNodeWithText("Save").assertIsNotEnabled()

        amount.performTextInput("abc")
        compose.onNode(hasSetTextAction() and hasText("1.234")).assertIsDisplayed()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `adjustment sheet shows the ledger's rejection`() {
        show(
            WalletPreviewData.ready(
                KidUiTier.SPROUT,
                adjustment = WalletPreviewData.adjustment.copy(error = AdjustmentError.InsufficientFunds("£7.45")),
            ),
        )
        compose.onNodeWithText("Only £7.45 is available to take away").assertIsDisplayed()
    }

    @Test
    fun `adjustment sheet explains a conflict`() {
        show(
            WalletPreviewData.ready(
                KidUiTier.SPROUT,
                adjustment = WalletPreviewData.adjustment.copy(error = AdjustmentError.Conflict),
            ),
        )
        compose.onNodeWithText(
            "This change clashed with another device; ask a parent to check the history",
        ).assertIsDisplayed()
    }

    @Test
    fun `a failure after dismissing the sheet shows a snackbar`() {
        show(
            WalletPreviewData.ready(KidUiTier.SPROUT).copy(
                message = WalletMessage.AdjustmentFailed(AdjustmentError.Failed),
            ),
        )
        compose.onNodeWithText("Couldn't save the adjustment. Try again.").assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 10_000) { WalletEvent.MessageShown in events }
    }

    @Test
    fun `error state retries`() {
        show(WalletUiState.Error(WalletError.LOAD_FAILED))
        compose.onNodeWithText("Something went wrong while loading the wallet.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf<WalletEvent>(WalletEvent.Refresh), events)
    }

    @Test
    fun `loading state is announced`() {
        show(WalletUiState.Loading)
        compose.onNodeWithContentDescription("Loading wallet").assertIsDisplayed()
    }
}

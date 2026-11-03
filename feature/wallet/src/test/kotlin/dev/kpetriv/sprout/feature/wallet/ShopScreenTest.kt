package dev.kpetriv.sprout.feature.wallet

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.RewardId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Renders [ShopScreen] per tier under Robolectric. A tall screen keeps every reward composed. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2400dp")
class ShopScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<ShopEvent>()

    private fun show(state: ShopUiState) {
        compose.setContent { SproutTheme { ShopScreen(state = state, onEvent = { events += it }) } }
    }

    @Test
    fun `seedling tiles say whether the kid can buy and open the dialog`() {
        show(ShopPreviewData.ready(KidUiTier.SEEDLING, affordable = false))

        compose.onNodeWithText("You have 1 coin").assertIsDisplayed()
        compose.onNodeWithContentDescription("Sticker pack, 2 coins, Keep saving").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ice cream, 5 coins, Keep saving").performClick()

        assertEquals(listOf<ShopEvent>(ShopEvent.Select(RewardId("reward-ice-cream"))), events)
    }

    @Test
    fun `seedling confirm dialog has big buttons`() {
        val state = ShopPreviewData.ready(KidUiTier.SEEDLING, affordable = true)
        show(state.copy(selected = state.items.first { it.rewardId.value == "reward-ice-cream" }))

        compose.onNodeWithText("Buy Ice cream?").assertIsDisplayed()
        compose.onNodeWithText("It costs 5 coins.").assertIsDisplayed()
        val yes = compose.onNodeWithText("Yes, buy it!")
        val height = yes.fetchSemanticsNode().let { node ->
            generateSequence(node) { it.parent }.first { SemanticsProperties.Role in it.config }.size.height
        }
        assertTrue(with(compose.density) { height.toDp().value } >= 72f, "height was ${height}px")
        yes.performClick()
        compose.onNodeWithText("Not now").performClick()

        assertEquals(listOf(ShopEvent.ConfirmPurchase, ShopEvent.DismissConfirm), events)
    }

    @Test
    fun `a reward the kid cannot afford gets a not-yet dialog`() {
        val state = ShopPreviewData.ready(KidUiTier.SPROUT, affordable = false)
        show(state.copy(selected = state.items.first { it.rewardId.value == "reward-ice-cream" }))

        compose.onNodeWithText("Not yet!").assertIsDisplayed()
        compose.onNodeWithText("Yes, buy it!").assertDoesNotExist()
        compose.onNodeWithText("OK").performClick()
        assertEquals(listOf<ShopEvent>(ShopEvent.DismissConfirm), events)
    }

    @Test
    fun `sprout lists prices and days of pocket money`() {
        show(ShopPreviewData.ready(KidUiTier.SPROUT, affordable = false))

        compose.onNodeWithText("You have £1.20").assertIsDisplayed()
        compose.onNodeWithText("£4.50").assertIsDisplayed()
        compose.onNodeWithText("£3.30 more, about 14 days of pocket money").assertIsDisplayed()
        compose.onNodeWithText("Gives 30 min of screen time").assertIsDisplayed()
        compose.onAllNodesWithText("Buy").onFirst().assertIsNotEnabled()
    }

    @Test
    fun `sprout buy opens the confirm dialog`() {
        show(ShopPreviewData.ready(KidUiTier.SPROUT, affordable = true))

        compose.onAllNodesWithText("You can buy this now").onFirst().assertIsDisplayed()
        compose.onAllNodesWithText("Buy").onFirst().assertIsEnabled().performClick()
        assertEquals(listOf<ShopEvent>(ShopEvent.Select(RewardId("reward-sticker"))), events)
    }

    @Test
    fun `sapling shows the wishlist and savings plans and toggles hearts`() {
        show(ShopPreviewData.ready(KidUiTier.SAPLING, affordable = false))

        compose.onNodeWithText("Wishlist").assertIsDisplayed()
        compose.onNodeWithText("All rewards").assertIsDisplayed()
        compose.onAllNodesWithText("Savings plan: about 2 weeks of pocket money").onFirst().assertIsDisplayed()
        compose.onAllNodesWithText("Savings plan: about 6 weeks of pocket money").onFirst().assertIsDisplayed()
        compose.onNodeWithContentDescription("Add Ice cream to wishlist").performClick()

        assertEquals(listOf<ShopEvent>(ShopEvent.ToggleWishlist(RewardId("reward-ice-cream"))), events)
    }

    @Test
    fun `sapling with an empty wishlist explains the heart`() {
        show(ShopPreviewData.ready(KidUiTier.SAPLING, affordable = true, wishlist = emptySet()))
        compose.onNodeWithText("Tap the heart on a reward to save it here.").assertIsDisplayed()
    }

    @Test
    fun `a parent browsing sees no buy buttons`() {
        show(ShopPreviewData.ready(KidUiTier.SPROUT, affordable = true, canPurchase = false))

        compose.onNodeWithText("You're looking at the shop. Kids buy rewards in kid mode.").assertIsDisplayed()
        compose.onNodeWithText("Buy").assertDoesNotExist()
    }

    @Test
    fun `success says enjoy with the screen time added`() {
        show(
            ShopPreviewData.ready(KidUiTier.SEEDLING, affordable = true)
                .copy(message = ShopMessage.Purchased("Tablet time", minutesText = "30 min")),
        )

        compose.onNodeWithText("Enjoy!").assertIsDisplayed()
        compose.onNodeWithText("You bought Tablet time.\n30 min of screen time added.").assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        assertEquals(listOf<ShopEvent>(ShopEvent.MessageShown), events)
    }

    @Test
    fun `failures are friendly`() {
        show(
            ShopPreviewData.ready(KidUiTier.SPROUT, affordable = false)
                .copy(message = ShopMessage.Failed(PurchaseFailure.NotEnoughMoney("£3.30"))),
        )
        compose.onNodeWithText("Oops!").assertIsDisplayed()
        compose.onNodeWithText("You need £3.30 more").assertIsDisplayed()
    }

    @Test
    fun `a changed catalogue asks for another look`() {
        show(
            ShopPreviewData.ready(KidUiTier.SPROUT, affordable = true)
                .copy(message = ShopMessage.Failed(PurchaseFailure.CatalogueChanged)),
        )
        compose.onNodeWithText("This reward just changed. Have another look.").assertIsDisplayed()
    }

    @Test
    fun `error retries and loading is announced`() {
        show(ShopUiState.Error(ShopError.LOAD_FAILED))
        compose.onNodeWithText("Something went wrong while loading the shop.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf<ShopEvent>(ShopEvent.Refresh), events)
    }
}

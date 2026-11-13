package dev.kpetriv.sprout.feature.wallet

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind
import dev.kpetriv.sprout.core.testing.accessibility.AccessibilityRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Renders the rewards list and editor, and the parent shop preview's "Edit rewards", under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rGB-w411dp-h2400dp")
class RewardScreensTest {
    @get:Rule(order = 0)
    val compose = createComposeRule()

    @get:Rule(order = 1)
    val a11y = AccessibilityRule(compose)

    private val listEvents = mutableListOf<RewardListEvent>()
    private val editorEvents = mutableListOf<RewardEditorEvent>()
    private val actions = mutableListOf<String>()

    private fun showList(state: RewardListUiState, selected: RewardId? = null) {
        compose.setContent {
            SproutTheme {
                RewardListScreen(
                    state = state,
                    onEvent = { listEvents += it },
                    onAddReward = { actions += "add" },
                    onEditReward = { actions += "edit:${it.value}" },
                    onSeeShop = { actions += "shop" },
                    onBack = { actions += "back" },
                    selectedRewardId = selected,
                )
            }
        }
    }

    private fun showEditor(state: RewardEditorUiState) {
        compose.setContent { SproutTheme { RewardEditorScreen(state = state, onEvent = { editorEvents += it }) } }
    }

    @Test
    fun `the list shows each reward's price, minutes, kids and hidden state, and opens the editor`() {
        showList(RewardsPreviewData.list())

        compose.onNodeWithText("Rewards").assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "30 minutes of screen time, 30 min of screen time, £1.50, All kids, In the shop",
        ).assertIsDisplayed()
        compose.onNodeWithContentDescription("Trip to the zoo, Outing, £15.00, For Mia, Zoe, In the shop")
            .assertIsDisplayed()
        compose.onNodeWithText("Screen time · 30 min", useUnmergedTree = true).assertExists()
        val inShop = compose.onAllNodesWithText("In the shop", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(3, inShop.size, "every row has a state pill")
        compose.onNodeWithText("Hidden", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Sticker pack, Something else, £1.00, All kids, Hidden")
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        compose.onNodeWithText("Add reward").performClick()
        compose.onNodeWithText("See the shop as a kid").performClick()

        assertEquals(listOf("edit:reward-stickers", "add", "shop"), actions)
    }

    @Test
    fun `the reward open beside the list is marked selected`() {
        showList(RewardsPreviewData.list(), selected = RewardId("reward-ice-cream"))

        compose.onNodeWithContentDescription("Ice cream, Treat, £2.50, All kids, In the shop").assertIsSelected()
        compose.onNodeWithContentDescription("Sticker pack, Something else, £1.00, All kids, Hidden")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
    }

    @Test
    fun `an empty list invites the parent to add the first reward`() {
        showList(RewardListUiState.Ready(emptyList()))

        compose.onNode(isHeading().and(hasText("No rewards yet"))).assertIsDisplayed()
        compose.onNodeWithText("Add reward").performClick()
        compose.onNodeWithText("See the shop as a kid").performClick()
        assertEquals(listOf("add", "shop"), actions)
    }

    @Test
    fun `loading shows a progress indicator`() {
        showList(RewardListUiState.Loading)
        compose.onNodeWithContentDescription("Loading rewards").assertIsDisplayed()
    }

    @Test
    fun `an error offers to try again`() {
        showList(RewardListUiState.Error)
        compose.onNodeWithText("Something went wrong while loading the rewards.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf<RewardListEvent>(RewardListEvent.Retry), listEvents)
    }

    @Test
    fun `a new reward's form sends its edits and save`() {
        showEditor(RewardsPreviewData.editorNew())

        compose.onNodeWithText("New reward").assertIsDisplayed()
        compose.onNodeWithText("Minutes of screen time").assertDoesNotExist()
        compose.onNodeWithText("Name").performTextInput("Ice cream")
        compose.onNodeWithText("Price (GBP)").performTextInput("2.50")
        compose.onNodeWithText("Outing")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .performClick()
        compose.onNodeWithText("Chosen kids").performClick()
        compose.onNodeWithText("Show in the shop").assertIsOn().performClick()
        compose.onNodeWithText("Save").performClick()

        assertTrue(RewardEditorEvent.TitleChanged("Ice cream") in editorEvents)
        assertTrue(RewardEditorEvent.PriceChanged("2.50") in editorEvents)
        assertTrue(RewardEditorEvent.KindChanged(RewardKind.OUTING) in editorEvents)
        assertTrue(RewardEditorEvent.AllKidsChanged(false) in editorEvents)
        assertTrue(RewardEditorEvent.ActiveChanged(false) in editorEvents)
        assertEquals(RewardEditorEvent.Save, editorEvents.last())
    }

    @Test
    fun `a screen-time reward shows its minutes and the chosen kids`() {
        showEditor(RewardsPreviewData.editorScreenTime())

        compose.onNodeWithText("Edit reward").assertIsDisplayed()
        compose.onNodeWithText("30").assertIsDisplayed()
        compose.onNodeWithText("Added to the kid's screen time when they buy it.").assertIsDisplayed()
        compose.onNodeWithText("Mia").assertIsSelected()
        compose.onNodeWithText("Zoe").assertIsNotSelected().performClick()
        assertEquals(RewardEditorEvent.KidToggled(RewardsPreviewData.zoe.id), editorEvents.last())
    }

    @Test
    fun `validation errors show next to their fields and are announced`() {
        showEditor(RewardsPreviewData.editorErrors())

        listOf(
            "Give the reward a name.",
            "Enter a price above zero.",
            "Enter how many minutes it gives.",
            "Choose at least one kid, or pick All kids.",
        ).forEach { message ->
            compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Error, message), useUnmergedTree = true)
                .assertExists(message)
        }
    }

    @Test
    fun `the price example follows the reward's currency`() {
        showEditor(
            RewardsPreviewData.editorNew().copy(
                currency = Currency.Fiat("JPY"),
                priceText = "two",
                errors = setOf(RewardEditorError.PRICE_NOT_A_NUMBER),
            ),
        )
        compose.onNodeWithText("Price (JPY)").assertIsDisplayed()
        compose.onNodeWithText("Enter a price like 250.", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `save is disabled while saving`() {
        showEditor(RewardsPreviewData.editorNew().copy(isSaving = true))
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun `the parent's shop preview offers Edit rewards`() {
        var opened = 0
        compose.setContent {
            SproutTheme {
                ShopScreen(
                    state = ShopPreviewData.ready(KidUiTier.SPROUT, affordable = true, canPurchase = false),
                    onEvent = {},
                    onBack = {},
                    onEditRewards = { opened++ },
                )
            }
        }

        compose.onNodeWithText("You're looking at the shop. Kids buy rewards in kid mode.").assertIsDisplayed()
        compose.onNodeWithText("Edit rewards").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `a kid's shop never offers Edit rewards`() {
        compose.setContent {
            SproutTheme {
                ShopScreen(
                    state = ShopPreviewData.ready(KidUiTier.SPROUT, affordable = true, canPurchase = true),
                    onEvent = {},
                    onEditRewards = { error("a kid cannot edit rewards") },
                )
            }
        }

        compose.onNodeWithText("Edit rewards").assertDoesNotExist()
    }
}

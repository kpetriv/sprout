package dev.kpetriv.sprout.feature.chores

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.ChoreId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On a tablet the chore list is capped at 640 dp and centred, but the side gutters belong to the list: a drag that
 * starts in a gutter scrolls it (M7 review L10). The chore list stands for every `ContentWidth` screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rGB-w1280dp-h480dp-xlarge-land-mdpi")
class ContentWidthGutterTest {
    @get:Rule
    val compose = createComposeRule()

    private fun showLongList() {
        val base = ChoresPreviewData.choreList()
        val group = base.groups.first()
        val template = group.chores.first()
        val chores = (1..30).map { template.copy(id = ChoreId("chore-$it"), title = "Chore $it") }
        compose.setContent {
            SproutTheme {
                ChoreListScreen(state = base.copy(groups = listOf(group.copy(chores = chores))), onEvent = {})
            }
        }
    }

    @Test
    fun `the list stays a centred 640 dp column`() {
        showLongList()

        // The chore's card: the title's text node sits in the card's clickable column, which sits in the card.
        val card = compose.onNodeWithText("Chore 1", useUnmergedTree = true).onParent().onParent()
        card.assert(SemanticsMatcher.expectValue(SemanticsProperties.IsTraversalGroup, true))
        val bounds = card.getBoundsInRoot()
        // 1280 dp window: 320 dp gutters, then the list's own gutter on each side (M9 §2.4).
        val content = SproutSpacing.contentMaxWidth.value
        val left = (WINDOW_WIDTH - content) / 2 + SproutSpacing.gutter.value
        val right = (WINDOW_WIDTH + content) / 2 - SproutSpacing.gutter.value
        assertEquals(left, bounds.left.value, 1f, "card starts at ${bounds.left}")
        assertEquals(right, bounds.right.value, 1f, "card ends at ${bounds.right}")
    }

    @Test
    fun `a drag in the side gutter scrolls the list`() {
        showLongList()
        compose.onNodeWithText("Chore 1").assertIsDisplayed()
        val before = compose.onNodeWithText("Chore 1").getBoundsInRoot().top

        compose.onRoot().performTouchInput {
            // 100 dp from the left edge: well inside the 320 dp gutter, outside every row.
            val x = 100.dp.toPx()
            swipe(start = Offset(x, centerY + 120.dp.toPx()), end = Offset(x, centerY - 120.dp.toPx()))
        }
        compose.waitForIdle()

        // Scrolled up by most of the drag, or out of the list altogether.
        val after = compose.onAllNodesWithText("Chore 1").fetchSemanticsNodes().firstOrNull()
        if (after != null) {
            val top = with(compose.density) { after.boundsInRoot.top.toDp() }
            assertTrue(top < before - 100.dp, "Chore 1 moved from $before to $top")
        }
    }

    private companion object {
        /** The window width of this test's qualifiers. */
        const val WINDOW_WIDTH = 1_280f
    }
}

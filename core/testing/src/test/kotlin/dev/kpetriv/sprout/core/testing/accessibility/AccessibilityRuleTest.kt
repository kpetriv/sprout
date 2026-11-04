package dev.kpetriv.sprout.core.testing.accessibility

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.theme.ProvideKidTier
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.designsystem.theme.TierTokens
import dev.kpetriv.sprout.core.model.KidUiTier
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AccessibilityRuleTest {
    @get:Rule
    val compose = createComposeRule()

    private fun issues(
        tier: KidUiTier? = null,
        minTouchTarget: Dp = tier?.let { TierTokens.forTier(it).minTouchTarget } ?: TierTokens.Sapling.minTouchTarget,
        content: @Composable () -> Unit,
    ): String {
        compose.setContent {
            SproutTheme {
                if (tier == null) Column { content() } else ProvideKidTier(tier) { Column { content() } }
            }
        }
        return runCatching { compose.assertAccessible(minTouchTarget) }.exceptionOrNull()?.message.orEmpty()
    }

    @Test
    fun `labelled, distinct, large enough buttons pass`() {
        assertEquals(
            "",
            issues {
                Button(onClick = {}) { Text("Approve") }
                Button(onClick = {}) { Text("Reject") }
                Box(Modifier.size(48.dp).clickable {}.semantics { contentDescription = "Close" })
            },
        )
    }

    @Test
    fun `an unlabelled clickable fails`() {
        val message = issues { Box(Modifier.size(48.dp).clickable {}) }
        assertTrue("without text or content description" in message, message)
    }

    @Test
    fun `identical sibling labels fail, grouped ones pass`() {
        val message = issues {
            Button(onClick = {}) { Text("Approve") }
            Button(onClick = {}) { Text("Approve") }
        }
        assertTrue("share the label \"approve\"" in message, message)
    }

    @Test
    fun `cards grouped for traversal are not siblings`() {
        assertEquals(
            "",
            issues {
                Column(Modifier.semantics { isTraversalGroup = true }) { Button(onClick = {}) { Text("Approve") } }
                Column(Modifier.semantics { isTraversalGroup = true }) { Button(onClick = {}) { Text("Approve") } }
            },
        )
    }

    @Test
    fun `a target below the minimum fails`() {
        // Parent theme (48 dp touch targets) checked against Seedling's 72 dp.
        val message = issues(minTouchTarget = TierTokens.Seedling.minTouchTarget) {
            Box(Modifier.size(56.dp).clickable {}.semantics { contentDescription = "Too small" })
        }
        assertTrue("below 72.0.dp" in message, message)
        assertTrue("Too small" in message, message)
    }

    @Test
    fun `a kid tier extends every touch target to its minimum`() {
        assertEquals(
            "",
            issues(KidUiTier.SEEDLING) {
                Button(onClick = {}) { Text("Material") }
                Box(Modifier.size(56.dp).clickable {}.semantics { contentDescription = "Custom" })
            },
        )
    }

    @Test
    fun `clearAndSetSemantics hiding a button fails`() {
        val message = issues {
            Box(Modifier.clearAndSetSemantics { contentDescription = "Card" }) {
                Button(onClick = {}) { Text("Hidden") }
            }
        }
        assertTrue("hides an actionable node" in message, message)
    }

    @Test
    fun `the rule fails a test that leaves an issue on screen`() {
        val rule = AccessibilityRule(compose)
        compose.setContent { Box(Modifier.size(48.dp).clickable {}) }
        assertFailsWith<AssertionError> { rule.check() }
    }
}

package dev.kpetriv.sprout.core.testing.accessibility

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.unit.Dp
import dev.kpetriv.sprout.core.designsystem.theme.TierTokens
import dev.kpetriv.sprout.core.model.KidUiTier
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.lang.reflect.Method

/**
 * The accessibility rule of M7 §8 (D-M7-5), run against everything a test left on screen when it ends.
 *
 * It walks the semantics tree of every Compose root (the screen and any dialog or sheet window) and fails the test
 * when:
 * 1. an actionable node (clickable, long-clickable, toggleable, editable or adjustable) has no text and no content
 *    description, nor a labelled child, so TalkBack would say only "button";
 * 2. two enabled actionable siblings have the same label ("Approve", "Approve"), so a TalkBack user cannot tell them
 *    apart;
 * 3. an actionable node is smaller than [minTouchTarget] in either direction (the tier minimum: 72 dp Seedling, 56 dp
 *    Sprout, 48 dp Sapling and parent screens), measured on its unclipped size extended to the view configuration's
 *    minimum touch target, as Compose's hit testing does;
 * 4. a `clearAndSetSemantics` hides an actionable node from accessibility services.
 *
 * Use it next to the test's Compose rule, inside it, so the check runs before the Compose rule tears the activity
 * down:
 * ```
 * @get:Rule(order = 0) val compose = createComposeRule()
 * @get:Rule(order = 1) val a11y = AccessibilityRule(compose)
 * ```
 * A kid screen test sets [minTouchTarget] for its tier ([forTier]); a test can also call [check] at any point (for
 * example with a sheet open). Tests that set no content are skipped. A failing test body is reported as is, without
 * the accessibility check on top.
 */
class AccessibilityRule(
    private val compose: ComposeTestRule,
    var minTouchTarget: Dp = TierTokens.Sapling.minTouchTarget,
) : TestRule {
    /** Set to false in a test that deliberately leaves an inaccessible state on screen (say why in the test). */
    var enabled: Boolean = true

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            base.evaluate()
            if (enabled) check()
        }
    }

    /** Uses [tier]'s minimum touch target from now on. */
    fun forTier(tier: KidUiTier) {
        minTouchTarget = TierTokens.forTier(tier).minTouchTarget
    }

    /** Checks every root on screen now; does nothing when nothing is on screen. */
    fun check(minTouchTarget: Dp = this.minTouchTarget) {
        compose.assertAccessible(minTouchTarget)
    }
}

/** Checks every Compose root on screen (the screen plus any dialog or sheet) against the [AccessibilityRule] rules. */
fun ComposeTestRule.assertAccessible(minTouchTarget: Dp = TierTokens.Sapling.minTouchTarget) {
    waitForIdle()
    val merged = onAllNodes(isRoot()).fetchSemanticsNodes(atLeastOneRootRequired = false)
    val unmerged = onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)
    val issues = merged.flatMap { root -> mergedTreeIssues(root, minTouchTarget) } +
        unmerged.flatMap { root -> hiddenNodeIssues(root) }
    if (issues.isNotEmpty()) throw AssertionError(formatIssues(issues))
}

/** Checks the subtree of this node (usually `onRoot()`) against the [AccessibilityRule] rules. */
fun SemanticsNodeInteraction.assertAccessible(minTouchTarget: Dp = TierTokens.Sapling.minTouchTarget) {
    val issues = accessibilityIssues(fetchSemanticsNode(), minTouchTarget)
    if (issues.isNotEmpty()) throw AssertionError(formatIssues(issues))
}

/** Checks the subtree of [root] against the [AccessibilityRule] rules. */
fun assertAccessible(root: SemanticsNode, minTouchTarget: Dp = TierTokens.Sapling.minTouchTarget) {
    val issues = accessibilityIssues(root, minTouchTarget)
    if (issues.isNotEmpty()) throw AssertionError(formatIssues(issues))
}

/**
 * Every rule violation in [root]'s subtree, one line each; empty when the subtree passes. Rules 1 to 3 read the tree
 * as TalkBack does, so pass a node from the merged tree (the default of `onRoot()`); rule 4 sees the most from the
 * unmerged tree, which [ComposeTestRule.assertAccessible] uses for it.
 */
fun accessibilityIssues(root: SemanticsNode, minTouchTarget: Dp): List<String> =
    mergedTreeIssues(root, minTouchTarget) + hiddenNodeIssues(root)

/** Rules 1 to 3 on [root]'s subtree. */
fun mergedTreeIssues(root: SemanticsNode, minTouchTarget: Dp): List<String> {
    val issues = mutableListOf<String>()
    val minPx = with(root.layoutInfo.density) { minTouchTarget.toPx() } - ROUNDING_PX
    checkMerged(root, minPx, minTouchTarget, issues)
    return issues
}

/** Rule 4 on [root]'s subtree. */
fun hiddenNodeIssues(root: SemanticsNode): List<String> {
    val issues = mutableListOf<String>()
    checkHidden(root, hiddenByAncestor = false, issues)
    return issues
}

private fun checkMerged(node: SemanticsNode, minPx: Float, minTouchTarget: Dp, issues: MutableList<String>) {
    val config = node.config
    if (config.isActionable() && !config.isHiddenFromAccessibility()) {
        // A wrapper whose labelled children carry the meaning (Material's sheet drag handle sits in an unlabelled
        // long-click box) is read through those children.
        val label = config.label() ?: node.children.firstNotNullOfOrNull { it.config.label() }
        if (label == null) issues += "Actionable node without text or content description: ${node.describe()}"
        if (config.isTouchable()) touchTargetIssue(node, minPx, minTouchTarget)?.let { issues += it }
    }
    duplicateLabelIssues(node.children, issues)
    node.children.forEach { checkMerged(it, minPx, minTouchTarget, issues) }
}

/**
 * Compose's hit testing extends a pointer target smaller than the view configuration's minimum touch target to that
 * size (ProvideKidTier raises it to the tier minimum). Measured from the unclipped size rather than
 * touchBoundsInRoot, which is clipped to zero for a node scrolled out of view.
 */
private fun touchTargetIssue(node: SemanticsNode, minPx: Float, minTouchTarget: Dp): String? {
    val layout = node.layoutInfo
    val extended = with(layout.density) { layout.viewConfiguration.minimumTouchTargetSize.toSize() }
    val width = maxOf(node.size.width.toFloat(), extended.width)
    val height = maxOf(node.size.height.toFloat(), extended.height)
    val placed = node.size.width > 0 && node.size.height > 0
    if (!placed || (width >= minPx && height >= minPx)) return null
    val density = layout.density.density
    return "Touch target ${(width / density).toInt()}x${(height / density).toInt()} dp is below " +
        "$minTouchTarget: ${node.describe()}"
}

/** Disabled controls cannot be activated, so two of them with one label cannot be confused. */
private fun duplicateLabelIssues(siblings: List<SemanticsNode>, issues: MutableList<String>) {
    siblings
        .filter { it.config.isActionable() && !it.config.isHiddenFromAccessibility() }
        .filterNot { it.config.contains(SemanticsProperties.Disabled) }
        .mapNotNull { child -> child.config.label()?.let { it.lowercase() to child } }
        .groupBy({ it.first }, { it.second })
        .filterValues { it.size > 1 }
        .forEach { (label, nodes) ->
            issues += "${nodes.size} actionable siblings share the label \"$label\": ${nodes.first().describe()}"
        }
}

private fun checkHidden(node: SemanticsNode, hiddenByAncestor: Boolean, issues: MutableList<String>) {
    if (hiddenByAncestor && node.config.isActionable()) {
        issues += "clearAndSetSemantics hides an actionable node: ${node.describe()}"
    }
    val hidesChildren = hiddenByAncestor || node.config.isClearingSemantics
    node.allChildren().forEach { checkHidden(it, hidesChildren, issues) }
}

private fun SemanticsConfiguration.isActionable(): Boolean = contains(SemanticsActions.OnClick) ||
    contains(SemanticsActions.OnLongClick) ||
    contains(SemanticsProperties.ToggleableState) ||
    contains(SemanticsActions.SetText) ||
    contains(SemanticsActions.SetProgress)

/** Text fields are measured by the text field's own minimum height, not the tier's touch target. */
private fun SemanticsConfiguration.isTouchable(): Boolean = !contains(SemanticsActions.SetText)

private fun SemanticsConfiguration.isHiddenFromAccessibility(): Boolean =
    contains(SemanticsProperties.HideFromAccessibility)

/** What TalkBack would read as the node's name: its content description, else its text. Null when there is none. */
private fun SemanticsConfiguration.label(): String? = listOf(
    getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" "),
    getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text },
    getOrNull(SemanticsProperties.EditableText)?.text.orEmpty(),
).map { it.trim() }.firstOrNull { it.isNotEmpty() }

private fun SemanticsNode.describe(): String {
    val label = config.label() ?: "(no label)"
    val role = config.getOrNull(SemanticsProperties.Role)?.toString() ?: "no role"
    return "#$id \"$label\" ($role) at $boundsInRoot"
}

/**
 * Every child in the full tree, including those a `clearAndSetSemantics` replaced. Compose keeps that view internal
 * (`SemanticsNode.getChildren(includeReplacedSemantics = true, ...)`), so it is reached by reflection; this module is
 * test-only, and a Compose upgrade that renames it fails loudly here rather than silently skipping rule 4.
 */
private fun SemanticsNode.allChildren(): List<SemanticsNode> {
    @Suppress("UNCHECKED_CAST")
    return childrenWithReplaced.invoke(this, true, false, false) as List<SemanticsNode>
}

private val childrenWithReplaced: Method by lazy {
    SemanticsNode::class.java.methods.firstOrNull { method ->
        method.name.startsWith("getChildren\$") && method.parameterTypes.size == CHILDREN_ARGS
    } ?: error("SemanticsNode.getChildren(includeReplacedSemantics, ...) not found; update AccessibilityRule")
}

private fun formatIssues(issues: List<String>): String =
    "Accessibility check failed (${issues.size}):\n" + issues.joinToString("\n") { "- $it" }

private const val CHILDREN_ARGS = 3

/** Half a pixel of slack for dp-to-px rounding. */
private const val ROUNDING_PX = 0.5f

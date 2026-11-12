package dev.kpetriv.sprout.core.designsystem.theme

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * True when the user asked for less motion (Android's "Remove animations", which sets the animator duration scale
 * to 0). Components skip decorative motion under it: [pressScale] stays still, confetti does not fall. [SproutTheme]
 * provides the system setting; tests and previews may provide their own value.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * The system's reduced-motion setting, read once per composition of [SproutTheme] (keyed on the content resolver):
 * a change in system settings applies the next time the theme is composed, such as after the activity restarts.
 */
@Composable
internal fun rememberSystemReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** How far a pressed button or chip shrinks (M9 §2.5). */
const val PRESSED_SCALE: Float = 0.96f

/**
 * Shrinks the element to [PRESSED_SCALE] with a gentle spring while [interactionSource] reports a press, for buttons
 * and chips (cards do not move). Stays still under [LocalReducedMotion]. Pass the interaction source of the clickable
 * it decorates.
 */
fun Modifier.pressScale(interactionSource: InteractionSource): Modifier = this then PressScaleElement(interactionSource)

private data class PressScaleElement(val interactionSource: InteractionSource) :
    ModifierNodeElement<PressScaleNode>() {
    override fun create() = PressScaleNode(interactionSource)

    override fun update(node: PressScaleNode) = node.update(interactionSource)

    override fun InspectorInfo.inspectableProperties() {
        name = "pressScale"
        properties["interactionSource"] = interactionSource
    }
}

private class PressScaleNode(private var interactionSource: InteractionSource) :
    Modifier.Node(),
    LayoutModifierNode,
    CompositionLocalConsumerModifierNode {
    private var scale = Animatable(1f)
    private var collecting: Job? = null

    override fun onAttach() = collect()

    // A node recycled (lazy lists) or detached mid-press must not come back shrunk; the coroutine that would have
    // animated it back is cancelled with the node's scope.
    override fun onReset() {
        scale = Animatable(1f)
    }

    override fun onDetach() {
        collecting = null
        scale = Animatable(1f)
    }

    fun update(source: InteractionSource) {
        if (source == interactionSource) return
        interactionSource = source
        collecting?.cancel()
        if (isAttached) collect()
    }

    private fun collect() {
        collecting = coroutineScope.launch {
            val presses = mutableSetOf<PressInteraction.Press>()
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses += interaction
                    is PressInteraction.Release -> presses -= interaction.press
                    is PressInteraction.Cancel -> presses -= interaction.press
                }
                val target = if (presses.isNotEmpty() && !currentValueOf(LocalReducedMotion)) PRESSED_SCALE else 1f
                launch { scale.animateTo(target, PressSpring) }
            }
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale.value
                scaleY = scale.value
            }
        }
    }
}

private val PressSpring = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium)

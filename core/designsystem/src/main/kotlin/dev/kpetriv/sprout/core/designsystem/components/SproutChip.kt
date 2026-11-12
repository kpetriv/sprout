@file:Suppress("MatchingDeclarationName") // Named after its composable, like Material's files.

package dev.kpetriv.sprout.core.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.SproutPreviewFrame
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.designsystem.theme.pressScale
import dev.kpetriv.sprout.core.model.KidUiTier

/** Defaults for [SproutChip]. */
object SproutChipDefaults {
    /** Visible height; the touch target is extended to the tier's minimum. */
    val Height: Dp = 40.dp
    val IconSize: Dp = 18.dp
}

/**
 * A filter or choice chip (M9 §3): a Linen pill with a hairline; selected, a Leaf-tint pill with Botanical-deep text
 * and a check icon in place of [icon] (state is never colour alone). Announced as selected or not, with [role]:
 * [Role.Checkbox] for a filter, [Role.RadioButton] for one choice of several (put those in a `selectableGroup()`).
 */
@Composable
fun SproutChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    role: Role = Role.Checkbox,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = modifier
            .pressScale(interactionSource)
            .semantics { this.role = role },
        enabled = enabled,
        shape = CircleShape,
        color = if (selected) scheme.primaryContainer else scheme.surfaceContainerLow,
        contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
        border = BorderStroke(1.dp, if (selected) scheme.primaryContainer else scheme.outlineVariant),
        interactionSource = interactionSource,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = SproutChipDefaults.Height)
                .padding(horizontal = SproutSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
        ) {
            val shown = if (selected) SproutIcons.Check else icon
            if (shown != null) {
                Icon(shown, contentDescription = null, modifier = Modifier.size(SproutChipDefaults.IconSize))
            }
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A selected chip and two unselected ones, for previews and goldens. */
@Composable
internal fun SproutChipSample() {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(SproutSpacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
    ) {
        SproutChip(selected = true, label = "All", onClick = {})
        SproutChip(selected = false, label = "Money", onClick = {}, icon = SproutIcons.Payments)
        SproutChip(selected = false, label = "Time", onClick = {})
    }
}

@Preview(name = "Seedling", showBackground = true)
@Composable
private fun SproutChipSeedlingPreview() = SproutPreviewFrame(KidUiTier.SEEDLING) { SproutChipSample() }

@Preview(name = "Sprout", showBackground = true)
@Composable
private fun SproutChipSproutPreview() = SproutPreviewFrame(KidUiTier.SPROUT) { SproutChipSample() }

@Preview(name = "Sapling", showBackground = true)
@Composable
private fun SproutChipSaplingPreview() = SproutPreviewFrame(KidUiTier.SAPLING) { SproutChipSample() }

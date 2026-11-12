package dev.kpetriv.sprout.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.SproutPreviewFrame
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.model.KidUiTier

/**
 * A "label over figure" tile (M9 §3): Linen, the tier's medium corners, [label] in labelMedium Ink soft and [value]
 * in the display face. An optional small Botanical [icon] sits before the label. Read as one node, "label, value".
 */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Column(
        modifier = modifier
            .semantics(mergeDescendants = true) {}
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
            .padding(SproutSpacing.md),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = SproutSpacing.xs).size(16.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(text = value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Two tiles side by side, for previews and goldens. */
@Composable
internal fun StatTileSample() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(SproutSpacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
    ) {
        StatTile(label = "Balance", value = "£7.45", modifier = Modifier.weight(1f))
        StatTile(
            label = "Screen time",
            value = "1 h 20 min",
            icon = SproutIcons.Schedule,
            modifier = Modifier.weight(1f),
        )
    }
}

@Preview(name = "Seedling", showBackground = true)
@Composable
private fun StatTileSeedlingPreview() = SproutPreviewFrame(KidUiTier.SEEDLING) { StatTileSample() }

@Preview(name = "Sprout", showBackground = true)
@Composable
private fun StatTileSproutPreview() = SproutPreviewFrame(KidUiTier.SPROUT) { StatTileSample() }

@Preview(name = "Sapling", showBackground = true)
@Composable
private fun StatTileSaplingPreview() = SproutPreviewFrame(KidUiTier.SAPLING) { StatTileSample() }

@Preview(name = "Parent, dark", showBackground = true)
@Composable
private fun StatTileParentDarkPreview() = SproutPreviewFrame(tier = null, darkTheme = true) { StatTileSample() }

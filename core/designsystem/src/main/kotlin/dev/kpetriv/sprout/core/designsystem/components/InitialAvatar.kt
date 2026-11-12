@file:Suppress("MatchingDeclarationName") // Named after its composable, like Material's files.

package dev.kpetriv.sprout.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.theme.SproutPreviewFrame
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier

/** Defaults for [InitialAvatar]. */
object InitialAvatarDefaults {
    val Size: Dp = 48.dp
}

/**
 * A kid's avatar (M9 §3): [initial] in the display face on the per-kid tint at [tintIndex] (Leaf, Honey, Sky, Linen,
 * then round again; `SproutColors.avatarTint`). Keep the index stable per kid, such as their position in the
 * household. The initial is read out; pass `Modifier.clearAndSetSemantics {}` when the name is right beside it.
 * [size] is the minimum: the circle grows when the initial's line needs more room (large font scales).
 */
@Composable
fun InitialAvatar(
    initial: String,
    tintIndex: Int,
    modifier: Modifier = Modifier,
    size: Dp = InitialAvatarDefaults.Size,
) {
    val tint = SproutTheme.colors.avatarTint(tintIndex)
    val style = MaterialTheme.typography.titleLarge
    // Grows with the initial's line at large font scales so the letter never spills out of the circle.
    val line = with(LocalDensity.current) { style.lineHeight.toDp() }
    Box(
        modifier = modifier.size(maxOf(size, line + TextPadding * 2)).background(tint.container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = initial, style = style, color = tint.content, maxLines = 1)
    }
}

private val TextPadding = 4.dp

/** The four avatar tints, for previews and goldens. */
@Composable
internal fun InitialAvatarSample() {
    Row(
        modifier = Modifier.padding(SproutSpacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md),
    ) {
        listOf("M", "L", "A", "J").forEachIndexed { index, initial -> InitialAvatar(initial, tintIndex = index) }
    }
}

@Preview(name = "Seedling", showBackground = true)
@Composable
private fun InitialAvatarSeedlingPreview() = SproutPreviewFrame(KidUiTier.SEEDLING) { InitialAvatarSample() }

@Preview(name = "Sprout", showBackground = true)
@Composable
private fun InitialAvatarSproutPreview() = SproutPreviewFrame(KidUiTier.SPROUT) { InitialAvatarSample() }

@Preview(name = "Sapling", showBackground = true)
@Composable
private fun InitialAvatarSaplingPreview() = SproutPreviewFrame(KidUiTier.SAPLING) { InitialAvatarSample() }

@Preview(name = "Parent, dark", showBackground = true)
@Composable
private fun InitialAvatarParentDarkPreview() = SproutPreviewFrame(tier = null, darkTheme = true) {
    InitialAvatarSample()
}

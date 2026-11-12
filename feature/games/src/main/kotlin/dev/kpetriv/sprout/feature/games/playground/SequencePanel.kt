@file:Suppress("MatchingDeclarationName") // Named after its composables; the defaults object sits beside them.

package dev.kpetriv.sprout.feature.games.playground

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.PlaygroundTokens
import dev.kpetriv.sprout.core.games.MemorySymbols
import dev.kpetriv.sprout.core.games.Symbol
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.feature.games.R

/** Defaults for [SequencePanel]. */
internal object SequencePanelDefaults {
    /** The panel's corner (DESIGN-playground.md "rounded.panel"). */
    val PanelCorner: Dp = 24.dp

    /** The smallest a slot gets before the row wraps. */
    val MinSlotSize: Dp = 52.dp

    val SlotGap: Dp = 10.dp
    val PanelPadding: Dp = 16.dp

    /** Index badges (their minimum: they grow with the text) and tick badges. */
    val BadgeSize: Dp = 24.dp
    val BadgePadding: Dp = 6.dp

    val DashLength: Dp = 6.dp
    val DashStroke: Dp = 2.dp

    /**
     * The largest slot (DESIGN-playground.md "Sequence panel", 52 to 80 dp): Seedling 80, Sprout 64, Sapling 52. The
     * panel shrinks slots towards [MinSlotSize] to keep the sequence on one row.
     */
    @Composable
    @ReadOnlyComposable
    fun slotSize(): Dp = when (Playground.tokens) {
        PlaygroundTokens.Seedling -> 80.dp
        PlaygroundTokens.Sprout -> 64.dp
        else -> 52.dp
    }
}

/**
 * The memory sequence (M9-games §2.2, replacing `SequenceCard`): a Field-panel box with one slot per symbol of
 * [sequence], at most [slotSize] across and shrunk (down to 52 dp) to keep one row. The first `filled.size` slots
 * show [filled] (the symbols shown so far while [showing], the kid's taps after): a [ShapeGlyph] or, with [pictures]
 * (Seedling), a [PictureGlyph] on a Chalk tile; empty slots are dashed Chalk outlines. Every slot has its index
 * under it; a tapped slot gets a Lime tick.
 *
 * The live region is today's, unchanged: while the sequence shows the panel says "Showing the order" (one
 * announcement, not one per symbol); when it hides, "The order was red circle, blue square, …"; then "2 of 4 tapped"
 * after each tap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SequencePanel(
    sequence: List<Symbol>,
    filled: List<Symbol>,
    showing: Boolean,
    pictures: Boolean,
    modifier: Modifier = Modifier,
    slotSize: Dp = SequencePanelDefaults.slotSize(),
) {
    // The live region changes once while the sequence shows (one symbol every 700 ms would outrun speech, review
    // L8): when it hides, the whole sequence is announced by name; then each tap's progress.
    val description = when {
        showing -> stringResource(R.string.feature_games_memory_showing)

        filled.isEmpty() ->
            stringResource(R.string.feature_games_memory_sequence, sequence.joinToString(", ") { it.label })

        else -> {
            val tapped = filled.size
            pluralStringResource(R.plurals.feature_games_memory_tapped, tapped, tapped, sequence.size)
        }
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(Playground.colors.fieldPanel, RoundedCornerShape(SequencePanelDefaults.PanelCorner))
            .padding(SequencePanelDefaults.PanelPadding)
            .clearAndSetSemantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        // One row when it fits: slots shrink from slotSize down to MinSlotSize before the row wraps.
        val gap = SequencePanelDefaults.SlotGap
        val count = sequence.size.coerceAtLeast(1)
        // Less a pixel's slack, so rounding each slot and gap to whole pixels never pushes the last one to a new row.
        val fitted = (maxWidth - gap * (count - 1)) / count - RoundingSlack
        val size = fitted.coerceIn(minOf(SequencePanelDefaults.MinSlotSize, slotSize), slotSize)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            sequence.indices.forEach { index ->
                Slot(
                    symbol = filled.getOrNull(index),
                    index = index + 1,
                    ticked = !showing && index < filled.size,
                    pictures = pictures,
                    size = size,
                )
            }
        }
    }
}

@Composable
private fun Slot(symbol: Symbol?, index: Int, ticked: Boolean, pictures: Boolean, size: Dp) {
    val colors = Playground.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
            when {
                symbol == null -> EmptySlot(size)

                pictures -> Box(
                    modifier = Modifier
                        .size(size)
                        .background(colors.chalk, RoundedCornerShape(size / SLOT_CORNER_DIVISOR)),
                    contentAlignment = Alignment.Center,
                ) { PictureGlyph(symbol = symbol, size = size * PICTURE_SCALE, labelled = false) }

                else -> ShapeGlyph(symbol = symbol, size = size, labelled = false)
            }
            if (ticked) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = BadgeOverhang, y = -BadgeOverhang)
                        .size(SequencePanelDefaults.BadgeSize)
                        .background(colors.lime, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        SproutIcons.Check,
                        contentDescription = null,
                        tint = colors.ink,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        // The index in the tier's figure style (18 sp and up on Seedling); the badge grows with the text, so a large
        // font scale widens it into a pill instead of clipping the number.
        Box(
            modifier = Modifier
                .background(colors.fieldDeep, CircleShape)
                .defaultMinSize(minWidth = SequencePanelDefaults.BadgeSize, minHeight = SequencePanelDefaults.BadgeSize)
                .padding(horizontal = SequencePanelDefaults.BadgePadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$index",
                style = PlaygroundType.figure,
                color = if (symbol == null) colors.chalkSoft else colors.chalk,
            )
        }
    }
}

@Composable
private fun EmptySlot(size: Dp) {
    val colors = Playground.colors
    Box(
        modifier = Modifier
            .size(size)
            .drawBehind {
                val stroke = SequencePanelDefaults.DashStroke.toPx()
                val dash = SequencePanelDefaults.DashLength.toPx()
                val corner = CornerRadius(this.size.minDimension / SLOT_CORNER_DIVISOR)
                drawRoundRect(colors.fieldDeep, cornerRadius = corner)
                drawRoundRect(
                    color = colors.chalkSoft,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(this.size.width - stroke, this.size.height - stroke),
                    cornerRadius = corner,
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
                )
            },
    )
}

private const val SLOT_CORNER_DIVISOR = 4
private val RoundingSlack = 1.dp
private const val PICTURE_SCALE = 0.8f
private val BadgeOverhang = 6.dp
private val SampleSequence = listOf(2, 0, 5, 9)

/** Sprout's four-symbol sequence two in while showing, and Seedling pictures being tapped back, for goldens. */
@Composable
internal fun SequencePanelSample(pictures: Boolean) {
    val symbols = if (pictures) MemorySymbols.pictures else MemorySymbols.coloursAndShapes
    val sequence = SampleSequence.map { symbols[it] }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SequencePanel(sequence = sequence, filled = sequence.take(2), showing = true, pictures = pictures)
        SequencePanel(sequence = sequence, filled = sequence.dropLast(1), showing = false, pictures = pictures)
    }
}

@Preview(name = "Sequence panel", widthDp = 411)
@Composable
private fun SequencePanelPreview(@PreviewParameter(KidTierPreviewProvider::class) tier: KidUiTier) =
    PlaygroundPreview(tier) { SequencePanelSample(pictures = tier == KidUiTier.SEEDLING) }

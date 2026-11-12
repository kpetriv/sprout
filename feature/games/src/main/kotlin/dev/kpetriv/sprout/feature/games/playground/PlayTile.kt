@file:Suppress("MatchingDeclarationName") // Named after its composables; the defaults object sits beside them.

package dev.kpetriv.sprout.feature.games.playground

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.theme.Accent
import dev.kpetriv.sprout.core.designsystem.theme.LocalReducedMotion
import dev.kpetriv.sprout.core.designsystem.theme.PlaygroundAccent
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier

/** Defaults for [PlayTile]. */
internal object PlayTileDefaults {
    /** How long the press travels (DESIGN-playground.md "Play tile"). */
    const val PRESS_MILLIS: Int = 80

    /** A disabled tile's opacity. */
    const val DISABLED_ALPHA: Float = 0.4f

    /** The outline variant's border: 2 dp of Chalk at 60 %. */
    val OutlineWidth: Dp = 2.dp
    const val OUTLINE_ALPHA: Float = 0.6f

    /** The focus ring (keyboard, switch access): Chalk, this wide, this far outside the tile. */
    val FocusWidth: Dp = 2.dp
    val FocusGap: Dp = 3.dp

    /** Under reduced motion a press darkens the fill instead of moving it. */
    const val PRESSED_SHADE_ALPHA: Float = 0.12f

    val ContentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
}

/**
 * The chunky Playground tile (M9-games §2.2): [accent]'s fill with a solid bottom edge in its edge colour, drawn as a
 * second rounded shape below the fill (never a shadow). The accent is resolved for the tier
 * ([dev.kpetriv.sprout.core.designsystem.theme.PlaygroundTokens.resolve]: the quiet Sapling set turns Marigold into
 * Lime and Coral into Sky, [Accent.Primary] stays Marigold). Content gets Ink (Chalk on [Accent.Outline]) and the
 * [PlaygroundType.tileLarge] style.
 *
 * Pressed, the fill and content translate down by the edge height over 80 ms (under `LocalReducedMotion` they stay
 * put and the fill darkens instead). `enabled = false` dims the tile to 40 % and drops the edge (the fill sits in its
 * place). [Accent.Outline] is a 2 dp Chalk-60 % border with no edge. At least the tier's minimum touch target in both
 * directions, edge included; a clickable tile is a `Role.Button`, a tile with a null [onClick] is a plain surface.
 * Focused (keyboard, switch access), it shows a 2 dp Chalk ring just outside its shape.
 *
 * [modifier] is applied inside the minimum size, so a smaller fixed size is raised to the touch target. For the same
 * reason spacing must not go in it: `Modifier.padding` here would shrink the tile and its touch target below the
 * minimum. Space tiles with the parent's arrangement, or put the padding on a wrapping layout.
 *
 * @param shape the tile's shape; the tier's [PlaygroundTokens.tileCorner] rounded rectangle by default.
 */
@Composable
internal fun PlayTile(
    accent: Accent,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(Playground.tokens.tileCorner),
    contentPadding: PaddingValues = PlayTileDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = Playground.colors
    val resolved = Playground.tokens.resolve(accent, colors)
    PlayTileSurface(
        colors = resolved,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        outlineColor = colors.chalk.copy(alpha = PlayTileDefaults.OUTLINE_ALPHA),
        pressedShade = if (resolved.isOutline) colors.chalk else colors.ink,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

/** [PlayTile] with explicit colours, for tiles outside the four accents (the top bar's Chalk-12 % back tile). */
@Composable
internal fun PlayTileSurface(
    colors: PlaygroundAccent,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(Playground.tokens.tileCorner),
    outlineColor: Color = Playground.colors.chalk.copy(alpha = PlayTileDefaults.OUTLINE_ALPHA),
    pressedShade: Color = Playground.colors.ink,
    contentPadding: PaddingValues = PlayTileDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val focusRing = Playground.colors.chalk
    val reducedMotion = LocalReducedMotion.current
    val outline = colors.isOutline
    val edge = if (outline) 0.dp else Playground.tokens.tileEdge
    // 0: resting on its edge; 1: sunk by the edge height (pressed, or disabled with no edge to show).
    val motion = TileMotion(enabled = enabled, pressed = pressed, reducedMotion = reducedMotion, outline = outline)
    val sunk by animateFloatAsState(targetValue = motion.sunk, animationSpec = motion.spec, label = "playTilePress")
    val shaded = motion.shaded
    val touch = SproutTheme.tierTokens.minTouchTarget
    Box(
        // The minimum comes first, so a caller's smaller fixed size is raised to it rather than winning.
        modifier = Modifier
            .sizeIn(minWidth = touch, minHeight = touch)
            .then(modifier)
            .alpha(if (enabled) 1f else PlayTileDefaults.DISABLED_ALPHA)
            .tileClickable(onClick, enabled, source)
            .drawBehind {
                drawTile(
                    shape = shape,
                    colors = colors,
                    edge = edge.toPx(),
                    sunk = sunk,
                    outlineColor = outlineColor,
                    outlineWidth = PlayTileDefaults.OutlineWidth.toPx(),
                    shade = if (shaded) pressedShade.copy(alpha = PlayTileDefaults.PRESSED_SHADE_ALPHA) else null,
                )
                if (focused && enabled) {
                    drawFocusRing(
                        shape,
                        focusRing,
                        PlayTileDefaults.FocusWidth.toPx(),
                        PlayTileDefaults.FocusGap.toPx(),
                    )
                }
            }
            .padding(bottom = edge)
            .graphicsLayer { translationY = sunk * edge.toPx() }
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides colors.content,
            LocalTextStyle provides PlaygroundType.tileLarge.copy(color = colors.content),
        ) { content() }
    }
}

/**
 * How a tile moves: fully sunk when disabled (the fill covers the edge) or pressed with motion on, animated over 80 ms
 * only when enabled with motion on, and shaded instead of moving when pressed under reduced motion (or an outline,
 * which has no edge to sink into).
 */
private class TileMotion(enabled: Boolean, pressed: Boolean, reducedMotion: Boolean, outline: Boolean) {
    val sunk: Float = if (!enabled || (pressed && !reducedMotion)) 1f else 0f
    val spec: AnimationSpec<Float> = if (enabled && !reducedMotion) tween(PlayTileDefaults.PRESS_MILLIS) else snap()
    val shaded: Boolean = pressed && enabled && (reducedMotion || outline)
}

/** A `Role.Button` click without a ripple (the press travel is the feedback); nothing when [onClick] is null. */
private fun Modifier.tileClickable(
    onClick: (() -> Unit)?,
    enabled: Boolean,
    source: MutableInteractionSource,
): Modifier = if (onClick == null) {
    this
} else {
    clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
}

/** A ring [gap] outside the tile, so keyboard and switch-access users see which tile has focus. */
private fun DrawScope.drawFocusRing(shape: Shape, color: Color, width: Float, gap: Float) {
    val outset = gap + width / 2
    val ring = shape.createOutline(Size(size.width + outset * 2, size.height + outset * 2), layoutDirection, this)
    translate(-outset, -outset) { drawOutline(ring, color, style = Stroke(width)) }
}

/** The edge as the whole shape, then the fill one edge shorter on top of it, [sunk] of the way down. */
@Suppress("LongParameterList") // One draw call's inputs, all needed.
private fun DrawScope.drawTile(
    shape: Shape,
    colors: PlaygroundAccent,
    edge: Float,
    sunk: Float,
    outlineColor: Color,
    outlineWidth: Float,
    shade: Color?,
) {
    if (colors.isOutline) {
        val inset = outlineWidth / 2
        val inner = Size(size.width - outlineWidth, size.height - outlineWidth)
        val outline = shape.createOutline(inner, layoutDirection, this)
        translate(inset, inset) {
            if (shade != null) drawOutline(outline, shade)
            drawOutline(outline, outlineColor, style = Stroke(outlineWidth))
        }
        return
    }
    val fillSize = Size(size.width, size.height - edge)
    val fill: Outline = shape.createOutline(fillSize, layoutDirection, this)
    // Fully sunk (pressed or disabled) the fill covers the edge; skip it so no fringe shows at the corners.
    if (edge > 0f && sunk < 1f) {
        translate(top = edge) { drawOutline(fill, colors.edge) }
    }
    translate(top = sunk * edge) {
        drawOutline(fill, colors.fill)
        if (shade != null) drawOutline(fill, shade)
    }
}

/** Every accent enabled, two disabled and the outline, as the tier resolves them, for previews and goldens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayTileSample() {
    val height = Playground.tokens.tileHeight
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SampleTile(Accent.Lime, "Lime", height)
            SampleTile(Accent.Marigold, "Gold", height)
            SampleTile(Accent.Sky, "Sky", height)
            SampleTile(Accent.Coral, "Coral", height)
            SampleTile(Accent.Primary, "Go", height)
            SampleTile(Accent.Panel, "Panel", height)
            SampleTile(Accent.Outline, "Clear", height)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SampleTile(Accent.Marigold, "Used", height, enabled = false)
            SampleTile(Accent.Sky, "Off", height, enabled = false)
            SampleTile(Accent.Outline, "None", height, enabled = false)
        }
    }
}

@Composable
private fun SampleTile(accent: Accent, label: String, height: Dp, enabled: Boolean = true) {
    PlayTile(
        accent = accent,
        onClick = {},
        enabled = enabled,
        modifier = Modifier.height(height).width(SampleTileWidth),
    ) {
        Text(text = label, style = PlaygroundType.tile.copy(color = LocalContentColor.current))
    }
}

private val SampleTileWidth = 120.dp

@Preview(name = "Play tiles", widthDp = 440)
@Composable
private fun PlayTilePreview(@PreviewParameter(KidTierPreviewProvider::class) tier: KidUiTier) =
    PlaygroundPreview(tier) { PlayTileSample() }

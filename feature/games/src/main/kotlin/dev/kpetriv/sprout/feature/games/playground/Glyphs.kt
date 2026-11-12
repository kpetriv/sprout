@file:Suppress("MagicNumber") // Drawings in a 100-unit box: the coordinates are the drawing.

package dev.kpetriv.sprout.feature.games.playground

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.theme.PlaygroundColors
import dev.kpetriv.sprout.core.games.MemorySymbols
import dev.kpetriv.sprout.core.games.Symbol
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * The memory symbols drawn as flat vector shapes (M9-games §2.2, D-M9-G5): no emoji on screen. Every drawing is
 * deterministic Canvas code in a 100 x 100 unit box, scaled to the glyph's size. `Symbol.glyph` stays in core/games
 * for the wire and the tests; the UI keys the drawings on `Symbol.id`.
 *
 * Symbol colours are content, not decoration, so they do not go through the quiet Sapling accent dial: a red circle
 * is Coral at every tier (otherwise Sapling's red and blue circles would look the same).
 */

/** Box size the drawings are written in. */
private const val UNITS = 100f

/**
 * One of the twelve colour-and-shape symbols (Sprout and Sapling): red → Coral, green → Lime, blue → Sky, yellow →
 * Marigold, each with its darker edge under it; the star and heart in Chalk, the triangle and diamond in Sky. An
 * unknown id draws a Chalk ring. Labelled with [Symbol.label] ("red circle") unless [labelled] is false (when the
 * caller describes it, such as a tile that merges it into its own label).
 */
@Composable
internal fun ShapeGlyph(symbol: Symbol, size: Dp, modifier: Modifier = Modifier, labelled: Boolean = true) {
    val colors = Playground.colors
    Canvas(modifier = modifier.size(size).glyphSemantics(symbol, labelled)) {
        scale(this.size.minDimension / UNITS, pivot = Offset.Zero) { drawShape(symbol.id, colors) }
    }
}

/**
 * One of the twelve Seedling pictures: flat geometric drawings in the four accents with Ink outlines, meant for a
 * Chalk tile or panel. An unknown id draws an Ink-outlined Chalk disc. Labelled as [ShapeGlyph] is.
 */
@Composable
internal fun PictureGlyph(symbol: Symbol, size: Dp, modifier: Modifier = Modifier, labelled: Boolean = true) {
    val colors = Playground.colors
    Canvas(modifier = modifier.size(size).glyphSemantics(symbol, labelled)) {
        scale(this.size.minDimension / UNITS, pivot = Offset.Zero) { drawPicture(symbol.id, colors) }
    }
}

private fun Modifier.glyphSemantics(symbol: Symbol, labelled: Boolean): Modifier =
    if (labelled) semantics { contentDescription = symbol.label } else this

// Shapes ------------------------------------------------------------------------------------------------------------

private const val SHAPE_EDGE = 5f

private fun DrawScope.drawShape(id: String, colors: PlaygroundColors) {
    val path = shapePath(id)
    if (path == null) {
        drawCircle(colors.chalk, radius = 40f, center = Offset(50f, 50f), style = Stroke(8f))
        return
    }
    val (fill, edge) = shapeColours(id, colors)
    translate(top = SHAPE_EDGE) { drawPath(path, edge) }
    drawPath(path, fill)
}

/** Fill and edge of a shape: its colour word (red, green, blue, yellow), else Sky (triangle, diamond) or Chalk. */
private fun shapeColours(id: String, colors: PlaygroundColors): Pair<Color, Color> =
    when (id.substringBefore('-', missingDelimiterValue = "")) {
        "red" -> colors.coral to colors.coralEdge

        "green" -> colors.lime to colors.limeEdge

        "blue" -> colors.sky to colors.skyEdge

        "yellow" -> colors.marigold to colors.marigoldEdge

        else -> if (id == "triangle" ||
            id == "diamond"
        ) {
            colors.sky to colors.skyEdge
        } else {
            colors.chalk to colors.chalkSoft
        }
    }

/** The outline of a shape id's form (the part after the colour word), or null for an unknown form. */
private fun shapePath(id: String): Path? = when (id.substringAfter('-')) {
    "circle" -> Path().apply { addOval(Rect(8f, 6f, 92f, 90f)) }
    "square" -> Path().apply { addRoundRect(roundRect(10f, 8f, 90f, 88f, 16f)) }
    "star" -> starPath(center = Offset(50f, 50f), outer = 46f, inner = 20f)
    "heart" -> heartPath()
    "triangle" -> polygon(listOf(Offset(50f, 8f), Offset(94f, 86f), Offset(6f, 86f)), corner = 8f)
    "diamond" -> polygon(listOf(Offset(50f, 4f), Offset(92f, 48f), Offset(50f, 92f), Offset(8f, 48f)), corner = 8f)
    else -> null
}

private fun roundRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float) =
    RoundRect(left, top, right, bottom, CornerRadius(radius))

private fun starPath(center: Offset, outer: Float, inner: Float, points: Int = 5): Path = Path().apply {
    val step = PI / points
    for (i in 0 until points * 2) {
        val radius = if (i % 2 == 0) outer else inner
        val angle = -PI / 2 + i * step
        val point = Offset(center.x + (radius * cos(angle)).toFloat(), center.y + (radius * sin(angle)).toFloat())
        if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
    }
    close()
}

private fun heartPath(): Path = Path().apply {
    moveTo(50f, 90f)
    cubicTo(20f, 68f, 4f, 50f, 6f, 32f)
    cubicTo(8f, 14f, 22f, 6f, 34f, 8f)
    cubicTo(42f, 9f, 47f, 14f, 50f, 22f)
    cubicTo(53f, 14f, 58f, 9f, 66f, 8f)
    cubicTo(78f, 6f, 92f, 14f, 94f, 32f)
    cubicTo(96f, 50f, 80f, 68f, 50f, 90f)
    close()
}

/** A polygon through [points] with its corners rounded by [corner] units (quadratic curves at each vertex). */
private fun polygon(points: List<Offset>, corner: Float): Path = Path().apply {
    val n = points.size
    fun towards(from: Offset, to: Offset, distance: Float): Offset {
        val d = to - from
        val length = d.getDistance()
        return from + d * (distance / length)
    }
    for (i in 0 until n) {
        val previous = points[(i - 1 + n) % n]
        val vertex = points[i]
        val next = points[(i + 1) % n]
        val start = towards(vertex, previous, corner)
        val end = towards(vertex, next, corner)
        if (i == 0) moveTo(start.x, start.y) else lineTo(start.x, start.y)
        quadraticTo(vertex.x, vertex.y, end.x, end.y)
    }
    close()
}

// Pictures ----------------------------------------------------------------------------------------------------------

private const val OUTLINE = 4f

private fun DrawScope.outlined(path: Path, fill: Color, ink: Color) {
    drawPath(path, fill)
    drawPath(path, ink, style = Stroke(width = OUTLINE, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun oval(left: Float, top: Float, right: Float, bottom: Float) = Path().apply {
    addOval(Rect(left, top, right, bottom))
}

private fun circle(x: Float, y: Float, r: Float) = oval(x - r, y - r, x + r, y + r)

private fun union(paths: List<Path>): Path = paths.reduce { acc, path -> Path.combine(PathOperation.Union, acc, path) }

private fun line(ink: Color, scope: DrawScope, from: Offset, to: Offset, width: Float = OUTLINE) =
    scope.drawLine(ink, from, to, strokeWidth = width, cap = StrokeCap.Round)

@Suppress("CyclomaticComplexMethod") // One branch per picture.
private fun DrawScope.drawPicture(id: String, c: PlaygroundColors) {
    when (id) {
        "apple" -> apple(c)
        "banana" -> banana(c)
        "dog" -> dog(c)
        "cat" -> cat(c)
        "fish" -> fish(c)
        "flower" -> flower(c)
        "sun" -> sun(c)
        "moon" -> moon(c)
        "car" -> car(c)
        "ball" -> ball(c)
        "house" -> house(c)
        "tree" -> tree(c)
        else -> outlined(circle(50f, 50f, 40f), c.chalk, c.ink)
    }
}

private fun DrawScope.apple(c: PlaygroundColors) {
    line(c.ink, this, Offset(50f, 30f), Offset(54f, 12f), width = 6f)
    rotate(-30f, pivot = Offset(66f, 18f)) { outlined(oval(56f, 10f, 80f, 24f), c.lime, c.ink) }
    val body = union(listOf(circle(37f, 58f, 28f), circle(63f, 58f, 28f), oval(30f, 30f, 70f, 90f)))
    outlined(body, c.coral, c.ink)
}

private fun DrawScope.banana(c: PlaygroundColors) {
    val crescent = Path.combine(PathOperation.Difference, circle(50f, 30f, 50f), circle(50f, 8f, 50f))
    rotate(-18f, pivot = Offset(50f, 50f)) {
        outlined(crescent, c.marigold, c.ink)
        drawCircle(c.ink, radius = 4f, center = Offset(3f, 34f))
        drawCircle(c.ink, radius = 4f, center = Offset(97f, 34f))
    }
}

private fun DrawScope.dog(c: PlaygroundColors) {
    rotate(18f, pivot = Offset(24f, 30f)) { outlined(oval(12f, 22f, 36f, 66f), c.marigoldEdge, c.ink) }
    rotate(-18f, pivot = Offset(76f, 30f)) { outlined(oval(64f, 22f, 88f, 66f), c.marigoldEdge, c.ink) }
    outlined(oval(22f, 18f, 78f, 86f), c.marigold, c.ink)
    outlined(oval(36f, 54f, 64f, 80f), c.chalk, c.ink)
    drawCircle(c.ink, radius = 4.5f, center = Offset(39f, 44f))
    drawCircle(c.ink, radius = 4.5f, center = Offset(61f, 44f))
    drawOval(c.ink, topLeft = Offset(43f, 56f), size = Size(14f, 9f))
    line(c.ink, this, Offset(50f, 65f), Offset(50f, 72f))
}

private fun DrawScope.cat(c: PlaygroundColors) {
    val ears = union(
        listOf(
            polygon(listOf(Offset(18f, 10f), Offset(42f, 30f), Offset(20f, 44f)), corner = 4f),
            polygon(listOf(Offset(82f, 10f), Offset(80f, 44f), Offset(58f, 30f)), corner = 4f),
            oval(14f, 24f, 86f, 90f),
        ),
    )
    outlined(ears, c.sky, c.ink)
    drawCircle(c.ink, radius = 5f, center = Offset(36f, 52f))
    drawCircle(c.ink, radius = 5f, center = Offset(64f, 52f))
    outlined(polygon(listOf(Offset(44f, 62f), Offset(56f, 62f), Offset(50f, 70f)), corner = 2f), c.coral, c.ink)
    line(c.ink, this, Offset(8f, 64f), Offset(32f, 68f), width = 3f)
    line(c.ink, this, Offset(8f, 76f), Offset(32f, 72f), width = 3f)
    line(c.ink, this, Offset(92f, 64f), Offset(68f, 68f), width = 3f)
    line(c.ink, this, Offset(92f, 76f), Offset(68f, 72f), width = 3f)
}

private fun DrawScope.fish(c: PlaygroundColors) {
    val body = union(
        listOf(
            oval(6f, 26f, 76f, 74f),
            polygon(listOf(Offset(66f, 50f), Offset(96f, 24f), Offset(96f, 76f)), corner = 4f),
        ),
    )
    outlined(body, c.lime, c.ink)
    outlined(polygon(listOf(Offset(34f, 28f), Offset(52f, 16f), Offset(56f, 30f)), corner = 3f), c.limeEdge, c.ink)
    drawCircle(c.chalk, radius = 7f, center = Offset(26f, 46f))
    drawCircle(c.ink, radius = 4f, center = Offset(26f, 46f))
    line(c.ink, this, Offset(44f, 40f), Offset(44f, 60f))
}

private fun DrawScope.flower(c: PlaygroundColors) {
    line(c.ink, this, Offset(50f, 60f), Offset(50f, 96f), width = 7f)
    line(c.lime, this, Offset(50f, 60f), Offset(50f, 96f), width = 3f)
    val petals = (0 until 5).map { i ->
        val angle = -PI / 2 + i * 2 * PI / 5
        circle(50f + (22 * cos(angle)).toFloat(), 40f + (22 * sin(angle)).toFloat(), 15f)
    }
    outlined(union(petals), c.coral, c.ink)
    outlined(circle(50f, 40f, 12f), c.marigold, c.ink)
}

private fun DrawScope.sun(c: PlaygroundColors) {
    repeat(8) { i ->
        rotate(i * 45f, pivot = Offset(50f, 50f)) {
            outlined(
                polygon(listOf(Offset(44f, 16f), Offset(50f, 2f), Offset(56f, 16f)), corner = 2f),
                c.marigold,
                c.ink,
            )
        }
    }
    outlined(circle(50f, 50f, 28f), c.marigold, c.ink)
}

private fun DrawScope.moon(c: PlaygroundColors) {
    val crescent = Path.combine(PathOperation.Difference, circle(46f, 50f, 40f), circle(66f, 40f, 34f))
    outlined(crescent, c.sky, c.ink)
    outlined(starPath(Offset(78f, 74f), outer = 12f, inner = 5f), c.marigold, c.ink)
}

private fun DrawScope.car(c: PlaygroundColors) {
    val body = union(
        listOf(
            Path().apply { addRoundRect(roundRect(6f, 44f, 94f, 76f, 10f)) },
            Path().apply { addRoundRect(roundRect(24f, 22f, 72f, 56f, 12f)) },
        ),
    )
    outlined(body, c.coral, c.ink)
    outlined(Path().apply { addRoundRect(roundRect(30f, 28f, 46f, 44f, 4f)) }, c.sky, c.ink)
    outlined(Path().apply { addRoundRect(roundRect(52f, 28f, 66f, 44f, 4f)) }, c.sky, c.ink)
    outlined(circle(28f, 78f, 11f), c.ink, c.ink)
    outlined(circle(72f, 78f, 11f), c.ink, c.ink)
    drawCircle(c.chalk, radius = 4f, center = Offset(28f, 78f))
    drawCircle(c.chalk, radius = 4f, center = Offset(72f, 78f))
}

private fun DrawScope.ball(c: PlaygroundColors) {
    val accents = listOf(c.coral, c.marigold, c.sky, c.lime)
    repeat(4) { i ->
        drawArc(
            color = accents[i],
            startAngle = -90f + i * 90f,
            sweepAngle = 90f,
            useCenter = true,
            topLeft = Offset(8f, 8f),
            size = Size(84f, 84f),
        )
    }
    line(c.ink, this, Offset(50f, 8f), Offset(50f, 92f), width = 3f)
    line(c.ink, this, Offset(8f, 50f), Offset(92f, 50f), width = 3f)
    outlined(circle(50f, 50f, 9f), c.chalk, c.ink)
    drawCircle(c.ink, radius = 42f, center = Offset(50f, 50f), style = Stroke(OUTLINE))
}

private fun DrawScope.house(c: PlaygroundColors) {
    outlined(Path().apply { addRect(Rect(18f, 46f, 82f, 92f)) }, c.marigold, c.ink)
    outlined(polygon(listOf(Offset(50f, 8f), Offset(94f, 50f), Offset(6f, 50f)), corner = 4f), c.coral, c.ink)
    outlined(Path().apply { addRoundRect(roundRect(42f, 64f, 58f, 92f, 4f)) }, c.sky, c.ink)
    outlined(Path().apply { addRoundRect(roundRect(24f, 56f, 36f, 68f, 2f)) }, c.sky, c.ink)
    outlined(Path().apply { addRoundRect(roundRect(64f, 56f, 76f, 68f, 2f)) }, c.sky, c.ink)
}

private fun DrawScope.tree(c: PlaygroundColors) {
    outlined(Path().apply { addRoundRect(roundRect(42f, 56f, 58f, 94f, 3f)) }, c.marigoldEdge, c.ink)
    val canopy =
        union(listOf(circle(50f, 30f, 22f), circle(30f, 50f, 20f), circle(70f, 50f, 20f), circle(50f, 58f, 18f)))
    outlined(canopy, c.lime, c.ink)
}

/** The twelve shapes on the field, for previews and goldens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShapeGlyphSample() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MemorySymbols.coloursAndShapes.forEach { ShapeGlyph(symbol = it, size = SampleGlyphSize) }
    }
}

/** The twelve Seedling pictures, each on a Chalk tile as the Seedling memory game shows them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PictureGlyphSample() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MemorySymbols.pictures.forEach { symbol ->
            Box(
                modifier = Modifier.background(Playground.colors.chalk, RoundedCornerShape(16.dp)).padding(8.dp),
            ) { PictureGlyph(symbol = symbol, size = SampleGlyphSize) }
        }
    }
}

private val SampleGlyphSize = 64.dp

@Preview(name = "Shape glyphs", widthDp = 411)
@Composable
private fun ShapeGlyphPreview(@PreviewParameter(KidTierPreviewProvider::class) tier: KidUiTier) =
    PlaygroundPreview(tier) { ShapeGlyphSample() }

@Preview(name = "Picture glyphs", widthDp = 411)
@Composable
private fun PictureGlyphPreview(@PreviewParameter(KidTierPreviewProvider::class) tier: KidUiTier) =
    PlaygroundPreview(tier) { PictureGlyphSample() }

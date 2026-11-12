@file:Suppress("MagicNumber") // Pictogram geometry: coordinates on a 48 x 48 grid, drawn by eye.

package dev.kpetriv.sprout.core.designsystem.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.theme.SproutColors
import dev.kpetriv.sprout.core.designsystem.theme.SproutPreviewFrame
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme

/**
 * The curated pictogram set that replaces emoji (M9 §2.7, D-M9-5): flat shapes in the garden accents with 2 dp Ink
 * outlines on a 48 dp grid, built in code so they render identically everywhere (Robolectric included). The colours
 * are the same in light and dark (sticker style: the fills carry the shape on either background).
 *
 * Keys follow meaning, not the old emoji; slices B to D map their keyword tables onto them (`ChoreUi.kt`'s chore
 * keywords, the shop's reward kinds, the wallet's event categories, the games hub).
 */
enum class Pictogram(private val draw: PictogramPen.() -> Unit) {
    // Chores (feature/chores ChoreEmoji keywords).
    /** bed */
    BED(PictogramPen::bed),

    /** teeth, tooth, brush */
    TOOTH(PictogramPen::tooth),

    /** dish, plate, cup, kitchen */
    DISHES(PictogramPen::dishes),

    /** table (laying the table) */
    CUTLERY(PictogramPen::cutlery),

    /** toy, lego */
    TEDDY(PictogramPen::teddy),

    /** room, tidy, clean; also the wallet's chores category */
    BROOM(PictogramPen::broom),

    /** laundry, clothes, sock */
    LAUNDRY(PictogramPen::laundry),

    /** plant, water, garden, flower; also the wallet's interest category */
    SEEDLING(PictogramPen::seedling),

    /** dog, cat, pet, fish, feed */
    PAW(PictogramPen::paw),

    /** trash, rubbish, bin, recycle */
    BIN(PictogramPen::bin),

    /** read, book */
    BOOK(PictogramPen::book),

    /** homework, school, study; also the wallet's adjustment category */
    PENCIL(PictogramPen::pencil),

    /** shoe */
    SHOE(PictogramPen::shoe),

    /** bath, shower, wash */
    BATH(PictogramPen::bath),

    /** car */
    CAR(PictogramPen::car),

    /** A chore no keyword matched (was the sparkle). */
    SPARKLE(PictogramPen::sparkle),

    // Shop reward kinds and purchases (feature/wallet).

    /** Screen time. */
    TV(PictogramPen::tv),

    /** A treat. */
    ICE_CREAM(PictogramPen::iceCream),

    /** An outing. */
    FERRIS_WHEEL(PictogramPen::ferrisWheel),

    /** Any other reward. */
    GIFT(PictogramPen::gift),

    /** A purchase went through. */
    PARTY(PictogramPen::party),

    // Wallet event categories (feature/wallet EventCategoryUi).

    /** Allowance. */
    MONEY_BAG(PictogramPen::moneyBag),

    /** Game credits. */
    CONTROLLER(PictogramPen::controller),

    /** Shop spending. */
    SHOPPING_BAG(PictogramPen::shoppingBag),

    /** Savings. */
    PIGGY_BANK(PictogramPen::piggyBank),

    // Games (feature/games).

    /** The numbers (arithmetic) game. */
    GAME_NUMBERS(PictogramPen::gameNumbers),

    /** The memory game. */
    GAME_MEMORY(PictogramPen::gameMemory),

    /** The word game. */
    GAME_WORDS(PictogramPen::gameWords),

    /** The times tables game. */
    GAME_TABLES(PictogramPen::gameTables),

    /** A perfect score. */
    TROPHY(PictogramPen::trophy),

    /** A good score; a savings star. */
    STAR(PictogramPen::star),

    /** The daily game credit cap is reached ("come back tomorrow"). */
    MOON(PictogramPen::moon),

    // Money and time.

    /** Coins, game credit. */
    COIN(PictogramPen::coin),

    /** Screen time left. */
    SUN(PictogramPen::sun),

    /** Time, the screen-time category. */
    CLOCK(PictogramPen::clock),

    /** Fallback when nothing else fits: the sprout mark in a circle. */
    GENERIC(PictogramPen::generic),
    ;

    /** This pictogram as a 48 dp vector, built once. */
    val imageVector: ImageVector by lazy {
        val builder = ImageVector.Builder(
            name = "Pictogram.$name",
            defaultWidth = PictogramDefaults.Size,
            defaultHeight = PictogramDefaults.Size,
            viewportWidth = GRID,
            viewportHeight = GRID,
        )
        PictogramPen(builder).draw()
        builder.build()
    }
}

/** Defaults for [Pictogram]. */
object PictogramDefaults {
    /** The default size (disc included). The drawing is on a 48 grid: strokes are 2 dp when drawn 48 dp wide. */
    val Size: Dp = 48.dp

    /** How much of the disc the drawing fills. */
    const val IN_DISC: Float = 0.74f
}

/**
 * Draws [pictogram] at [size]. With [disc] (the default) it sits on a paper disc ([SproutColors.pictogramDisc]),
 * as in the renders, which also keeps its Ink outlines visible in the dark theme; turn it off only on a light surface
 * that already frames it. Decorative by default: the text next to it (the chore title, the item name) carries the
 * meaning. Pass a [contentDescription] only when the pictogram stands alone.
 */
@Composable
fun Pictogram(
    pictogram: Pictogram,
    modifier: Modifier = Modifier,
    size: Dp = PictogramDefaults.Size,
    disc: Boolean = true,
    contentDescription: String? = null,
) {
    if (!disc) {
        Image(pictogram.imageVector, contentDescription, modifier.size(size))
        return
    }
    Box(
        modifier = modifier.size(size).background(SproutTheme.colors.pictogramDisc, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Image(pictogram.imageVector, contentDescription, Modifier.size(size * PictogramDefaults.IN_DISC))
    }
}

/** Every pictogram with its name, six to a row, for previews and goldens. */
@Composable
internal fun PictogramSample() {
    Column(
        modifier = Modifier.padding(SproutSpacing.md),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
    ) {
        Pictogram.entries.chunked(SAMPLE_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm)) {
                row.forEach { pictogram ->
                    Column(
                        modifier = Modifier.width(SampleCellWidth),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Pictogram(pictogram)
                        Text(
                            text = pictogram.name.lowercase(),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private const val SAMPLE_COLUMNS = 6
private val SampleCellWidth = 58.dp

@Preview(name = "Light", showBackground = true, widthDp = 420)
@Composable
private fun PictogramLightPreview() = SproutPreviewFrame(tier = null) { PictogramSample() }

@Preview(name = "Dark", showBackground = true, widthDp = 420)
@Composable
private fun PictogramDarkPreview() = SproutPreviewFrame(tier = null, darkTheme = true) { PictogramSample() }

private const val GRID = 48f
private const val STROKE = 2f

/** The pictogram palette: the garden accents at their mid tones, white paper and Ink outlines. */
private object Palette {
    val Ink = Color(0xFF1A1C19)
    val Leaf = Color(0xFF8FD3A3)
    val Honey = Color(0xFFF0C56A)
    val Sky = Color(0xFF8EC3EA)
    val Coral = Color(0xFFFFB4AB)
    val Paper = Color(0xFFFFFFFF)
}

/** A tiny drawing API over [ImageVector.Builder]: filled shapes with Ink outlines, and Ink lines. */
internal class PictogramPen(private val builder: ImageVector.Builder) {
    fun shape(fill: Color?, outline: Boolean = true, width: Float = STROKE, block: PathBuilder.() -> Unit) {
        builder.addPath(
            pathData = PathBuilder().apply(block).nodes,
            fill = fill?.let(::SolidColor),
            stroke = if (outline) SolidColor(Palette.Ink) else null,
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }

    fun path(fill: Color?, data: String, outline: Boolean = true) {
        builder.addPath(
            pathData = addPathNodes(data),
            fill = fill?.let(::SolidColor),
            stroke = if (outline) SolidColor(Palette.Ink) else null,
            strokeLineWidth = STROKE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }

    fun line(vararg points: Float, width: Float = STROKE) = shape(fill = null, width = width) {
        moveTo(points[0], points[1])
        var i = 2
        while (i < points.size) {
            lineTo(points[i], points[i + 1])
            i += 2
        }
    }

    fun circle(cx: Float, cy: Float, r: Float, fill: Color?, outline: Boolean = true) = shape(fill, outline) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 2 * r, dy1 = 0f)
        arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = -2 * r, dy1 = 0f)
        close()
    }

    fun rect(bounds: Rect, radius: Float, fill: Color?) = shape(fill) {
        val left = bounds.left
        val top = bounds.top
        val right = bounds.right
        val bottom = bounds.bottom
        moveTo(left + radius, top)
        lineTo(right - radius, top)
        arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = right, y1 = top + radius)
        lineTo(right, bottom - radius)
        arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = right - radius, y1 = bottom)
        lineTo(left + radius, bottom)
        arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = left, y1 = bottom - radius)
        lineTo(left, top + radius)
        arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = left + radius, y1 = top)
        close()
    }

    /** A four-pointed sparkle centred on ([cx], [cy]). */
    fun sparkleShape(cx: Float, cy: Float, r: Float, fill: Color) = shape(fill) {
        val k = r * 0.18f
        moveTo(cx, cy - r)
        quadTo(cx + k, cy - k, cx + r, cy)
        quadTo(cx + k, cy + k, cx, cy + r)
        quadTo(cx - k, cy + k, cx - r, cy)
        quadTo(cx - k, cy - k, cx, cy - r)
        close()
    }

    /** A five-pointed star centred on ([cx], [cy]), pointing up. */
    fun starShape(cx: Float, cy: Float, outer: Float, fill: Color) = shape(fill) {
        val inner = outer * 0.45f
        for (point in 0 until 10) {
            val radius = if (point % 2 == 0) outer else inner
            val angle = -Math.PI / 2 + point * Math.PI / 5
            val x = cx + (radius * kotlin.math.cos(angle)).toFloat()
            val y = cy + (radius * kotlin.math.sin(angle)).toFloat()
            if (point == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

// Chores.

private fun PictogramPen.bed() {
    line(9f, 36f, 9f, 41f)
    line(39f, 36f, 39f, 41f)
    rect(Rect(6f, 13f, 13f, 37f), 2f, Palette.Honey)
    rect(Rect(6f, 27f, 42f, 37f), 2f, Palette.Sky)
    rect(Rect(15f, 20f, 24f, 27f), 3f, Palette.Paper)
    rect(Rect(24f, 22f, 42f, 31f), 2f, Palette.Leaf)
}

private fun PictogramPen.tooth() {
    path(
        Palette.Paper,
        "M16 9C11 9 9 14 10 19C11 25 13 28 14 34C15 40 19 40 20 34C21 29 22 27 24 27C26 27 27 29 28 34" +
            "C29 40 33 40 34 34C35 28 37 25 38 19C39 14 37 9 32 9C28 9 27 11 24 11C21 11 20 9 16 9Z",
    )
    sparkleShape(39f, 9f, 5f, Palette.Sky)
}

private fun PictogramPen.dishes() {
    circle(24f, 24f, 17f, Palette.Paper)
    circle(24f, 24f, 11f, Palette.Sky)
    circle(24f, 24f, 5f, Palette.Paper, outline = false)
}

private fun PictogramPen.cutlery() {
    line(13f, 8f, 13f, 15f)
    line(17f, 8f, 17f, 15f)
    line(21f, 8f, 21f, 15f)
    path(null, "M13 15C13 19 21 19 21 15")
    rect(Rect(15f, 19f, 19f, 41f), 2f, Palette.Honey)
    path(Palette.Paper, "M30 7L30 26L36 26C37 18 36 11 30 7Z")
    rect(Rect(30f, 26f, 36f, 41f), 2f, Palette.Honey)
}

private fun PictogramPen.teddy() {
    rect(Rect(13f, 30f, 35f, 43f), 8f, Palette.Honey)
    circle(15f, 13f, 5f, Palette.Honey)
    circle(33f, 13f, 5f, Palette.Honey)
    circle(24f, 22f, 11f, Palette.Honey)
    circle(24f, 26f, 4.5f, Palette.Paper)
    circle(20f, 19f, 1.4f, Palette.Ink, outline = false)
    circle(28f, 19f, 1.4f, Palette.Ink, outline = false)
}

private fun PictogramPen.broom() {
    path(Palette.Sky, "M33 4L37 6L26 29L22 27Z")
    path(Palette.Honey, "M18 25L29 30L25 43L7 35Z")
    line(13f, 36f, 20f, 29f)
    line(19f, 39f, 24f, 31f)
}

private fun PictogramPen.laundry() {
    path(Palette.Sky, "M12 19C12 11 20 9 24 14C28 9 36 11 36 19Z")
    path(Palette.Honey, "M9 21L39 21L35 41L13 41Z")
    rect(Rect(6f, 17f, 42f, 22f), 2f, Palette.Honey)
    line(12f, 28f, 36f, 28f)
    line(13f, 34f, 35f, 34f)
}

private fun PictogramPen.seedling() {
    line(24f, 28f, 24f, 16f)
    path(Palette.Leaf, "M24 21C18 21 13 17 12 10C18 10 23 13 24 21Z")
    path(Palette.Leaf, "M24 17C27 11 32 8 38 8C37 14 31 18 24 17Z")
    path(Palette.Honey, "M14 31L34 31L31 43L17 43Z")
    rect(Rect(12f, 27f, 36f, 32f), 1.5f, Palette.Honey)
}

private fun PictogramPen.paw() {
    circle(13f, 21f, 4f, Palette.Honey)
    circle(19.5f, 13f, 4f, Palette.Honey)
    circle(28.5f, 13f, 4f, Palette.Honey)
    circle(35f, 21f, 4f, Palette.Honey)
    path(Palette.Honey, "M24 23C31 23 36 31 34 36C32 41 27 39 24 39C21 39 16 41 14 36C12 31 17 23 24 23Z")
}

private fun PictogramPen.bin() {
    rect(Rect(19f, 6f, 29f, 11f), 2f, null)
    path(Palette.Leaf, "M11 15L37 15L34 42L14 42Z")
    rect(Rect(8f, 10f, 40f, 15f), 2f, Palette.Leaf)
    line(19f, 20f, 20f, 37f)
    line(24f, 20f, 24f, 37f)
    line(29f, 20f, 28f, 37f)
}

private fun PictogramPen.book() {
    path(Palette.Paper, "M24 14C18 10 11 10 6 12L6 37C11 35 18 35 24 39Z")
    path(Palette.Paper, "M24 14C30 10 37 10 42 12L42 37C37 35 30 35 24 39Z")
    line(10f, 18f, 19f, 19f)
    line(10f, 24f, 19f, 25f)
    line(10f, 30f, 19f, 31f)
    path(Palette.Honey, "M31 12L36 12L36 22L33.5 20L31 22Z")
}

private fun PictogramPen.pencil() {
    path(Palette.Honey, "M13 29L31 11L37 17L19 35Z")
    path(Palette.Paper, "M13 29L19 35L8 40Z")
    path(Palette.Coral, "M31 11L34 8L40 14L37 17Z")
    path(Palette.Ink, "M8 40L12 38.5L9.5 36Z", outline = false)
}

private fun PictogramPen.shoe() {
    path(Palette.Sky, "M6 20L6 34L42 34C42 29 38 27 32 26L23 19C19 23 12 24 6 20Z")
    rect(Rect(5f, 33f, 43f, 39f), 2.5f, Palette.Paper)
    line(19f, 24f, 22f, 21f)
    line(23f, 27f, 26f, 24f)
}

private fun PictogramPen.bath() {
    line(40f, 21f, 40f, 9f, 34f, 9f)
    circle(16f, 16f, 3f, Palette.Sky)
    circle(23f, 11f, 2.5f, Palette.Sky)
    circle(29f, 16f, 3.5f, Palette.Sky)
    line(14f, 40f, 12f, 44f)
    line(34f, 40f, 36f, 44f)
    path(Palette.Paper, "M7 24L41 24L39 34C38 38 35 40 31 40L17 40C13 40 10 38 9 34Z")
    rect(Rect(4f, 21f, 44f, 25f), 2f, Palette.Paper)
}

private fun PictogramPen.car() {
    path(Palette.Sky, "M6 31L6 24L12 22L17 14L31 14L37 22L42 24L42 31Z")
    path(Palette.Paper, "M19 17L29 17L33 22L15 22Z")
    circle(14f, 32f, 5f, Palette.Ink)
    circle(34f, 32f, 5f, Palette.Ink)
    circle(14f, 32f, 2f, Palette.Paper, outline = false)
    circle(34f, 32f, 2f, Palette.Paper, outline = false)
}

private fun PictogramPen.sparkle() {
    sparkleShape(21f, 27f, 15f, Palette.Honey)
    sparkleShape(37f, 11f, 6f, Palette.Leaf)
}

// Shop.

private fun PictogramPen.tv() {
    line(18f, 5f, 24f, 11f, 30f, 5f)
    line(24f, 36f, 24f, 41f)
    line(17f, 41f, 31f, 41f)
    rect(Rect(6f, 11f, 42f, 36f), 4f, Palette.Paper)
    rect(Rect(10f, 15f, 38f, 32f), 2f, Palette.Sky)
}

private fun PictogramPen.iceCream() {
    path(Palette.Honey, "M15 22L33 22L24 44Z")
    line(19f, 27f, 27f, 33f)
    line(29f, 27f, 21f, 33f)
    path(Palette.Coral, "M13 23C10 18 13 8 24 8C35 8 38 18 35 23Z")
}

private fun PictogramPen.ferrisWheel() {
    line(24f, 20f, 15f, 42f)
    line(24f, 20f, 33f, 42f)
    line(11f, 42f, 37f, 42f)
    circle(24f, 20f, 13f, Palette.Paper)
    line(11f, 20f, 37f, 20f)
    line(24f, 7f, 24f, 33f)
    circle(24f, 7f, 3.5f, Palette.Sky)
    circle(37f, 20f, 3.5f, Palette.Honey)
    circle(24f, 33f, 3.5f, Palette.Leaf)
    circle(11f, 20f, 3.5f, Palette.Coral)
}

private fun PictogramPen.gift() {
    path(Palette.Honey, "M24 16C16 4 7 14 24 16Z")
    path(Palette.Honey, "M24 16C32 4 41 14 24 16Z")
    rect(Rect(10f, 22f, 38f, 42f), 2f, Palette.Leaf)
    rect(Rect(8f, 16f, 40f, 23f), 2f, Palette.Leaf)
    rect(Rect(21f, 16f, 27f, 42f), 0.5f, Palette.Honey)
}

private fun PictogramPen.party() {
    path(Palette.Honey, "M8 41L16 20L28 32Z")
    line(12f, 31f, 19f, 36f)
    circle(26f, 12f, 2f, Palette.Sky)
    circle(37f, 19f, 2f, Palette.Leaf)
    circle(35f, 8f, 2f, Palette.Coral)
    path(null, "M21 15C21 11 25 11 24 7")
    path(null, "M31 26C35 25 35 29 40 28")
}

// Wallet.

private fun PictogramPen.moneyBag() {
    path(Palette.Honey, "M18 14C8 22 8 42 24 42C40 42 40 22 30 14Z")
    path(Palette.Honey, "M18 11L15 5L24 8L33 5L30 11Z")
    rect(Rect(17f, 10f, 31f, 15f), 2f, Palette.Leaf)
    circle(24f, 29f, 6f, Palette.Paper)
}

private fun PictogramPen.controller() {
    path(
        Palette.Sky,
        "M14 15L34 15C41 15 44 26 43 33C42 38 37 39 34 35L31 31L17 31L14 35C11 39 6 38 5 33C4 26 7 15 14 15Z",
    )
    line(11f, 23f, 17f, 23f)
    line(14f, 20f, 14f, 26f)
    circle(32f, 21f, 2f, Palette.Honey)
    circle(36f, 25f, 2f, Palette.Honey)
}

private fun PictogramPen.shoppingBag() {
    path(null, "M17 20L17 13C17 6 31 6 31 13L31 20")
    rect(Rect(9f, 16f, 39f, 42f), 3f, Palette.Honey)
    circle(17f, 22f, 1.5f, Palette.Ink, outline = false)
    circle(31f, 22f, 1.5f, Palette.Ink, outline = false)
}

private fun PictogramPen.piggyBank() {
    circle(27f, 8f, 4f, Palette.Honey)
    path(Palette.Coral, "M17 15L15 8L23 12Z")
    path(
        Palette.Coral,
        "M10 26C10 16 20 12 27 12C36 12 42 18 42 26C42 33 37 37 34 38L34 42L29 42L29 39L20 39L20 42L15 42" +
            "L15 37C12 35 10 31 10 26Z",
    )
    rect(Rect(5f, 22f, 11f, 30f), 2f, Palette.Coral)
    circle(17f, 22f, 1.4f, Palette.Ink, outline = false)
    line(23f, 16f, 31f, 16f)
}

// Games.

private fun PictogramPen.gameNumbers() {
    rect(Rect(5f, 10f, 43f, 38f), 6f, Palette.Sky)
    line(12f, 18f, 15f, 16f, 15f, 32f)
    path(null, "M20 19C20 14 28 14 28 19C28 23 20 28 20 32L28 32")
    path(null, "M32 16L38 16L34 22C40 22 40 32 34 32C33 32 32 31 32 30")
}

private fun PictogramPen.gameMemory() {
    rect(Rect(7f, 9f, 25f, 35f), 3f, Palette.Leaf)
    rect(Rect(22f, 14f, 40f, 40f), 3f, Palette.Paper)
    starShape(31f, 27f, 6f, Palette.Honey)
}

private fun PictogramPen.gameWords() {
    rect(Rect(4f, 14f, 23f, 34f), 3f, Palette.Honey)
    rect(Rect(25f, 14f, 44f, 34f), 3f, Palette.Leaf)
    line(9f, 29f, 13.5f, 19f, 18f, 29f)
    line(10.8f, 25f, 16.2f, 25f)
    path(null, "M30 19L30 29L35 29C39 29 39 24 35 24L30 24M30 19L34.5 19C38 19 38 24 34.5 24")
}

private fun PictogramPen.gameTables() {
    rect(Rect(6f, 6f, 42f, 42f), 8f, Palette.Leaf)
    line(17f, 17f, 31f, 31f, width = 4f)
    line(31f, 17f, 17f, 31f, width = 4f)
}

private fun PictogramPen.trophy() {
    path(null, "M14 11L9 11C8 19 11 21 15 21")
    path(null, "M34 11L39 11C40 19 37 21 33 21")
    rect(Rect(21f, 28f, 27f, 35f), 0.5f, Palette.Honey)
    path(Palette.Honey, "M14 8L34 8L34 18C34 25 29 29 24 29C19 29 14 25 14 18Z")
    rect(Rect(15f, 34f, 33f, 41f), 2f, Palette.Honey)
}

private fun PictogramPen.star() = starShape(24f, 25f, 18f, Palette.Honey)

private fun PictogramPen.moon() {
    path(
        Palette.Honey,
        "M27 8C15 8 7 17 7 27C7 36 15 42 25 42C32 42 38 38 41 32C29 35 20 26 22 17C23 13 25 10 27 8Z",
    )
    starShape(37f, 13f, 4f, Palette.Honey)
}

// Money and time.

private fun PictogramPen.coin() {
    circle(24f, 24f, 17f, Palette.Honey)
    circle(24f, 24f, 11f, null)
    line(24f, 18f, 24f, 30f)
}

private fun PictogramPen.sun() {
    repeat(8) { ray ->
        val angle = ray * Math.PI / 4
        val dx = kotlin.math.cos(angle).toFloat()
        val dy = kotlin.math.sin(angle).toFloat()
        line(24f + dx * 13f, 24f + dy * 13f, 24f + dx * 19f, 24f + dy * 19f)
    }
    circle(24f, 24f, 9f, Palette.Honey)
}

private fun PictogramPen.clock() {
    rect(Rect(20f, 4f, 28f, 9f), 1.5f, Palette.Sky)
    circle(24f, 26f, 16f, Palette.Sky)
    circle(24f, 26f, 11.5f, Palette.Paper)
    line(24f, 26f, 24f, 18f)
    line(24f, 26f, 30f, 29f)
}

private fun PictogramPen.generic() {
    circle(24f, 24f, 17f, Palette.Paper)
    path(Palette.Leaf, "M17 31C15 22 21 15 33 14C34 25 28 32 17 31Z")
    line(17f, 31f, 26f, 22f)
}

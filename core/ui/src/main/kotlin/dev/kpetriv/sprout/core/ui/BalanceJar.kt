package dev.kpetriv.sprout.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlin.math.roundToInt

/**
 * A coin jar that fills to [level] (0..1, clamped) with [countLabel] underneath ("7 coins"). The fill animates
 * when [level] changes. Accessibility: one node announcing [contentDescription] and the fill as a progress value.
 */
@Composable
fun BalanceJar(
    level: Float,
    countLabel: String,
    tier: KidUiTier,
    modifier: Modifier = Modifier,
    contentDescription: String = countLabel,
    jarSize: Dp = BalanceJarDefaults.JarSize,
) {
    val target = level.coerceIn(0f, 1f)
    val animated by animateFloatAsState(targetValue = target, label = "jarLevel")
    val percent = (target * PERCENT).roundToInt()
    val stateText = pluralStringResource(R.plurals.core_ui_jar_state, percent, percent)
    val scheme = MaterialTheme.colorScheme
    val colors = JarColors(
        outline = scheme.onSurface,
        glass = scheme.surfaceContainerLowest,
        shine = scheme.outlineVariant,
        fill = scheme.secondaryContainer,
        coin = scheme.secondary,
        lid = SproutTheme.colors.honeyBorder,
    )
    Column(
        modifier = modifier.clearAndSetSemantics {
            this.contentDescription = contentDescription
            stateDescription = stateText
            progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f)
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(modifier = Modifier.size(width = jarSize * JAR_ASPECT, height = jarSize)) {
            drawJar(animated, colors)
        }
        TierScaledText(
            text = countLabel,
            role = TierTextRole.Balance,
            tier = tier,
            textAlign = TextAlign.Center,
        )
    }
}

/** Defaults and helpers for [BalanceJar]. */
object BalanceJarDefaults {
    val JarSize: Dp = 160.dp

    /** Jar sizes a balance is shown against, smallest first. */
    private val capacities = longArrayOf(10, 20, 50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000)

    /**
     * How full the jar looks for [count] coins when there is no savings target: [count] over the smallest capacity
     * that holds it (10, 20, 50, ...). A count equal to a capacity fills the jar exactly (10 coins is full); one
     * more coin moves to the next size up, so the jar drops to just over half (11 of 20). Zero or less is empty;
     * counts above the largest capacity are full.
     */
    fun levelFor(count: Long): Float {
        val capacity = capacities.firstOrNull { it >= count }
        return when {
            count <= 0 -> 0f
            capacity == null -> 1f
            else -> count.toFloat() / capacity
        }
    }
}

/** Morning garden jar (M9 §2.7): white glass with an Ink outline, Honey coins on a Honey-tint fill, a linen lid. */
private class JarColors(
    val outline: Color,
    val glass: Color,
    val shine: Color,
    val fill: Color,
    val coin: Color,
    val lid: Color,
)

private const val PERCENT = 100
private const val JAR_ASPECT = 0.8f
private const val NECK_TOP = 0.08f
private const val BODY_TOP = 0.2f
private const val NECK_INSET = 0.22f
private const val BODY_CORNER = 0.18f
private const val COIN_COLUMNS = 4
private const val COIN_FILL_THRESHOLD = 0.3f
private const val COIN_SIZE = 0.9f
private const val COIN_ROW_STEP = 1.6f
private const val COIN_RING = 0.55f
private const val SHINE_X = 0.14f
private const val SHINE_TOP = 0.32f
private const val SHINE_BOTTOM = 0.7f

private fun DrawScope.drawJar(level: Float, colors: JarColors) {
    val stroke = 2.5.dp.toPx()
    val bodyTop = size.height * BODY_TOP
    val bodyHeight = size.height - bodyTop - stroke / 2
    val corner = CornerRadius(size.width * BODY_CORNER)
    val body = Path().apply {
        addRoundRect(RoundRect(stroke / 2, bodyTop, size.width - stroke / 2, bodyTop + bodyHeight, corner))
    }
    val fillTop = bodyTop + bodyHeight * (1f - level)

    drawPath(body, colors.glass)
    drawLine(
        color = colors.shine,
        start = Offset(size.width * SHINE_X, size.height * SHINE_TOP),
        end = Offset(size.width * SHINE_X, size.height * SHINE_BOTTOM),
        strokeWidth = stroke * 2,
        cap = StrokeCap.Round,
    )
    clipPath(body) {
        drawRect(colors.fill, topLeft = Offset(0f, fillTop), size = Size(size.width, size.height - fillTop))
        drawCoins(fillTop, bodyTop + bodyHeight, colors)
    }
    drawPath(body, colors.outline, style = Stroke(width = stroke))

    val neckLeft = size.width * NECK_INSET
    val neckSize = Size(size.width - 2 * neckLeft, bodyTop - size.height * NECK_TOP)
    drawRect(colors.glass, Offset(neckLeft, size.height * NECK_TOP), neckSize)
    drawRect(colors.outline, Offset(neckLeft, size.height * NECK_TOP), neckSize, style = Stroke(width = stroke))
    val lidTopLeft = Offset(neckLeft - stroke, stroke / 2)
    val lidSize = Size(neckSize.width + 2 * stroke, size.height * NECK_TOP)
    drawRoundRect(color = colors.lid, topLeft = lidTopLeft, size = lidSize, cornerRadius = CornerRadius(stroke))
    drawRoundRect(
        color = colors.outline,
        topLeft = lidTopLeft,
        size = lidSize,
        cornerRadius = CornerRadius(stroke),
        style = Stroke(width = stroke),
    )
}

/** Rows of Honey coins with Ink rims from the bottom of the jar up to the fill line. */
private fun DrawScope.drawCoins(fillTop: Float, bottom: Float, colors: JarColors) {
    val rim = Stroke(width = 1.5.dp.toPx())
    val radius = size.width / (COIN_COLUMNS * 2 + 1)
    var row = 0
    var centerY = bottom - radius
    while (centerY - radius * COIN_FILL_THRESHOLD >= fillTop) {
        val offset = if (row % 2 == 0) radius else radius * 2
        var centerX = offset
        while (centerX + radius <= size.width) {
            val center = Offset(centerX, centerY)
            drawCircle(colors.coin, radius * COIN_SIZE, center)
            drawCircle(colors.outline, radius * COIN_SIZE, center, style = rim)
            drawCircle(colors.outline, radius * COIN_RING, center, style = rim)
            centerX += radius * 2
        }
        centerY -= radius * COIN_ROW_STEP
        row++
    }
}

@Preview(name = "Seedling", showBackground = true)
@Composable
private fun BalanceJarSeedlingPreview() = TierPreviewFrame(KidUiTier.SEEDLING) {
    BalanceJar(level = 0.7f, countLabel = "7 coins", tier = KidUiTier.SEEDLING)
}

@Preview(name = "Sprout", showBackground = true)
@Composable
private fun BalanceJarSproutPreview() = TierPreviewFrame(KidUiTier.SPROUT) {
    BalanceJar(level = 0.35f, countLabel = "£7.45", tier = KidUiTier.SPROUT, jarSize = 120.dp)
}

@Preview(name = "Sapling", showBackground = true)
@Composable
private fun BalanceJarSaplingPreview() = TierPreviewFrame(KidUiTier.SAPLING) {
    BalanceJar(level = 0.9f, countLabel = "£45.00", tier = KidUiTier.SAPLING, jarSize = 96.dp)
}

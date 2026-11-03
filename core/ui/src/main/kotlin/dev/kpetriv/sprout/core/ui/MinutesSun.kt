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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Screen-time minutes as a sun whose rays light up like a clock face: each lit ray is
 * [maxMinutes] / 12 minutes. [label] ("45 min") sits underneath. One accessibility node with [contentDescription].
 */
@Composable
fun MinutesSun(
    minutes: Long,
    label: String,
    tier: KidUiTier,
    modifier: Modifier = Modifier,
    maxMinutes: Long = MinutesSunDefaults.MAX_MINUTES,
    contentDescription: String = label,
    sunSize: Dp = MinutesSunDefaults.SunSize,
) {
    require(maxMinutes > 0) { "maxMinutes must be positive" }
    val fraction = (minutes.coerceIn(0, maxMinutes).toFloat() / maxMinutes)
    val animated by animateFloatAsState(targetValue = fraction, label = "sunRays")
    val lit = MaterialTheme.colorScheme.secondary
    val unlit = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = modifier.clearAndSetSemantics {
            this.contentDescription = contentDescription
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(modifier = Modifier.size(sunSize)) { drawSun(animated, lit, unlit) }
        TierScaledText(text = label, role = TierTextRole.Title, tier = tier, textAlign = TextAlign.Center)
    }
}

/** Defaults for [MinutesSun]. */
object MinutesSunDefaults {
    /** Two hours fills every ray. */
    const val MAX_MINUTES: Long = 120
    val SunSize: Dp = 120.dp
}

private const val RAYS = 12
private const val CORE_RADIUS = 0.28f
private const val RAY_START = 0.36f
private const val RAY_END = 0.48f

private fun DrawScope.drawSun(fraction: Float, lit: Color, unlit: Color) {
    val center = Offset(size.width / 2, size.height / 2)
    val radius = size.minDimension
    val litRays = fraction * RAYS
    drawCircle(if (fraction > 0f) lit else unlit, radius * CORE_RADIUS, center)
    repeat(RAYS) { index ->
        // Start at 12 o'clock and go clockwise, like a clock hand.
        val angle = 2 * PI * index / RAYS - PI / 2
        val dx = cos(angle).toFloat()
        val dy = sin(angle).toFloat()
        drawLine(
            color = if (index < litRays) lit else unlit,
            start = Offset(center.x + dx * radius * RAY_START, center.y + dy * radius * RAY_START),
            end = Offset(center.x + dx * radius * RAY_END, center.y + dy * radius * RAY_END),
            strokeWidth = 6.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

@Preview(name = "Seedling", showBackground = true)
@Composable
private fun MinutesSunSeedlingPreview() = TierPreviewFrame(KidUiTier.SEEDLING) {
    MinutesSun(minutes = 45, label = "45 min", tier = KidUiTier.SEEDLING)
}

@Preview(name = "Sprout", showBackground = true)
@Composable
private fun MinutesSunSproutPreview() = TierPreviewFrame(KidUiTier.SPROUT) {
    MinutesSun(minutes = 80, label = "1 h 20 min", tier = KidUiTier.SPROUT, sunSize = 96.dp)
}

@Preview(name = "Sapling", showBackground = true)
@Composable
private fun MinutesSunSaplingPreview() = TierPreviewFrame(KidUiTier.SAPLING) {
    MinutesSun(minutes = 0, label = "0 min", tier = KidUiTier.SAPLING, sunSize = 72.dp)
}

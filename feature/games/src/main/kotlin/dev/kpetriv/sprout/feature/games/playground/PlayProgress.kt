@file:Suppress("MatchingDeclarationName") // Named after its composables; the defaults object sits beside them.

package dev.kpetriv.sprout.feature.games.playground

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.feature.games.R
import kotlin.math.ceil

/** Defaults for [SegmentProgress] and [CountdownRing]. */
internal object PlayProgressDefaults {
    /** The Chalk overlay of a segment still to come. */
    const val TO_COME_ALPHA: Float = 0.15f

    /** Gap between segments. */
    val SegmentGap: Dp = 6.dp

    /** The countdown ring (DESIGN-playground.md "Countdown ring"). */
    val RingSize: Dp = 48.dp
    val RingStroke: Dp = 4.dp

    const val MILLIS_PER_SECOND: Float = 1_000f
    const val FULL_TURN: Float = 360f
    const val TOP: Float = -90f
}

/**
 * The round progress (M9-games §2.2): one fat rounded segment per round, the ones before [round] in Lime and the rest
 * Chalk at 15 %, [PlaygroundTokens.segmentHeight] tall (14 dp, 8 dp on Sapling), then "3 of 8" in Outfit. Read as one
 * node, "Round 3 of 8", as today's progress row is.
 *
 * @param round the round being played, 1-based (the segments before it are done).
 * @param total the number of rounds.
 */
@Composable
internal fun SegmentProgress(round: Int, total: Int, modifier: Modifier = Modifier) {
    val colors = Playground.colors
    val height = Playground.tokens.segmentHeight
    val description = stringResource(R.string.feature_games_progress_description, round, total)
    val done = (round - 1).coerceIn(0, total)
    val toCome = colors.chalk.copy(alpha = PlayProgressDefaults.TO_COME_ALPHA)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(PlayProgressDefaults.SegmentGap),
        ) {
            repeat(total) { index ->
                val color = if (index < done) colors.lime else toCome
                Box(modifier = Modifier.weight(1f).height(height).background(color, CircleShape))
            }
        }
        Text(
            text = stringResource(R.string.feature_games_progress, round, total),
            style = PlaygroundType.figure,
            color = colors.chalk,
        )
    }
}

/**
 * The round's clock (M9-games §2.2, replacing `TimeBar`): a 48 dp Field-deep disc whose 4 dp Sky ring sweeps away
 * over [limitMs] from when [roundKey] changes, with the whole seconds left in Outfit inside. Informational only: a
 * slow answer loses the timed bonus, nothing else. Announced as "Time left" with its progress in whole seconds.
 */
@Composable
internal fun CountdownRing(roundKey: Long, limitMs: Long, modifier: Modifier = Modifier) {
    val left = remember(roundKey) { Animatable(1f) }
    LaunchedEffect(roundKey) {
        left.animateTo(0f, animationSpec = tween(limitMs.toInt(), easing = LinearEasing))
    }
    CountdownRingFrame(fractionLeft = { left.value }, limitMs = limitMs, modifier = modifier)
}

/** [CountdownRing] at a given point: [fractionLeft] of [limitMs] remains. */
@Composable
internal fun CountdownRingFrame(fractionLeft: () -> Float, limitMs: Long, modifier: Modifier = Modifier) {
    val colors = Playground.colors
    val description = stringResource(R.string.feature_games_time_left)
    val totalSeconds = ceil(limitMs / PlayProgressDefaults.MILLIS_PER_SECOND).toInt()
    // Recomposes once a second, not once a frame; the ring itself redraws in the draw phase. Keyed on the lambda, so a
    // new round's clock (a new lambda) starts a new count.
    val secondsLeft by remember(fractionLeft, limitMs) {
        derivedStateOf { ceil(fractionLeft() * limitMs / PlayProgressDefaults.MILLIS_PER_SECOND).toInt() }
    }
    // Whole seconds, read here so the semantics change once a second rather than every frame.
    val progress = ProgressBarRangeInfo(secondsLeft.toFloat(), 0f..totalSeconds.toFloat())
    Box(
        modifier = modifier
            .size(PlayProgressDefaults.RingSize)
            .clearAndSetSemantics {
                contentDescription = description
                progressBarRangeInfo = progress
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = PlayProgressDefaults.RingStroke.toPx()
            drawCircle(colors.fieldDeep)
            val inset = stroke / 2
            drawArc(
                color = colors.sky,
                startAngle = PlayProgressDefaults.TOP,
                sweepAngle = PlayProgressDefaults.FULL_TURN * fractionLeft(),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Text(text = "$secondsLeft", style = PlaygroundType.figure, color = colors.chalk)
    }
}

/** Two progress rows (Sprout's 8 rounds, Times tables' 20) and the ring at the start and part-way, for goldens. */
@Composable
internal fun SegmentProgressSample() {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SegmentProgress(round = 3, total = 8)
        SegmentProgress(round = 9, total = 20)
    }
}

/** The ring as a round starts (all 10 s left, the clock held) and with 4.5 s of 10 left. */
@Composable
internal fun CountdownRingSample() {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        CountdownRing(roundKey = 1L, limitMs = SAMPLE_LIMIT_MS)
        CountdownRingFrame(fractionLeft = { SAMPLE_FRACTION_LEFT }, limitMs = SAMPLE_LIMIT_MS)
    }
}

private const val SAMPLE_LIMIT_MS = 10_000L
private const val SAMPLE_FRACTION_LEFT = 0.45f

@Preview(name = "Segment progress", widthDp = 411)
@Composable
private fun SegmentProgressPreview(@PreviewParameter(KidTierPreviewProvider::class) tier: KidUiTier) =
    PlaygroundPreview(tier) { SegmentProgressSample() }

@Preview(name = "Countdown ring")
@Composable
private fun CountdownRingPreview(@PreviewParameter(KidTierPreviewProvider::class) tier: KidUiTier) =
    PlaygroundPreview(tier) { CountdownRingSample() }

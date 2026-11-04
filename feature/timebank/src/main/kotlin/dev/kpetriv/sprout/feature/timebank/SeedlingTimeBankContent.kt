package dev.kpetriv.sprout.feature.timebank

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.TierScaledText
import dev.kpetriv.sprout.core.ui.TierTextRole
import dev.kpetriv.sprout.core.ui.contentWidth
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Seedling (about 5 to 7): one big sun that is the Start/Stop button. A ring around it drains as the minutes go, the
 * middle shows the whole minutes left, and a line underneath says it in words. No clock, no history.
 */
@Composable
internal fun SeedlingTimeBankContent(
    state: TimeBankUiState.Ready,
    onEvent: (TimeBankEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tier = KidUiTier.SEEDLING
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).contentWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SunButton(state = state, onEvent = onEvent)
        TierScaledText(
            text = remainingText(state),
            role = TierTextRole.Title,
            tier = tier,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (state.isEmpty) {
            TierScaledText(
                text = stringResource(R.string.feature_timebank_empty_body),
                role = TierTextRole.Body,
                tier = tier,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SunButton(state: TimeBankUiState.Ready, onEvent: (TimeBankEvent) -> Unit, size: Dp = SunSize) {
    val fraction by animateFloatAsState(targetValue = state.ringFraction.coerceIn(0f, 1f), label = "sunRing")
    val colors = MaterialTheme.colorScheme
    val (core, onCore, ring) = when {
        state.running -> Triple(colors.primary, colors.onPrimary, colors.primary)
        state.isEmpty -> Triple(colors.surfaceVariant, colors.onSurfaceVariant, colors.outlineVariant)
        else -> Triple(colors.secondaryContainer, colors.onSecondaryContainer, colors.secondary)
    }
    val track = colors.outlineVariant
    val minutes = state.remainingMinutes
    val description = when {
        state.isEmpty -> stringResource(R.string.feature_timebank_sun_empty_described)

        state.running -> pluralStringResource(
            R.plurals.feature_timebank_sun_stop_described,
            minutes.toPluralCount(),
            minutes,
        )

        else -> pluralStringResource(R.plurals.feature_timebank_sun_start_described, minutes.toPluralCount(), minutes)
    }
    val enabled = if (state.running) state.canStop else state.canStart
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button) {
                onEvent(if (state.running) TimeBankEvent.Stop else TimeBankEvent.Start)
            }
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) { drawSun(fraction, core = core, ring = ring, track = track) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = minutes.toString(), style = MaterialTheme.typography.displayMedium, color = onCore)
            Text(
                text = pluralStringResource(R.plurals.feature_timebank_minutes_unit, minutes.toPluralCount()),
                style = MaterialTheme.typography.titleMedium,
                color = onCore,
            )
            if (!state.isEmpty) {
                Text(
                    text = stringResource(
                        if (state.running) R.string.feature_timebank_stop else R.string.feature_timebank_start,
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    color = onCore,
                )
            }
        }
    }
}

private val SunSize = 300.dp
private const val RAYS = 12
private const val RING_RADIUS = 0.46f
private const val RING_STROKE = 0.06f
private const val RAY_START = 0.33f
private const val RAY_END = 0.39f
private const val CORE_RADIUS = 0.29f
private const val FULL_TURN = 360f
private const val TOP = -90f

/** The ring (track plus the [fraction] still left, clockwise from 12 o'clock), the rays and the core. */
private fun DrawScope.drawSun(fraction: Float, core: Color, ring: Color, track: Color) {
    val center = Offset(size.width / 2, size.height / 2)
    val side = size.minDimension
    val ringRadius = side * RING_RADIUS
    val stroke = Stroke(width = side * RING_STROKE, cap = StrokeCap.Round)
    val topLeft = Offset(center.x - ringRadius, center.y - ringRadius)
    val arcSize = Size(ringRadius * 2, ringRadius * 2)
    drawArc(track, 0f, FULL_TURN, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
    if (fraction > 0f) {
        drawArc(ring, TOP, FULL_TURN * fraction, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
    }
    repeat(RAYS) { index ->
        val angle = 2 * PI * index / RAYS - PI / 2
        val dx = cos(angle).toFloat()
        val dy = sin(angle).toFloat()
        drawLine(
            color = core,
            start = Offset(center.x + dx * side * RAY_START, center.y + dy * side * RAY_START),
            end = Offset(center.x + dx * side * RAY_END, center.y + dy * side * RAY_END),
            strokeWidth = side * RING_STROKE / 2,
            cap = StrokeCap.Round,
        )
    }
    drawCircle(core, side * CORE_RADIUS, center)
}

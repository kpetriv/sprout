package dev.kpetriv.sprout.feature.timebank

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
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
import dev.kpetriv.sprout.core.designsystem.components.CardMeaning
import dev.kpetriv.sprout.core.designsystem.components.SproutCard
import dev.kpetriv.sprout.core.designsystem.components.accentTint
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.TierScaledText
import dev.kpetriv.sprout.core.ui.TierTextRole
import dev.kpetriv.sprout.core.ui.contentWidth
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Seedling (about 5 to 7), after MG-07: the minutes left in a Honey pill, then one big sun disc that is the Start/Stop
 * button. A Botanical ring on a Sky-tint track drains as the minutes go, the disc shows the whole minutes left in the
 * display face and says Start or Stop on a pill. Time up and an empty bank say so on a card. No clock, no history.
 */
@Composable
internal fun SeedlingTimeBankContent(
    state: TimeBankUiState.Ready,
    onEvent: (TimeBankEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tier = KidUiTier.SEEDLING
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .contentWidth()
            .padding(SproutSpacing.gutter),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.lg, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.isEmpty || state.timeUp) {
            // An attention card, not a pill: the kid has to do something (stop, or earn more minutes).
            SproutCard(meaning = CardMeaning.Attention, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
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
        } else {
            MinutesLeftPill(text = remainingText(state))
        }
        SunButton(state = state, onEvent = onEvent)
    }
}

/** The Honey pill above the sun: a sun icon and "33 minutes left", announced when it changes. */
@Composable
private fun MinutesLeftPill(text: String) {
    val tint = CardMeaning.Attention.accentTint()
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(tint.container)
            .border(BorderStroke(1.dp, SproutTheme.colors.honeyBorder), CircleShape)
            .padding(horizontal = SproutSpacing.lg, vertical = SproutSpacing.md)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm + SproutSpacing.xs),
    ) {
        Icon(imageVector = SproutIcons.WbSunny, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        TierScaledText(
            text = text,
            role = TierTextRole.Title,
            tier = KidUiTier.SEEDLING,
            color = tint.content,
            textAlign = TextAlign.Center,
        )
    }
}

/** The colours of the sun disc in one state. */
private data class SunColors(val core: Color, val onCore: Color, val pill: Color, val onPill: Color)

@Composable
private fun sunColors(state: TimeBankUiState.Ready): SunColors {
    val colors = MaterialTheme.colorScheme
    return when {
        state.isEmpty -> SunColors(
            colors.surfaceContainer,
            colors.onSurfaceVariant,
            Color.Transparent,
            Color.Unspecified,
        )

        // Time up: the Stop disc turns red, and the card above says why.
        state.running && state.timeUp -> SunColors(
            colors.error,
            colors.onError,
            colors.onError.copy(alpha = PILL_ALPHA),
            colors.onError,
        )

        // Running: the Stop pill in pressed Botanical, so its white word keeps about 9:1.
        state.running -> SunColors(
            colors.primary,
            colors.onPrimary,
            SproutTheme.colors.primaryPressed,
            colors.onPrimary,
        )

        // Idle: a Leaf-tint disc with a Botanical Start pill.
        else -> SunColors(colors.primaryContainer, colors.onPrimaryContainer, colors.primary, colors.onPrimary)
    }
}

private const val PILL_ALPHA = 0.18f

@Composable
private fun SunButton(state: TimeBankUiState.Ready, onEvent: (TimeBankEvent) -> Unit, size: Dp = SunSize) {
    val fraction by animateFloatAsState(targetValue = state.ringFraction.coerceIn(0f, 1f), label = "sunRing")
    val colors = MaterialTheme.colorScheme
    val sun = sunColors(state)
    val ring = colors.primary
    val track = colors.tertiaryContainer
    val ticks = SproutTheme.colors.skyStrong.copy(alpha = TICK_ALPHA)
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
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawSun(fraction, core = sun.core, ring = ring, track = track, ticks = ticks)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = minutes.toString(), style = MaterialTheme.typography.displayMedium, color = sun.onCore)
            Text(
                text = pluralStringResource(R.plurals.feature_timebank_minutes_unit, minutes.toPluralCount()),
                style = MaterialTheme.typography.titleMedium,
                color = sun.onCore,
            )
            if (!state.isEmpty) SunPill(running = state.running, colors = sun)
        }
    }
}

/** "Start" or "Stop" with its icon on a pill inside the disc (the disc itself is the button). */
@Composable
private fun SunPill(running: Boolean, colors: SunColors) {
    Row(
        modifier = Modifier
            .padding(top = SproutSpacing.sm)
            .clip(CircleShape)
            .background(colors.pill)
            .border(1.dp, colors.onPill.copy(alpha = PILL_ALPHA), CircleShape)
            .padding(horizontal = SproutSpacing.md, vertical = SproutSpacing.xs + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
    ) {
        Icon(
            painter = painterResource(
                if (running) R.drawable.feature_timebank_ic_stop_circle else R.drawable.feature_timebank_ic_play_arrow,
            ),
            contentDescription = null,
            tint = colors.onPill,
            modifier = Modifier.size(PillIconSize),
        )
        Text(
            text = stringResource(if (running) R.string.feature_timebank_stop else R.string.feature_timebank_start),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onPill,
        )
    }
}

private val SunSize = 320.dp
private val PillIconSize = 20.dp
private const val TICKS = 24
private const val TICK_ALPHA = 0.35f
private const val TICK_START = 0.46f
private const val TICK_END = 0.495f
private const val RING_RADIUS = 0.405f
private const val RING_STROKE = 0.075f
private const val CORE_RADIUS = 0.34f
private const val FULL_TURN = 360f
private const val TOP = -90f

/**
 * The ticks around the outside, the Sky-tint track with the Botanical [fraction] still left (clockwise from 12
 * o'clock), and the core disc.
 */
private fun DrawScope.drawSun(fraction: Float, core: Color, ring: Color, track: Color, ticks: Color) {
    val center = Offset(size.width / 2, size.height / 2)
    val side = size.minDimension
    repeat(TICKS) { index ->
        val angle = 2 * PI * index / TICKS - PI / 2
        val dx = cos(angle).toFloat()
        val dy = sin(angle).toFloat()
        drawLine(
            color = ticks,
            start = Offset(center.x + dx * side * TICK_START, center.y + dy * side * TICK_START),
            end = Offset(center.x + dx * side * TICK_END, center.y + dy * side * TICK_END),
            strokeWidth = side * RING_STROKE / TICK_WIDTH_DIVISOR,
            cap = StrokeCap.Round,
        )
    }
    val ringRadius = side * RING_RADIUS
    val stroke = Stroke(width = side * RING_STROKE, cap = StrokeCap.Round)
    val topLeft = Offset(center.x - ringRadius, center.y - ringRadius)
    val arcSize = Size(ringRadius * 2, ringRadius * 2)
    drawArc(track, 0f, FULL_TURN, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
    if (fraction > 0f) {
        drawArc(ring, TOP, FULL_TURN * fraction, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
    }
    drawCircle(core, side * CORE_RADIUS, center)
}

private const val TICK_WIDTH_DIVISOR = 4

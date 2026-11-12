@file:Suppress("MatchingDeclarationName") // Named after its composables; the defaults object sits beside them.

package dev.kpetriv.sprout.feature.games.playground

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.theme.Accent
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.feature.games.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Defaults for [RewardCard], [Rosette] and [StreakPill]. */
internal object RewardDefaults {
    /** The rosette (DESIGN-playground.md "Rosette"). */
    val RosetteSize: Dp = 120.dp
    const val PETALS: Int = 8

    /** The "Game reward" label's pill: the Lime edge at this opacity over Lime. */
    const val LABEL_PILL_ALPHA: Float = 0.5f

    val StreakBorder: Dp = 1.5.dp
    val StreakIconSize: Dp = 20.dp
}

/**
 * The result's reward card (M9-games §2.2): a Lime tile with the [label] ("Game reward"), the [amount] huge in Ink
 * and the [caption] (the cap line, "You can still earn £0.15 today") beneath. Seedling passes the minor-unit words
 * alone ("+8 pennies", D-M5-10) with `label = null` and no caption. Read as one node.
 */
@Composable
internal fun RewardCard(
    amount: String,
    modifier: Modifier = Modifier,
    label: String? = stringResource(R.string.feature_games_game_reward),
    caption: String? = null,
) {
    val colors = Playground.colors
    PlayTile(
        accent = Accent.Lime,
        onClick = null,
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.ink,
                    modifier = Modifier
                        .background(
                            colors.limeEdge.copy(alpha = RewardDefaults.LABEL_PILL_ALPHA).compositeOver(colors.lime),
                            CircleShape,
                        )
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            Text(text = amount, style = PlaygroundType.amount, color = colors.ink, textAlign = TextAlign.Center)
            if (caption != null) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.ink,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The result's medal (M9-games §2.2): for a [perfect] play a 120 dp Marigold flower of eight flat circles around a
 * disc with a white star; otherwise a plain Lime star. Decorative: the score line beside it says it in words.
 */
@Composable
internal fun Rosette(perfect: Boolean, modifier: Modifier = Modifier) {
    val colors = Playground.colors
    Canvas(modifier = modifier.size(RewardDefaults.RosetteSize).clearAndSetSemantics { }) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2
        if (perfect) {
            val petal = radius * PETAL_RADIUS
            val ring = radius - petal
            repeat(RewardDefaults.PETALS) { i ->
                val angle = 2 * PI * i / RewardDefaults.PETALS
                val petalCenter = center + Offset((ring * cos(angle)).toFloat(), (ring * sin(angle)).toFloat())
                drawCircle(colors.marigold, radius = petal, center = petalCenter)
            }
            drawCircle(colors.marigold, radius = ring, center = center)
            drawCircle(colors.marigoldEdge, radius = ring * RING_INSET, center = center, style = ringStroke(radius))
            drawPath(starPath(center, radius * PERFECT_STAR_OUTER, radius * PERFECT_STAR_INNER), colors.chalk)
        } else {
            drawPath(starPath(center, radius, radius * PLAIN_STAR_INNER), colors.lime)
        }
    }
}

private fun ringStroke(radius: Float) = Stroke(width = radius * RING_STROKE)

private fun starPath(center: Offset, outer: Float, inner: Float): Path = Path().apply {
    val points = STAR_POINTS * 2
    for (i in 0 until points) {
        val r = if (i % 2 == 0) outer else inner
        val angle = -PI / 2 + PI * i / STAR_POINTS
        val x = center.x + (r * cos(angle)).toFloat()
        val y = center.y + (r * sin(angle)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private const val STAR_POINTS = 5
private const val PETAL_RADIUS = 0.3f
private const val RING_INSET = 0.8f
private const val RING_STROKE = 0.04f
private const val PERFECT_STAR_OUTER = 0.36f
private const val PERFECT_STAR_INNER = 0.15f
private const val PLAIN_STAR_INNER = 0.45f

/**
 * The answer streak (M9-games §2.2): a Lime-outlined pill on the field panel with a flat flame and "Streak 3" in
 * Outfit, Lime on the panel. Read as its text.
 */
@Composable
internal fun StreakPill(count: Int, modifier: Modifier = Modifier) {
    val colors = Playground.colors
    Row(
        modifier = modifier
            .semantics(mergeDescendants = true) {}
            .background(colors.fieldPanel, CircleShape)
            .border(RewardDefaults.StreakBorder, colors.lime, CircleShape)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = FlameIcon,
            contentDescription = null,
            tint = colors.lime,
            modifier = Modifier.size(RewardDefaults.StreakIconSize),
        )
        Text(
            text = stringResource(R.string.feature_games_streak, count),
            style = PlaygroundType.figure,
            color = colors.lime,
        )
    }
}

/** A flat flame (24 dp, one path), tinted by `Icon`. */
private val FlameIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "PlaygroundFlame",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 2f)
        curveTo(13f, 6f, 19f, 9f, 19f, 14.5f)
        curveTo(19f, 18.6f, 15.9f, 22f, 12f, 22f)
        curveTo(8.1f, 22f, 5f, 18.6f, 5f, 14.5f)
        curveTo(5f, 11.5f, 6.6f, 9.3f, 8.4f, 7.8f)
        curveTo(8.4f, 10.2f, 9.4f, 12f, 11f, 12.6f)
        curveTo(10.4f, 9f, 11f, 5f, 12f, 2f)
        close()
    }.build()
}

/** The Sprout reward card with its cap line, the Seedling one (amount only), for goldens. */
@Composable
internal fun RewardCardSample() {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        RewardCard(amount = "+£0.07", caption = "You can still earn £0.15 today")
        RewardCard(amount = "+8 pennies", label = null)
    }
}

/** The perfect rosette and the plain star. */
@Composable
internal fun RosetteSample() {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Rosette(perfect = true)
        Rosette(perfect = false)
    }
}

@Composable
internal fun StreakPillSample() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StreakPill(count = 3)
        StreakPill(count = 12)
    }
}

@Preview(name = "Reward card", widthDp = 411)
@Composable
private fun RewardCardPreview(@PreviewParameter(KidTierPreviewProvider::class) tier: KidUiTier) =
    PlaygroundPreview(tier) { RewardCardSample() }

@Preview(name = "Rosette")
@Composable
private fun RosettePreview(@PreviewParameter(KidTierPreviewProvider::class) tier: KidUiTier) =
    PlaygroundPreview(tier) { RosetteSample() }

@Preview(name = "Streak pill")
@Composable
private fun StreakPillPreview(@PreviewParameter(KidTierPreviewProvider::class) tier: KidUiTier) =
    PlaygroundPreview(tier) { StreakPillSample() }

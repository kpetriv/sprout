package dev.kpetriv.sprout.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A Seedling-friendly savings goal: [title] ("Saving for a bike") over a row of
 * [SavingsStarsDefaults.STAR_COUNT] stars, [filled] of them coloured in. No numbers, no percentages.
 * Accessibility: one node reading [title] with "3 of 5 stars" as its state.
 */
@Composable
fun SavingsStars(
    title: String,
    filled: Int,
    tier: KidUiTier,
    modifier: Modifier = Modifier,
    starSize: Dp = SavingsStarsDefaults.StarSize,
) {
    val count = SavingsStarsDefaults.STAR_COUNT
    val lit = filled.coerceIn(0, count)
    val state = pluralStringResource(R.plurals.core_ui_savings_stars, count, lit, count)
    val litColor = MaterialTheme.colorScheme.secondary
    val emptyColor = MaterialTheme.colorScheme.outline
    Column(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = title
            stateDescription = state
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TierScaledText(text = title, role = TierTextRole.Title, tier = tier, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(count) { index ->
                Star(filled = index < lit, litColor = litColor, emptyColor = emptyColor, size = starSize)
            }
        }
    }
}

@Composable
private fun Star(filled: Boolean, litColor: Color, emptyColor: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val path = starPath(
            center = Offset(this.size.width / 2, this.size.height / 2),
            outer =
                this.size.minDimension / 2,
        )
        if (filled) {
            drawPath(path, litColor)
        } else {
            drawPath(path, emptyColor, style = Stroke(width = this.size.minDimension * STROKE_FRACTION))
        }
    }
}

/** A five-pointed star centred on [center], pointing up. */
private fun starPath(center: Offset, outer: Float): Path = Path().apply {
    val inner = outer * INNER_RADIUS
    for (point in 0 until STAR_POINTS * 2) {
        val radius = if (point % 2 == 0) outer * OUTER_INSET else inner
        val angle = -PI / 2 + point * PI / STAR_POINTS
        val x = center.x + (radius * cos(angle)).toFloat()
        val y = center.y + (radius * sin(angle)).toFloat()
        if (point == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** Defaults and helpers for [SavingsStars]. */
object SavingsStarsDefaults {
    /** Stars in the row. */
    const val STAR_COUNT: Int = 5

    val StarSize: Dp = 40.dp

    /**
     * Stars to fill for [saved] of [target] (minor units, integers only): whole fifths, rounded down, so the last
     * star lights only when the goal is reached. Zero or less saved is no stars; a non-positive [target] is none.
     */
    fun filledStars(saved: Long, target: Long): Int = when {
        target <= 0 || saved <= 0 -> 0

        saved >= target -> STAR_COUNT

        // saved < target here, so the product stays below STAR_COUNT * target; divide first if that could overflow.
        target > Long.MAX_VALUE / STAR_COUNT -> (saved / (target / STAR_COUNT)).toInt().coerceAtMost(STAR_COUNT - 1)

        else -> (saved * STAR_COUNT / target).toInt()
    }
}

private const val STAR_POINTS = 5
private const val INNER_RADIUS = 0.42f
private const val OUTER_INSET = 0.94f
private const val STROKE_FRACTION = 0.06f

@Preview(name = "Seedling", showBackground = true)
@Composable
private fun SavingsStarsSeedlingPreview() = TierPreviewFrame(KidUiTier.SEEDLING) {
    SavingsStars("Saving for a bike", filled = 2, tier = KidUiTier.SEEDLING)
}

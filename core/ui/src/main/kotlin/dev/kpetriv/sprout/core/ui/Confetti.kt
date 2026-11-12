package dev.kpetriv.sprout.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import dev.kpetriv.sprout.core.designsystem.theme.LocalReducedMotion

private const val PIECES = 36
private const val DURATION_MILLIS = 1_600
private const val SEED = 0x5EED

/** One confetti piece: start position (fractions of the width), fall speed, sway and spin, all fixed. */
private class Piece(val x: Float, val delay: Float, val speed: Float, val sway: Float, val spin: Float, val color: Int)

/** Defaults for [Confetti]. */
object ConfettiDefaults {
    /** The Morning garden three (M9 §2.5): Botanical, Honey and Sky. */
    @Composable
    @ReadOnlyComposable
    fun colors(): List<Color> = MaterialTheme.colorScheme.let { listOf(it.primary, it.secondary, it.tertiary) }
}

/**
 * A short confetti burst over the content in [colors], then [onFinished]. Finite and deterministic (pieces come from
 * a fixed seed), so it never runs forever and never makes a screenshot flaky. Decorative: hidden from accessibility.
 * Under reduced motion ([LocalReducedMotion]) nothing falls and [onFinished] runs straight away. Used for finished
 * chores and brain-game results (D-M5-8).
 */
@Suppress("MagicNumber") // Piece size and colour are proportions of the canvas, tuned by eye.
@Composable
fun Confetti(onFinished: () -> Unit, modifier: Modifier = Modifier, colors: List<Color> = ConfettiDefaults.colors()) {
    require(colors.isNotEmpty()) { "Confetti needs at least one colour" }
    val finished = rememberUpdatedState(onFinished)
    val progress = remember { Animatable(0f) }
    val reducedMotion = LocalReducedMotion.current
    val pieces = remember { pieces() }
    LaunchedEffect(Unit) {
        if (!reducedMotion) progress.animateTo(1f, animationSpec = tween(DURATION_MILLIS, easing = LinearEasing))
        finished.value()
    }
    if (reducedMotion) return
    Canvas(modifier = modifier.semantics { hideFromAccessibility() }) {
        val t = progress.value
        val pieceSize = Size(size.minDimension / 40f, size.minDimension / 24f)
        pieces.forEach { piece ->
            val local = ((t - piece.delay) / (1f - piece.delay)).coerceIn(0f, 1f)
            if (local <= 0f || local >= 1f) return@forEach
            val y = -pieceSize.height + local * piece.speed * (size.height + pieceSize.height * 2)
            val x = piece.x * size.width + kotlin.math.sin(local * piece.sway) * size.width / 20f
            rotate(degrees = local * piece.spin, pivot = Offset(x, y)) {
                drawRect(
                    color = colors[piece.color % colors.size],
                    topLeft = Offset(x - pieceSize.width / 2, y - pieceSize.height / 2),
                    size = pieceSize,
                )
            }
        }
    }
}

/** Fixed pseudo-random pieces (a linear congruential generator with [SEED]); no `Random`, so every run matches. */
@Suppress("MagicNumber") // LCG constants (glibc) and the ranges of each piece's motion.
private fun pieces(): List<Piece> {
    var state = SEED.toLong()
    fun next(): Float {
        state = (state * 1_103_515_245L + 12_345L) and 0x7FFF_FFFFL
        return state.toFloat() / 0x7FFF_FFFF.toFloat()
    }
    return List(PIECES) { index ->
        Piece(
            x = next(),
            delay = next() * 0.3f,
            speed = 0.8f + next() * 0.4f,
            sway = 4f + next() * 8f,
            spin = 180f + next() * 540f,
            color = index,
        )
    }
}

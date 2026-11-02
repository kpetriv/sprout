package dev.kpetriv.sprout.core.designsystem.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TierShapesTest {
    private val density = Density(1f)
    private val bigEnough = Size(10_000f, 10_000f)

    private fun CornerBasedShape.radius(): Float = topStart.toPx(bigEnough, density)

    @Test
    fun shapeScaleIsStrictlyIncreasingForEveryTier() {
        KidUiTier.entries.forEach { tier ->
            val shapes = TierTokens.forTier(tier).toShapes()
            val radii = listOf(shapes.extraSmall, shapes.small, shapes.medium, shapes.large, shapes.extraLarge)
                .map { it.radius() }
            radii.zipWithNext { smaller, larger -> assertTrue(smaller < larger, "$tier: $radii") }
        }
    }

    @Test
    fun mediumShapeUsesTheTierCornerRadius() {
        KidUiTier.entries.forEach { tier ->
            val tokens = TierTokens.forTier(tier)
            assertEquals(tokens.cornerRadius.value, tokens.toShapes().medium.radius())
        }
    }
}

package dev.kpetriv.sprout.core.designsystem.theme

import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class TierViewConfigurationTest {
    private val platform = object : ViewConfiguration {
        override val longPressTimeoutMillis = 400L
        override val doubleTapTimeoutMillis = 300L
        override val doubleTapMinTimeMillis = 40L
        override val touchSlop = 8f
        override val minimumTouchTargetSize = DpSize(48.dp, 48.dp)
    }

    @Test
    fun `kid tiers raise the minimum touch target and keep everything else`() {
        val seedling = platform.withMinimumTouchTarget(TierTokens.Seedling.minTouchTarget)

        assertEquals(DpSize(72.dp, 72.dp), seedling.minimumTouchTargetSize)
        assertEquals(platform.touchSlop, seedling.touchSlop)
        assertEquals(platform.longPressTimeoutMillis, seedling.longPressTimeoutMillis)
        assertEquals(DpSize(56.dp, 56.dp), platform.withMinimumTouchTarget(56.dp).minimumTouchTargetSize)
    }

    @Test
    fun `Sapling leaves the platform configuration alone`() {
        assertSame(platform, platform.withMinimumTouchTarget(TierTokens.Sapling.minTouchTarget))
    }
}

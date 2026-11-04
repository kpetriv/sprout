package dev.kpetriv.sprout.core.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdaptiveTest {
    @Test
    fun `width classes follow the Material breakpoints`() {
        assertEquals(WindowWidthClass.Compact, WindowWidthClass.fromWidth(411.dp))
        assertEquals(WindowWidthClass.Compact, WindowWidthClass.fromWidth(599.dp))
        assertEquals(WindowWidthClass.Medium, WindowWidthClass.fromWidth(600.dp))
        assertEquals(WindowWidthClass.Medium, WindowWidthClass.fromWidth(839.dp))
        assertEquals(WindowWidthClass.Expanded, WindowWidthClass.fromWidth(840.dp))
        assertEquals(WindowWidthClass.Expanded, WindowWidthClass.fromWidth(1280.dp))
    }

    @Test
    fun `only Compact is narrower than Medium`() {
        assertEquals(listOf(false, true, true), WindowWidthClass.entries.map { it.isAtLeastMedium })
    }

    @Test
    fun `column count fits as many minimum-width columns as the width allows`() {
        // Phone shop: 379 dp of content, 160 dp tiles, 12 dp gaps -> 2, as the fixed phone grid had.
        assertEquals(2, adaptiveColumnCount(379.dp, 160.dp, spacing = 12.dp))
        // Tablet: 1248 dp -> 7 tiles of at least 160 dp.
        assertEquals(7, adaptiveColumnCount(1248.dp, 160.dp, spacing = 12.dp))
        // Exactly fitting: 3 x 100 + 2 x 10 = 320.
        assertEquals(3, adaptiveColumnCount(320.dp, 100.dp, spacing = 10.dp))
        assertEquals(2, adaptiveColumnCount(319.dp, 100.dp, spacing = 10.dp))
    }

    @Test
    fun `column count stays within its bounds`() {
        assertEquals(2, adaptiveColumnCount(100.dp, 160.dp, minColumns = 2))
        assertEquals(4, adaptiveColumnCount(2000.dp, 160.dp, maxColumns = 4))
        assertFailsWith<IllegalArgumentException> { adaptiveColumnCount(100.dp, 0.dp) }
        assertFailsWith<IllegalArgumentException> { adaptiveColumnCount(100.dp, 10.dp, minColumns = 3, maxColumns = 2) }
    }

    @Test
    fun `adaptive grid cells split the width evenly and keep every pixel`() {
        val cells = AdaptiveColumns(minColumnWidth = 100.dp, minColumns = 2)
        val sizes = with(cells) { Density(1f).calculateCrossAxisCellSizes(availableSize = 333, spacing = 10) }

        assertEquals(listOf(105, 104, 104), sizes)
        assertEquals(333 - 2 * 10, sizes.sum())
        assertEquals(AdaptiveColumns(100.dp, minColumns = 2), cells)
    }
}

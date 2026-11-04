package dev.kpetriv.sprout.core.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

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

    @Test
    fun `content width gutters centre a capped column in whole pixels`() {
        val mdpi = Density(1f)
        // Phone: narrower than the cap, no gutters.
        assertEquals(0.dp, mdpi.contentWidthGutters(411).calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(0.dp, mdpi.contentWidthGutters(640).calculateRightPadding(LayoutDirection.Ltr))
        // Tablet: 1280 dp leaves 320 dp each side.
        val tablet = mdpi.contentWidthGutters(1280)
        assertEquals(320.dp, tablet.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(320.dp, tablet.calculateRightPadding(LayoutDirection.Rtl))
        // An odd spare pixel goes left, as Alignment.CenterHorizontally rounds it.
        val odd = mdpi.contentWidthGutters(641)
        assertEquals(0.dp, odd.calculateRightPadding(LayoutDirection.Ltr))
        assertEquals(1.dp, odd.calculateLeftPadding(LayoutDirection.Rtl))
        // xxhdpi: 1080 px is 360 dp; a 2560 px window leaves (2560 - 1920) / 2 px each side.
        val xxhdpi = Density(3f)
        assertEquals(0.dp, xxhdpi.contentWidthGutters(1080).calculateLeftPadding(LayoutDirection.Ltr))
        with(xxhdpi) {
            assertEquals(320, contentWidthGutters(2560).calculateLeftPadding(LayoutDirection.Ltr).roundToPx())
        }
        // A custom cap, and an unbounded width.
        assertEquals(140.dp, mdpi.contentWidthGutters(1000, max = 720.dp).calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(0.dp, mdpi.contentWidthGutters(Constraints.Infinity).calculateLeftPadding(LayoutDirection.Ltr))
    }

    @Test
    fun `gutters add to a list's own content padding`() {
        val padded = PaddingValues(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 96.dp)
            .withGutters(Density(1f).contentWidthGutters(1281))

        assertEquals(16.dp + 321.dp, padded.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(4.dp + 320.dp, padded.calculateRightPadding(LayoutDirection.Ltr))
        assertEquals(4.dp + 321.dp, padded.calculateLeftPadding(LayoutDirection.Rtl))
        assertEquals(8.dp, padded.calculateTopPadding())
        assertEquals(96.dp, padded.calculateBottomPadding())
        // No gutters (a phone): the list's padding as it was.
        val phone = PaddingValues(16.dp)
        assertSame(phone, phone.withGutters(Density(1f).contentWidthGutters(411)))
    }
}

package dev.kpetriv.sprout.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlaygroundTokensTest {
    private val colors = PlaygroundColors.Default

    @Test
    fun eachTierMapsToItsTokens() {
        assertEquals(PlaygroundTokens.Seedling, PlaygroundTokens.forTier(KidUiTier.SEEDLING))
        assertEquals(PlaygroundTokens.Sprout, PlaygroundTokens.forTier(KidUiTier.SPROUT))
        assertEquals(PlaygroundTokens.Sapling, PlaygroundTokens.forTier(KidUiTier.SAPLING))
    }

    @Test
    fun valuesAreTheDesignFilesSizes() {
        val tokens = KidUiTier.entries.map(PlaygroundTokens::forTier)
        assertEquals(listOf(24.dp, 20.dp, 16.dp), tokens.map { it.tileCorner })
        assertEquals(listOf(4.dp, 4.dp, 3.dp), tokens.map { it.tileEdge })
        assertEquals(listOf(112.dp, 72.dp, 56.dp), tokens.map { it.tileHeight })
        assertEquals(listOf(14.dp, 14.dp, 8.dp), tokens.map { it.segmentHeight })
        assertEquals(listOf(AccentLevel.FULL, AccentLevel.FULL, AccentLevel.QUIET), tokens.map { it.accents })
    }

    @Test
    fun youngerTiersGetLargerValues() {
        val ordered = KidUiTier.entries.map(PlaygroundTokens::forTier) // youngest first
        ordered.zipWithNext { younger, older ->
            assertTrue(younger.tileCorner > older.tileCorner)
            assertTrue(younger.tileHeight > older.tileHeight)
            assertTrue(younger.tileEdge >= older.tileEdge)
            assertTrue(younger.segmentHeight >= older.segmentHeight)
            // AccentLevel is declared from most to least colour: younger tiers never get fewer accents.
            assertTrue(younger.accents <= older.accents)
        }
    }

    @Test
    fun quietNeverReturnsCoralOrMarigoldExceptForPrimary() {
        val quiet = PlaygroundTokens.Sapling
        val coral = setOf(colors.coral, colors.coralEdge)
        Accent.entries.forEach { accent ->
            val resolved = quiet.resolve(accent, colors)
            assertFalse(resolved.fill in coral || resolved.edge in coral, "$accent resolved to Coral")
            if (accent !=
                Accent.Primary
            ) {
                assertNotEquals(colors.marigold, resolved.fill, "$accent resolved to Marigold")
            }
        }
        assertEquals(colors.lime, quiet.resolve(Accent.Marigold, colors).fill)
        assertEquals(colors.sky, quiet.resolve(Accent.Coral, colors).fill)
    }

    @Test
    fun fullKeepsEveryAccent() {
        val full = PlaygroundTokens.Sprout
        assertEquals(colors.lime, full.resolve(Accent.Lime, colors).fill)
        assertEquals(colors.marigold, full.resolve(Accent.Marigold, colors).fill)
        assertEquals(colors.sky, full.resolve(Accent.Sky, colors).fill)
        assertEquals(colors.coral, full.resolve(Accent.Coral, colors).fill)
        assertEquals(colors.coralEdge, full.resolve(Accent.Coral, colors).edge)
    }

    @Test
    fun primaryIsMarigoldAtEveryLevel() {
        KidUiTier.entries.map(PlaygroundTokens::forTier).forEach { tokens ->
            val primary = tokens.resolve(Accent.Primary, colors)
            assertEquals(colors.marigold, primary.fill)
            assertEquals(colors.marigoldEdge, primary.edge)
            assertEquals(colors.ink, primary.content)
        }
    }

    @Test
    fun panelIsTheFieldPanelWithAFieldDeepEdgeAtEveryLevel() {
        KidUiTier.entries.map(PlaygroundTokens::forTier).forEach { tokens ->
            assertEquals(
                PlaygroundAccent(fill = colors.fieldPanel, edge = colors.fieldDeep, content = colors.chalk),
                tokens.resolve(Accent.Panel, colors),
            )
        }
    }

    @Test
    fun captionIsOpaque() {
        assertEquals(1f, colors.caption.alpha)
    }

    @Test
    fun outlineHasNoFillNoEdgeAndChalkContent() {
        val outline = PlaygroundTokens.Seedling.resolve(Accent.Outline, colors)
        assertTrue(outline.isOutline)
        assertEquals(Color.Transparent, outline.edge)
        assertEquals(colors.chalk, outline.content)
    }

    @Test
    fun filledAccentsCarryInk() {
        listOf(Accent.Lime, Accent.Marigold, Accent.Sky, Accent.Coral).forEach { accent ->
            assertEquals(colors.ink, PlaygroundTokens.Sprout.resolve(accent, colors).content)
        }
    }

    @Test
    fun textPairsOfTheDesignFileMeetWcagAa() {
        val pairs = mapOf(
            "Ink on Lime" to (colors.ink to colors.lime),
            "Ink on Marigold" to (colors.ink to colors.marigold),
            "Ink on Sky" to (colors.ink to colors.sky),
            "Ink on Coral" to (colors.ink to colors.coral),
            "Chalk on Field" to (colors.chalk to colors.field),
            "Chalk on Field panel" to (colors.chalk to colors.fieldPanel),
            "Chalk soft on Field" to (colors.chalkSoft.compositeOver(colors.field) to colors.field),
            "Lime on Field" to (colors.lime to colors.field),
            "Sky on Field" to (colors.sky to colors.field),
            // Pairs PlaygroundTheme's colour scheme and the components create.
            "Caption on Field panel (onSurfaceVariant on surfaceVariant)" to (colors.caption to colors.fieldPanel),
            "Caption on Field" to (colors.caption to colors.field),
            "Lime on Field panel (streak pill)" to (colors.lime to colors.fieldPanel),
            "Chalk on Field deep (countdown ring, badges)" to (colors.chalk to colors.fieldDeep),
            "Chalk on Field panel (Panel tiles)" to (colors.chalk to colors.fieldPanel),
            "Lime on Field panel tile (quiet backspace)" to (colors.lime to colors.fieldPanel),
        )
        pairs.forEach { (label, pair) ->
            val ratio = contrast(pair.first, pair.second)
            assertTrue(ratio >= AA, "$label contrast is $ratio")
        }
    }

    private fun contrast(a: Color, b: Color): Float {
        val (lighter, darker) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (lighter + FLARE) / (darker + FLARE)
    }

    private companion object {
        const val AA = 4.5f
        const val FLARE = 0.05f
    }
}

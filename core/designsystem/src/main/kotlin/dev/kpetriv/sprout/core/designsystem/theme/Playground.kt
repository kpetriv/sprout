package dev.kpetriv.sprout.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.model.KidUiTier

/*
 * "Playground", the games-only character (M9-games §1 and §2.1, D-M9-G1, D-M9-G3): the values of
 * .stitch/DESIGN-playground.md. The theme wrapper and the components live in feature/games (`PlaygroundTheme`);
 * the colours live here because core/ui's Confetti and the games hub's pictograms use them too.
 */

/** The Playground palette: the deep green play field, Chalk and Ink, and four flat accents with their edge colours. */
@Immutable
data class PlaygroundColors(
    /** The page: Morning garden's pressed Botanical, full bleed. */
    val field: Color,
    /** Inside rings and the top bar tiles. */
    val fieldDeep: Color,
    /** Sequence panels, answer boxes, history rows. */
    val fieldPanel: Color,
    /** Text and outlines on the field. */
    val chalk: Color,
    /** Captions on the field (white at 60 %). */
    val chalkSoft: Color,
    /** Text on a coloured tile. */
    val ink: Color,
    val lime: Color,
    val limeEdge: Color,
    val marigold: Color,
    val marigoldEdge: Color,
    val sky: Color,
    val skyEdge: Color,
    val coral: Color,
    val coralEdge: Color,
) {
    /**
     * Captions on the field and on panels: Chalk at [CAPTION_ALPHA] over the field panel, opaque. 4.5:1 or better on
     * the panel and the field, where Chalk soft (60 %) falls short on the panel.
     */
    val caption: Color get() = chalk.copy(alpha = CAPTION_ALPHA).compositeOver(fieldPanel)

    companion object {
        /** The opacity of [caption]'s Chalk. */
        const val CAPTION_ALPHA: Float = 0.85f

        /** The one Playground palette (there is no dark variant: the field is already dark, M9-games §9). */
        val Default = PlaygroundColors(
            field = Color(0xFF1E4B33),
            fieldDeep = Color(0xFF133322),
            fieldPanel = Color(0xFF27583F),
            chalk = Color(0xFFFFFFFF),
            chalkSoft = Color(0x99FFFFFF),
            ink = Color(0xFF1A1C19),
            lime = Color(0xFFB8E986),
            limeEdge = Color(0xFF8BC950),
            marigold = Color(0xFFF5B52A),
            marigoldEdge = Color(0xFFC48A14),
            sky = Color(0xFF7CC4EE),
            skyEdge = Color(0xFF4A98C7),
            coral = Color(0xFFFF8A65),
            coralEdge = Color(0xFFD1603E),
        )
    }
}

/** How many of the four accents a tier may use (D-M9-G2). */
enum class AccentLevel {
    /** Seedling and Sprout: Lime, Marigold, Sky and Coral. */
    FULL,

    /** Sapling, the quiet Playground: Lime and Sky only; Marigold for the one primary action; no Coral. */
    QUIET,
}

/** What a tile asks to be filled with; [PlaygroundTokens.resolve] turns it into colours for the tier. */
enum class Accent {
    Lime,
    Marigold,
    Sky,
    Coral,

    /** No fill and no edge: a 2 dp Chalk-60 % border on the field. */
    Outline,

    /** A Field-panel tile with a Field-deep edge and Chalk content (the quiet Sapling number pad). */
    Panel,

    /** The single primary action of a screen ("Start", "Go", "Check", "Done"): Marigold at every level. */
    Primary,
}

/**
 * The colours of a resolved [Accent]: [fill] (transparent for an outline), the solid bottom [edge] (transparent when
 * there is none) and the [content] colour of text and icons on it (Ink on an accent, Chalk on a panel or an outline).
 */
@Immutable
data class PlaygroundAccent(val fill: Color, val edge: Color, val content: Color) {
    /** True for [Accent.Outline]: no fill, no edge, a border instead. */
    val isOutline: Boolean get() = fill == Color.Transparent
}

/** Sizes and the accent dial of the Playground per tier (M9-games §2.1 and §4). Read next to [TierTokens]. */
@Immutable
data class PlaygroundTokens(
    /** Corner radius of a play tile. */
    val tileCorner: Dp,
    /** Height of a tile's solid bottom edge (how far it travels when pressed). */
    val tileEdge: Dp,
    /** Height of an answer tile. */
    val tileHeight: Dp,
    /** Height of a progress segment. */
    val segmentHeight: Dp,
    /** Which accents the tier may use. */
    val accents: AccentLevel,
) {
    /**
     * The colours of [accent] at this tier's [accents] level: under [AccentLevel.QUIET] Marigold resolves to Lime and
     * Coral to Sky; [Accent.Primary] is Marigold at every level.
     */
    fun resolve(accent: Accent, colors: PlaygroundColors = PlaygroundColors.Default): PlaygroundAccent {
        val quiet = accents == AccentLevel.QUIET
        return when (accent) {
            Accent.Lime -> colors.filled(colors.lime, colors.limeEdge)

            Accent.Marigold -> if (quiet) colors.filled(colors.lime, colors.limeEdge) else colors.marigoldFilled()

            Accent.Sky -> colors.filled(colors.sky, colors.skyEdge)

            Accent.Coral -> if (quiet) {
                colors.filled(colors.sky, colors.skyEdge)
            } else {
                colors.filled(colors.coral, colors.coralEdge)
            }

            Accent.Panel -> PlaygroundAccent(fill = colors.fieldPanel, edge = colors.fieldDeep, content = colors.chalk)

            Accent.Primary -> colors.marigoldFilled()

            Accent.Outline -> PlaygroundAccent(
                fill = Color.Transparent,
                edge = Color.Transparent,
                content = colors.chalk,
            )
        }
    }

    companion object {
        val Seedling = PlaygroundTokens(
            tileCorner = 24.dp,
            tileEdge = 4.dp,
            tileHeight = 112.dp,
            segmentHeight = 14.dp,
            accents = AccentLevel.FULL,
        )
        val Sprout = PlaygroundTokens(
            tileCorner = 20.dp,
            tileEdge = 4.dp,
            tileHeight = 72.dp,
            segmentHeight = 14.dp,
            accents = AccentLevel.FULL,
        )
        val Sapling = PlaygroundTokens(
            tileCorner = 16.dp,
            tileEdge = 3.dp,
            tileHeight = 56.dp,
            segmentHeight = 8.dp,
            accents = AccentLevel.QUIET,
        )

        fun forTier(tier: KidUiTier): PlaygroundTokens = when (tier) {
            KidUiTier.SEEDLING -> Seedling
            KidUiTier.SPROUT -> Sprout
            KidUiTier.SAPLING -> Sapling
        }
    }
}

private fun PlaygroundColors.filled(fill: Color, edge: Color) =
    PlaygroundAccent(fill = fill, edge = edge, content = ink)

private fun PlaygroundColors.marigoldFilled() = filled(marigold, marigoldEdge)

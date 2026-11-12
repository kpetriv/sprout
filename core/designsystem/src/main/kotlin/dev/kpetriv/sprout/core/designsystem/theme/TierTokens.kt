package dev.kpetriv.sprout.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.model.KidUiTier

/**
 * How much of the Morning garden palette a screen may use (M9 §2.6, D-M9-3). Components read it from
 * [TierTokens.colourLevel] instead of switching on the tier. Declared from most to least colour.
 */
enum class ColourLevel {
    /** Seedling: big blocks of Leaf, Honey and Sky tint, pictorial components, giant pills. */
    FULL,

    /** Sprout: tints only where they carry meaning (money hero chip, screen-time badge, goal icon). */
    ACCENTED,

    /** Sapling and the parent side: cream and white, Botanical as the only accent. */
    RESTRAINED,
}

/**
 * Size and colour tokens that scale with the kid's [KidUiTier]: younger kids get bigger text, bigger touch
 * targets, rounder shapes and more colour. Parent screens use [Parent] (standard Material sizes, Sapling's colour).
 */
@Immutable
data class TierTokens(
    /** Multiplier applied to every Material type style. */
    val typeScale: Float,
    /** Minimum size of anything tappable. Never below Material's 48dp. */
    val minTouchTarget: Dp,
    /** Corner radius of medium components (cards, tiles). */
    val cornerRadius: Dp,
    /** How much colour components may use. */
    val colourLevel: ColourLevel,
    /** Height of a `SproutButton` pill: 72 / 64 / 56 dp on the kid tiers, 48 dp on parent screens (M9 §3). */
    val buttonHeight: Dp,
) {
    companion object {
        val Seedling = TierTokens(
            typeScale = 1.3f,
            minTouchTarget = 72.dp,
            cornerRadius = 28.dp,
            colourLevel = ColourLevel.FULL,
            buttonHeight = 72.dp,
        )
        val Sprout = TierTokens(
            typeScale = 1.15f,
            minTouchTarget = 56.dp,
            cornerRadius = 24.dp,
            colourLevel = ColourLevel.ACCENTED,
            buttonHeight = 64.dp,
        )
        val Sapling = TierTokens(
            typeScale = 1f,
            minTouchTarget = 48.dp,
            cornerRadius = 18.dp,
            colourLevel = ColourLevel.RESTRAINED,
            buttonHeight = 56.dp,
        )

        /** Parent screens: Sapling's sizes and colour level with Material's 48 dp buttons. */
        val Parent = Sapling.copy(buttonHeight = 48.dp)

        fun forTier(tier: KidUiTier): TierTokens = when (tier) {
            KidUiTier.SEEDLING -> Seedling
            KidUiTier.SPROUT -> Sprout
            KidUiTier.SAPLING -> Sapling
        }
    }
}

/**
 * The tier of the kid using the current screen. Kid-mode screens switch their content on it.
 * Provided only by [ProvideKidTier]; there is deliberately no default, so a kid screen rendered outside
 * kid mode fails fast instead of silently showing the oldest tier. Parent screens never read it.
 */
val LocalKidTier = staticCompositionLocalOf<KidUiTier> {
    error("No KidUiTier provided; wrap kid-mode UI in ProvideKidTier")
}

/** Tokens in effect: [TierTokens.Parent] under [SproutTheme], the kid's tier under [ProvideKidTier]. */
val LocalTierTokens = staticCompositionLocalOf { TierTokens.Parent }

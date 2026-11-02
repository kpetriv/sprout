package dev.kpetriv.sprout.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.model.KidUiTier

/**
 * Size tokens that scale with the kid's [KidUiTier]: younger kids get bigger text, bigger touch
 * targets and rounder shapes. Parent screens use [Sapling] (standard Material sizes).
 */
@Immutable
data class TierTokens(
    /** Multiplier applied to every Material type style. */
    val typeScale: Float,
    /** Minimum size of anything tappable. Never below Material's 48dp. */
    val minTouchTarget: Dp,
    /** Corner radius of medium components (cards, buttons). */
    val cornerRadius: Dp,
) {
    companion object {
        val Seedling = TierTokens(typeScale = 1.3f, minTouchTarget = 72.dp, cornerRadius = 28.dp)
        val Sprout = TierTokens(typeScale = 1.15f, minTouchTarget = 56.dp, cornerRadius = 20.dp)
        val Sapling = TierTokens(typeScale = 1f, minTouchTarget = 48.dp, cornerRadius = 12.dp)

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

/** Size tokens in effect: [TierTokens.Sapling] under [SproutTheme], the kid's tier under [ProvideKidTier]. */
val LocalTierTokens = staticCompositionLocalOf { TierTokens.Sapling }

package dev.kpetriv.sprout.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import dev.kpetriv.sprout.core.model.KidUiTier

// Corner radius of each Material shape slot relative to TierTokens.cornerRadius (the medium slot).
// Strictly increasing, so the shape scale stays monotonic for every tier.
private const val EXTRA_SMALL_SHAPE_FACTOR = 0.25f
private const val SMALL_SHAPE_FACTOR = 0.5f
private const val LARGE_SHAPE_FACTOR = 1.5f
private const val EXTRA_LARGE_SHAPE_FACTOR = 2f

/**
 * Sprout's Material 3 theme ("Morning garden", M9) with standard (parent) sizing: [TierTokens.Parent], no kid tier.
 * Kid-mode UI additionally wraps itself in [ProvideKidTier].
 *
 * @param dynamicColor use the wallpaper palette on Android 12+. Off by default so the brand colours stay.
 * @param reducedMotion provided as [LocalReducedMotion]; the system's "Remove animations" setting by default.
 */
@Composable
fun SproutTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    reducedMotion: Boolean = rememberSystemReducedMotion(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> SproutDarkColors

        else -> SproutLightColors
    }
    CompositionLocalProvider(
        LocalSproutColors provides if (darkTheme) SproutColors.Dark else SproutColors.Light,
        LocalReducedMotion provides reducedMotion,
    ) {
        TierScaledMaterialTheme(colorScheme = colorScheme, tokens = TierTokens.Parent, content = content)
    }
}

/**
 * Enters kid mode for [content]: provides [LocalKidTier] and rescales type, shapes and minimum touch
 * targets to the tier's [TierTokens] (including its [ColourLevel]). Use inside [SproutTheme]; colours are inherited
 * from it.
 */
@Composable
fun ProvideKidTier(tier: KidUiTier, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalKidTier provides tier) {
        TierScaledMaterialTheme(
            colorScheme = MaterialTheme.colorScheme,
            tokens = TierTokens.forTier(tier),
            content = content,
        )
    }
}

@Composable
private fun TierScaledMaterialTheme(colorScheme: ColorScheme, tokens: TierTokens, content: @Composable () -> Unit) {
    val typography = remember(tokens) { SproutTypography.scaledBy(tokens.typeScale) }
    val shapes = remember(tokens) { tokens.toShapes() }

    val viewConfiguration = LocalViewConfiguration.current
    val tierViewConfiguration = remember(viewConfiguration, tokens) {
        viewConfiguration.withMinimumTouchTarget(tokens.minTouchTarget)
    }
    CompositionLocalProvider(LocalTierTokens provides tokens) {
        MaterialTheme(colorScheme = colorScheme, typography = typography, shapes = shapes) {
            // Inside MaterialTheme so nothing it provides can override the tier's touch target. Material components
            // reserve the tier's size through LocalMinimumInteractiveComponentSize; the view configuration makes
            // Compose's hit testing extend every smaller pointer target (custom clickables included) to it (M7 §8).
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentSize provides tokens.minTouchTarget,
                LocalViewConfiguration provides tierViewConfiguration,
                content = content,
            )
        }
    }
}

/**
 * This configuration with a minimum touch target of at least [minTouchTarget] square. Never shrinks it: on Sapling
 * (48 dp, the platform default) the configuration is returned unchanged.
 */
internal fun ViewConfiguration.withMinimumTouchTarget(minTouchTarget: Dp): ViewConfiguration {
    val current = minimumTouchTargetSize
    if (current.width >= minTouchTarget && current.height >= minTouchTarget) return this
    val size = DpSize(maxOf(current.width, minTouchTarget), maxOf(current.height, minTouchTarget))
    val base = this
    return object : ViewConfiguration by base {
        override val minimumTouchTargetSize: DpSize = size
    }
}

/** Material's five shape slots scaled from the tier's [TierTokens.cornerRadius]. */
internal fun TierTokens.toShapes(): Shapes = Shapes(
    extraSmall = RoundedCornerShape(cornerRadius * EXTRA_SMALL_SHAPE_FACTOR),
    small = RoundedCornerShape(cornerRadius * SMALL_SHAPE_FACTOR),
    medium = RoundedCornerShape(cornerRadius),
    large = RoundedCornerShape(cornerRadius * LARGE_SHAPE_FACTOR),
    extraLarge = RoundedCornerShape(cornerRadius * EXTRA_LARGE_SHAPE_FACTOR),
)

/** Accessors for Sprout-specific theme values, alongside MaterialTheme's. */
object SproutTheme {
    /** The kid's tier. Kid mode only: throws outside [ProvideKidTier], so parent screens must not read it. */
    val kidTier: KidUiTier
        @Composable
        @ReadOnlyComposable
        get() = LocalKidTier.current

    /** Size and colour-level tokens: the kid's tier under [ProvideKidTier], [TierTokens.Parent] otherwise. */
    val tierTokens: TierTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalTierTokens.current

    /** The named Morning garden colours that are not Material roles. */
    val colors: SproutColors
        @Composable
        @ReadOnlyComposable
        get() = LocalSproutColors.current

    /** Whether decorative motion is off ([LocalReducedMotion]). */
    val reducedMotion: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalReducedMotion.current
}

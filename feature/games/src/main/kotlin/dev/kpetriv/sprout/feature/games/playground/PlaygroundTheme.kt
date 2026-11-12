package dev.kpetriv.sprout.feature.games.playground

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.theme.OutfitFontFamily
import dev.kpetriv.sprout.core.designsystem.theme.PlaygroundColors
import dev.kpetriv.sprout.core.designsystem.theme.PlaygroundTokens
import dev.kpetriv.sprout.core.designsystem.theme.ProvideKidTier
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.designsystem.theme.sproutDarkColorScheme
import dev.kpetriv.sprout.core.model.KidUiTier

/** The Playground palette in effect; [PlaygroundTheme] provides it. */
internal val LocalPlaygroundColors = staticCompositionLocalOf { PlaygroundColors.Default }

/** The tier's Playground sizes and accent level; [PlaygroundTheme] provides them. */
internal val LocalPlaygroundTokens = staticCompositionLocalOf { PlaygroundTokens.Sprout }

/**
 * The games-only theme (M9-games §2.2, D-M9-G3), from the start card to the result. Provides [LocalPlaygroundColors]
 * and the [tier]'s [PlaygroundTokens], and wraps [content] in a `MaterialTheme` whose colour scheme is
 * [playgroundColorScheme], so stock Material pieces inside a game (a `Switch`, an `OutlinedTextField`, a
 * `CircularProgressIndicator`, a sheet or a snackbar) pick up the field without per-call colours.
 *
 * Compose it inside `ProvideKidTier`: the typography and shapes are the tier-scaled ones in effect, and the tier's
 * `LocalTierTokens` and touch target are left as they are.
 */
@Composable
internal fun PlaygroundTheme(
    tier: KidUiTier,
    colors: PlaygroundColors = PlaygroundColors.Default,
    content: @Composable () -> Unit,
) {
    val scheme = remember(colors) { playgroundColorScheme(colors) }
    // MaterialTheme must not reset the tier's touch target, which ProvideKidTier set above it.
    val minimumInteractiveSize = LocalMinimumInteractiveComponentSize.current
    CompositionLocalProvider(
        LocalPlaygroundColors provides colors,
        LocalPlaygroundTokens provides PlaygroundTokens.forTier(tier),
    ) {
        MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentSize provides minimumInteractiveSize,
                LocalContentColor provides colors.chalk,
                content = content,
            )
        }
    }
}

/**
 * The app's dark Morning garden scheme with every surface and content role set to the field family, so any stock
 * Material piece inside a game (a sheet, an elevated card, a snackbar, a switch) is field-coloured and readable:
 * surfaces are Field, Field panel or Field deep with Chalk on them, captions are [PlaygroundColors.caption] (4.5:1 on
 * the panel), the primary colour is Lime with Ink on it, and the inverse surface (snackbars) is Chalk with Ink.
 */
internal fun playgroundColorScheme(colors: PlaygroundColors): ColorScheme = sproutDarkColorScheme().copy(
    primary = colors.lime,
    onPrimary = colors.ink,
    inversePrimary = colors.field,
    background = colors.field,
    onBackground = colors.chalk,
    surface = colors.field,
    onSurface = colors.chalk,
    surfaceVariant = colors.fieldPanel,
    onSurfaceVariant = colors.caption,
    surfaceTint = colors.field,
    surfaceBright = colors.fieldPanel,
    surfaceDim = colors.fieldDeep,
    surfaceContainerLowest = colors.fieldDeep,
    surfaceContainerLow = colors.fieldPanel,
    surfaceContainer = colors.fieldPanel,
    surfaceContainerHigh = colors.fieldPanel,
    surfaceContainerHighest = colors.fieldPanel,
    inverseSurface = colors.chalk,
    inverseOnSurface = colors.ink,
    outline = colors.caption,
    outlineVariant = colors.chalk.copy(alpha = OUTLINE_VARIANT_ALPHA).compositeOver(colors.field),
)

/** Dividers and hairlines on the field: Chalk at this opacity. */
private const val OUTLINE_VARIANT_ALPHA = 0.24f

/** Accessors for the Playground values, alongside `SproutTheme`'s. */
internal object Playground {
    val colors: PlaygroundColors
        @Composable
        @ReadOnlyComposable
        get() = LocalPlaygroundColors.current

    val tokens: PlaygroundTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalPlaygroundTokens.current
}

/**
 * Playground text styles (DESIGN-playground.md "typography"), built from the tier-scaled Material styles in effect so
 * they grow with the tier. Outfit has no 900 weight bundled, so the design's 900 styles use 800.
 */
internal object PlaygroundType {
    /** The game name in the top bar: Outfit 800 at headlineSmall (24 sp unscaled). */
    val title: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.headlineSmall.outfit()

    /** A big tile label ("55", "Done"): Outfit 800 at headlineLarge (32 sp unscaled). */
    val tileLarge: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.headlineLarge.outfit()

    /** A number-pad key or a smaller tile label: Outfit 800 at headlineMedium (28 sp unscaled). */
    val tile: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.headlineMedium.outfit()

    /** Figures and short labels in Outfit ("3 of 8", "Streak 3"): Outfit 800 at titleMedium. */
    val figure: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.titleMedium.outfit()

    /** The amount on the reward card: Outfit 800 at displaySmall (36 sp unscaled). */
    val amount: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.displaySmall.outfit()

    private fun TextStyle.outfit() = copy(fontFamily = OutfitFontFamily, fontWeight = FontWeight.ExtraBold)
}

/** A component sample on the field, the way goldens and previews show it. */
@Composable
internal fun PlaygroundSampleFrame(tier: KidUiTier, content: @Composable () -> Unit) {
    PlaygroundTheme(tier) {
        Box(modifier = Modifier.background(Playground.colors.field).padding(24.dp)) { content() }
    }
}

/** A preview of a Playground sample for [tier], as the app shows it (kid mode, reduced motion so it stands still). */
@Composable
internal fun PlaygroundPreview(tier: KidUiTier, content: @Composable () -> Unit) {
    SproutTheme(reducedMotion = true) {
        ProvideKidTier(tier) { PlaygroundSampleFrame(tier, content) }
    }
}

/** The three kid tiers, for one `@Preview` per tier of each component. */
internal class KidTierPreviewProvider : PreviewParameterProvider<KidUiTier> {
    override val values: Sequence<KidUiTier> = KidUiTier.entries.asSequence()
}

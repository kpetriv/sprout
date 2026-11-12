package dev.kpetriv.sprout.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * "Morning garden" (M9 §2.1, D-M9-2): one warm cream neutral family, white cards with hairline borders, one deep
 * botanical green and three soft tints (leaf, honey, sky). Values come from the renders Kiril approved and
 * .stitch/DESIGN-morning-garden.md. Every Material 3 role is set explicitly in both schemes, so no lavender
 * Material default leaks into a container, and nothing is pure black.
 */

// Light: the Morning garden names of M9 §2.1.
private val Botanical = Color(0xFF2A6B45)
private val BotanicalDeep = Color(0xFF1E4B33)
private val LeafTint = Color(0xFFE9F3EB)
private val Honey = Color(0xFFC4922A)
private val HoneyTint = Color(0xFFFDF4E2)
private val HoneyDeep = Color(0xFF735C00)
private val HoneyBorder = Color(0xFFF0E0BC)
private val Sky = Color(0xFF39697A)
private val SkyStrong = Color(0xFF3F82B4)
private val SkyTint = Color(0xFFEBF3F6)
private val SkyDeep = Color(0xFF264A61)
private val Cream = Color(0xFFFAF8F3)
private val White = Color(0xFFFFFFFF)
private val Linen = Color(0xFFF2F1EA)
private val MossGrey = Color(0xFFE8EAE2)
private val Sage = Color(0xFFE0E3D9)
private val Ink = Color(0xFF1A1C19)
private val InkSoft = Color(0xFF5A635C)
private val Outline = Color(0xFF7A837C)
private val Hairline = Color(0xFFE3E6DC)
private val ErrorRed = Color(0xFFBA1A1A)
private val ErrorTint = Color(0xFFFFDAD6)
private val ErrorDeep = Color(0xFF93000A)

// Light, derived within the same families (not in the renders; listed in the M9 slice A report).
private val SageDeep = Color(0xFFD8DCD0) // surfaceContainerHighest: one step past Sage
private val CreamDim = Color(0xFFDEDCD3) // surfaceDim
private val InkInverse = Color(0xFF2F312D) // inverseSurface: Ink lifted so it is never near-black on cream
private val CreamInverse = Color(0xFFF1F1EA) // inverseOnSurface

// Dark: from the "Dark theme" paragraph of DESIGN-morning-garden.md and the M9 §2.1 table.
private val BotanicalDark = Color(0xFF8FD3A3)
private val OnBotanicalDark = Color(0xFF1A3A28)
private val LeafTintDark = Color(0xFF1F4A30)
private val BotanicalDeepDark = Color(0xFFC8F2C0)
private val HoneyDark = Color(0xFFF0C56A)
private val HoneyTintDark = Color(0xFF4A3A10)
private val HoneyDeepDark = Color(0xFFFDE7B8)
private val SkyDark = Color(0xFF8EC3EA)
private val SkyTintDark = Color(0xFF1C3D55)
private val SkyDeepDark = Color(0xFFD5E8F5)
private val CreamDark = Color(0xFF141A16)
private val WhiteDark = Color(0xFF1C241E)
private val LinenDark = Color(0xFF222B25)
private val MossGreyDark = Color(0xFF2A342D)
private val InkDark = Color(0xFFE4E7E0)
private val InkSoftDark = Color(0xFFAEB7B0)
private val OutlineDark = Color(0xFF8F9890)
private val HairlineDark = Color(0xFF3B463E)
private val ErrorDark = Color(0xFFFFB4AB)

// Dark, derived.
private val OnHoneyDark = Color(0xFF3F2E00)
private val OnSkyDark = Color(0xFF0F3348)
private val SageDark = Color(0xFF313C34) // surfaceContainerHigh
private val SageDeepDark = Color(0xFF38443B) // surfaceContainerHighest
private val CreamBrightDark = Color(0xFF3A443C) // surfaceBright
private val OnErrorDark = Color(0xFF690005)
private val BotanicalPressedDark = Color(0xFF76BD8C)
private val HoneyBorderDark = Color(0xFF6A5420)
private val SkyStrongDark = Color(0xFFA8D4F5)
private val BrickDark = Color(0xFF4A2421) // spending circles: a muted error tint, quieter than errorContainer

// Fixed roles are the same in both schemes (Material's definition), taken from the light tints and dark accents.
private val OnBotanicalFixed = Color(0xFF0F2A1B)
private val OnHoneyFixed = Color(0xFF261A00)
private val OnSkyFixed = Color(0xFF0B2533)

internal val SproutLightColors = lightColorScheme(
    primary = Botanical,
    onPrimary = White,
    primaryContainer = LeafTint,
    onPrimaryContainer = BotanicalDeep,
    inversePrimary = BotanicalDark,
    secondary = Honey,
    onSecondary = White,
    secondaryContainer = HoneyTint,
    onSecondaryContainer = HoneyDeep,
    tertiary = Sky,
    onTertiary = White,
    tertiaryContainer = SkyTint,
    onTertiaryContainer = SkyDeep,
    background = Cream,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    surfaceVariant = MossGrey,
    onSurfaceVariant = InkSoft,
    surfaceTint = Botanical,
    inverseSurface = InkInverse,
    inverseOnSurface = CreamInverse,
    error = ErrorRed,
    onError = White,
    errorContainer = ErrorTint,
    onErrorContainer = ErrorDeep,
    outline = Outline,
    outlineVariant = Hairline,
    scrim = Ink,
    surfaceBright = Cream,
    surfaceContainer = MossGrey,
    surfaceContainerHigh = Sage,
    surfaceContainerHighest = SageDeep,
    surfaceContainerLow = Linen,
    surfaceContainerLowest = White,
    surfaceDim = CreamDim,
    primaryFixed = LeafTint,
    primaryFixedDim = BotanicalDark,
    onPrimaryFixed = OnBotanicalFixed,
    onPrimaryFixedVariant = BotanicalDeep,
    secondaryFixed = HoneyTint,
    secondaryFixedDim = HoneyDark,
    onSecondaryFixed = OnHoneyFixed,
    onSecondaryFixedVariant = HoneyDeep,
    tertiaryFixed = SkyTint,
    tertiaryFixedDim = SkyDark,
    onTertiaryFixed = OnSkyFixed,
    onTertiaryFixedVariant = SkyDeep,
)

internal val SproutDarkColors = darkColorScheme(
    primary = BotanicalDark,
    onPrimary = OnBotanicalDark,
    primaryContainer = LeafTintDark,
    onPrimaryContainer = BotanicalDeepDark,
    inversePrimary = Botanical,
    secondary = HoneyDark,
    onSecondary = OnHoneyDark,
    secondaryContainer = HoneyTintDark,
    onSecondaryContainer = HoneyDeepDark,
    tertiary = SkyDark,
    onTertiary = OnSkyDark,
    tertiaryContainer = SkyTintDark,
    onTertiaryContainer = SkyDeepDark,
    background = CreamDark,
    onBackground = InkDark,
    surface = CreamDark,
    onSurface = InkDark,
    surfaceVariant = MossGreyDark,
    onSurfaceVariant = InkSoftDark,
    surfaceTint = BotanicalDark,
    inverseSurface = InkDark,
    inverseOnSurface = InkInverse,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorDeep,
    onErrorContainer = ErrorTint,
    outline = OutlineDark,
    outlineVariant = HairlineDark,
    scrim = Ink,
    surfaceBright = CreamBrightDark,
    surfaceContainer = MossGreyDark,
    surfaceContainerHigh = SageDark,
    surfaceContainerHighest = SageDeepDark,
    surfaceContainerLow = LinenDark,
    surfaceContainerLowest = WhiteDark,
    surfaceDim = CreamDark,
    primaryFixed = LeafTint,
    primaryFixedDim = BotanicalDark,
    onPrimaryFixed = OnBotanicalFixed,
    onPrimaryFixedVariant = BotanicalDeep,
    secondaryFixed = HoneyTint,
    secondaryFixedDim = HoneyDark,
    onSecondaryFixed = OnHoneyFixed,
    onSecondaryFixedVariant = HoneyDeep,
    tertiaryFixed = SkyTint,
    tertiaryFixedDim = SkyDark,
    onTertiaryFixed = OnSkyFixed,
    onTertiaryFixedVariant = SkyDeep,
)

/** A tinted background ([container]) and the colour for text and icons on it ([content], 4.5:1 or better). */
@Immutable
data class SproutTint(val container: Color, val content: Color)

/**
 * The few named Morning garden colours that are not Material roles (M9 §2.1). Read through [SproutTheme.colors];
 * everything else comes from `MaterialTheme.colorScheme`.
 */
@Immutable
data class SproutColors(
    /** Botanical while pressed (filled primary buttons). */
    val primaryPressed: Color,
    /** The border of Honey-tint pills (secondary buttons, the screen-time badge). */
    val honeyBorder: Color,
    /**
     * The brighter Sky of the renders. Decorative or 18.7 sp bold and larger only: it is 4.15:1 on white, short of
     * 4.5:1, so text below WCAG's large-text size uses `tertiary` instead.
     */
    val skyStrong: Color,
    /** Per-kid avatar tints, in order: Leaf, Honey, Sky, Linen. Index with [avatarTint]. */
    val avatarTints: List<SproutTint>,
    /**
     * Spending icon circles (ledger rows): the error tint in light; in dark a muted brick, so a spending row is not the
     * loudest block on the screen (errorContainer stays at the spec value for destructive surfaces).
     */
    val spendingTint: SproutTint,
    /**
     * The paper disc behind a pictogram: white in light; the light Linen in dark, so the pictograms' Ink outlines keep
     * their contrast on a dark screen (`surfaceContainerLowest` is near-black there).
     */
    val pictogramDisc: Color,
) {
    /** The avatar tint for the kid at [index] (any non-negative index; wraps around). */
    fun avatarTint(index: Int): SproutTint = avatarTints[Math.floorMod(index, avatarTints.size)]

    companion object {
        val Light = SproutColors(
            primaryPressed = BotanicalDeep,
            honeyBorder = HoneyBorder,
            skyStrong = SkyStrong,
            avatarTints = listOf(
                SproutTint(LeafTint, BotanicalDeep),
                SproutTint(HoneyTint, HoneyDeep),
                SproutTint(SkyTint, SkyDeep),
                SproutTint(Linen, Ink),
            ),
            spendingTint = SproutTint(ErrorTint, ErrorDeep),
            pictogramDisc = White,
        )

        val Dark = SproutColors(
            primaryPressed = BotanicalPressedDark,
            honeyBorder = HoneyBorderDark,
            skyStrong = SkyStrongDark,
            avatarTints = listOf(
                SproutTint(LeafTintDark, BotanicalDeepDark),
                SproutTint(HoneyTintDark, HoneyDeepDark),
                SproutTint(SkyTintDark, SkyDeepDark),
                SproutTint(LinenDark, InkDark),
            ),
            spendingTint = SproutTint(BrickDark, ErrorTint),
            pictogramDisc = Linen,
        )
    }
}

/** The [SproutColors] in effect; [SproutTheme] provides the light or dark set. */
val LocalSproutColors = staticCompositionLocalOf { SproutColors.Light }

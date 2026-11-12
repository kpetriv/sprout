package dev.kpetriv.sprout.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import dev.kpetriv.sprout.core.designsystem.R

/*
 * Morning garden type (M9 §2.2, D-M9-4): Outfit for titles and every number that matters, Plus Jakarta Sans for
 * everything else. Both bundled as res/font (static instances, see core/designsystem/fonts/FONTS.md), so text is
 * offline and deterministic in Robolectric. Neither face has Cyrillic; Cyrillic text falls back to the system font.
 */

/** The display face: Outfit 700 and 800. */
val OutfitFontFamily: FontFamily = FontFamily(
    Font(R.font.outfit_bold, FontWeight.Bold),
    Font(R.font.outfit_extrabold, FontWeight.ExtraBold),
)

/** The text face: Plus Jakarta Sans 500, 600 and 700. */
val PlusJakartaSansFontFamily: FontFamily = FontFamily(
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
)

/** Tabular figures, so amounts line up in a column. */
private const val TABULAR_FIGURES = "tnum"

private val HeroTracking = (-0.02).em
private val HeadlineTracking = (-0.01).em
private val LabelLargeTracking = 0.01.em
private val LabelTracking = 0.02.em

private val Base = Typography()

private fun TextStyle.display(weight: FontWeight, tracking: TextUnit) = copy(
    fontFamily = OutfitFontFamily,
    fontWeight = weight,
    letterSpacing = tracking,
    fontFeatureSettings = TABULAR_FIGURES,
)

private fun TextStyle.text(weight: FontWeight, tracking: TextUnit = 0.sp) = copy(
    fontFamily = PlusJakartaSansFontFamily,
    fontWeight = weight,
    letterSpacing = tracking,
)

/**
 * Material's type sizes (bodySmall raised to 14 sp, D-M9-14) in the Morning garden faces: Outfit (800 for
 * displayLarge, 700 otherwise, tight tracking, tabular figures) for display, headline and titleLarge; Plus Jakarta
 * Sans (600 titles and bodyLarge, 500 body, 700 labels) for the rest. The theme scales it per tier with [scaledBy].
 */
val SproutTypography: Typography = Typography(
    displayLarge = Base.displayLarge.display(FontWeight.ExtraBold, HeroTracking),
    displayMedium = Base.displayMedium.display(FontWeight.Bold, HeroTracking),
    displaySmall = Base.displaySmall.display(FontWeight.Bold, HeroTracking),
    headlineLarge = Base.headlineLarge.display(FontWeight.Bold, HeadlineTracking),
    headlineMedium = Base.headlineMedium.display(FontWeight.Bold, HeadlineTracking),
    headlineSmall = Base.headlineSmall.display(FontWeight.Bold, HeadlineTracking),
    titleLarge = Base.titleLarge.display(FontWeight.Bold, HeadlineTracking),
    titleMedium = Base.titleMedium.text(FontWeight.SemiBold),
    titleSmall = Base.titleSmall.text(FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.text(FontWeight.SemiBold),
    bodyMedium = Base.bodyMedium.text(FontWeight.Medium),
    // Body text never goes below 14 sp (D-M9-14): bodySmall is raised from Material's 12 sp; labels keep theirs.
    bodySmall = Base.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp).text(FontWeight.Medium),
    labelLarge = Base.labelLarge.text(FontWeight.Bold, LabelLargeTracking),
    labelMedium = Base.labelMedium.text(FontWeight.Bold, LabelTracking),
    labelSmall = Base.labelSmall.text(FontWeight.Bold, LabelTracking),
)

/** Material type scale multiplied by [factor]; the baseline when the factor is 1. */
internal fun Typography.scaledBy(factor: Float): Typography = if (factor == 1f) {
    this
} else {
    copy(
        displayLarge = displayLarge.scaledBy(factor),
        displayMedium = displayMedium.scaledBy(factor),
        displaySmall = displaySmall.scaledBy(factor),
        headlineLarge = headlineLarge.scaledBy(factor),
        headlineMedium = headlineMedium.scaledBy(factor),
        headlineSmall = headlineSmall.scaledBy(factor),
        titleLarge = titleLarge.scaledBy(factor),
        titleMedium = titleMedium.scaledBy(factor),
        titleSmall = titleSmall.scaledBy(factor),
        bodyLarge = bodyLarge.scaledBy(factor),
        bodyMedium = bodyMedium.scaledBy(factor),
        bodySmall = bodySmall.scaledBy(factor),
        labelLarge = labelLarge.scaledBy(factor),
        labelMedium = labelMedium.scaledBy(factor),
        labelSmall = labelSmall.scaledBy(factor),
    )
}

private fun TextStyle.scaledBy(factor: Float): TextStyle = copy(
    fontSize = if (fontSize.isSpecified) fontSize * factor else fontSize,
    lineHeight = if (lineHeight.isSpecified) lineHeight * factor else lineHeight,
)

/**
 * A row amount ("+£1.50", "−30 min"): titleMedium's size in the display face, bold, with tabular figures so amounts
 * line up down a list. Scales with the tier like every other style.
 */
val Typography.amount: TextStyle
    get() = titleMedium.copy(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    )

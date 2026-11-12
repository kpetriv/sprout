package dev.kpetriv.sprout.core.designsystem.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The Morning garden spacing scale (M9 §2.4): an 8 dp grid with 20 dp side gutters. */
object SproutSpacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 16.dp
    val lg: Dp = 24.dp
    val xl: Dp = 32.dp

    /** Left and right screen gutter. */
    val gutter: Dp = 20.dp

    /** Inner padding of a `SproutCard`. */
    val cardPadding: Dp = 20.dp

    /** Between rows in a list. */
    val rowGap: Dp = 8.dp

    /** Between sections of a screen. */
    val sectionGap: Dp = 24.dp

    /** Reading width cap for scrolling content on tablets (D-V1-22). */
    val contentMaxWidth: Dp = 640.dp
}

/**
 * From this font scale (the user's system setting, not the tier's type scale) a row that puts text beside a chip or a
 * second button stacks them instead, so no word breaks mid-way (M7 §8 font-scale goldens).
 */
const val STACK_FONT_SCALE: Float = 1.5f

package dev.kpetriv.sprout.core.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/*
 * Window-size helpers (M7 §5, D-M7-2). Screens read the width class through [rememberWindowWidthClass] and never
 * measure the window themselves, so a test (or a list-detail pane) can pin it with [LocalWindowWidthClass].
 */

/**
 * The Material 3 window width classes: Compact below 600 dp (phones in portrait), Medium from 600 dp (small tablets,
 * unfolded foldables, phones in landscape), Expanded from 840 dp (tablets in landscape, desktop windows).
 */
enum class WindowWidthClass {
    Compact,
    Medium,
    Expanded,
    ;

    /** True for Medium and Expanded: room for two columns of cards. */
    val isAtLeastMedium: Boolean get() = this != Compact

    companion object {
        /** Material's Medium breakpoint (`WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND`). */
        val MediumMinWidth: Dp = 600.dp

        /** Material's Expanded breakpoint (`WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND`). */
        val ExpandedMinWidth: Dp = 840.dp

        /** The class of a window (or pane) [width] wide. */
        fun fromWidth(width: Dp): WindowWidthClass = when {
            width >= ExpandedMinWidth -> Expanded
            width >= MediumMinWidth -> Medium
            else -> Compact
        }
    }
}

/**
 * An explicit width class for everything below, overriding the window's. Null (the default) means "measure the
 * window". Screenshot tests provide it (`captureWidthClasses`), and a host may provide [WindowWidthClass.Compact]
 * to a screen shown in a narrow pane so it lays out as on a phone.
 */
val LocalWindowWidthClass = compositionLocalOf<WindowWidthClass?> { null }

/**
 * The width class in effect: [LocalWindowWidthClass] when provided, otherwise the class of the current window.
 *
 * The window width comes from [LocalWindowInfo]'s container size, the same source as material3-adaptive's
 * `currentWindowAdaptiveInfo()`, with the same 600/840 dp breakpoints (D-M7-31: no new artifact for core/ui); before
 * the first layout, when the container size is still zero, it falls back to the configuration's screen width.
 */
@Composable
@ReadOnlyComposable
fun rememberWindowWidthClass(): WindowWidthClass {
    LocalWindowWidthClass.current?.let { return it }
    val containerWidthPx = LocalWindowInfo.current.containerSize.width
    val width = if (containerWidthPx > 0) {
        with(LocalDensity.current) { containerWidthPx.toDp() }
    } else {
        LocalConfiguration.current.screenWidthDp.dp
    }
    return WindowWidthClass.fromWidth(width)
}

/** Provides [widthClass] (by default the window's, measured once here) to [content] through [LocalWindowWidthClass]. */
@Composable
fun ProvideWindowWidthClass(
    widthClass: WindowWidthClass = rememberWindowWidthClass(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalWindowWidthClass provides widthClass, content = content)
}

/** Defaults for [ContentWidth]. */
object ContentWidthDefaults {
    /** Reading width cap for a scrolling screen on a tablet: wider lines and cards are hard to scan. */
    val Max: Dp = 640.dp
}

/**
 * Caps a scrolling list at [max] wide and centres it, while the whole window stays scrollable (M7 review L10). The box
 * fills the width it is given and passes [content] the side `gutters` that centre a column exactly `min(width, max)`
 * wide; the list fills the box and adds the gutters to its content padding
 * (`contentPadding = PaddingValues(16.dp).withGutters(gutters)`), so a drag in a gutter scrolls the list as well.
 * Content beside the list that does not scroll (a composer, a banner) takes `Modifier.padding(gutters)`. On a phone
 * (narrower than [max]) the gutters are zero, so phone layouts and their goldens do not change. A scrolling column
 * uses the [contentWidth] modifier after `verticalScroll` instead. [modifier] applies to the full-width box (pass
 * `fillMaxSize()` or `weight(1f)` here).
 */
@Composable
fun ContentWidth(
    modifier: Modifier = Modifier,
    max: Dp = ContentWidthDefaults.Max,
    content: @Composable BoxScope.(gutters: PaddingValues) -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val gutters = LocalDensity.current.contentWidthGutters(constraints.maxWidth, max)
        content(gutters)
    }
}

/**
 * The left and right padding that centre a column at most [max] wide in [availableWidthPx], in whole pixels and
 * rounded as `Alignment.CenterHorizontally` rounds, so the column lands where the old centred box put it at integer
 * densities. At a fractional density the gutters are converted to dp and added to the list's own dp padding before
 * the list rounds the sum, so the column may sit 1 px off the old position (cosmetic). Zero when the width is at
 * most [max] or unbounded. Pure, so it is unit tested.
 */
fun Density.contentWidthGutters(availableWidthPx: Int, max: Dp = ContentWidthDefaults.Max): PaddingValues {
    val spare = if (availableWidthPx == Constraints.Infinity) 0 else availableWidthPx - max.roundToPx()
    if (spare <= 0) return NoGutters
    val left = (spare / 2f).roundToInt()
    return PaddingValues.Absolute(left = left.toDp(), right = (spare - left).toDp())
}

/** [this] content padding with the [ContentWidth] [gutters] added on each side. */
fun PaddingValues.withGutters(gutters: PaddingValues): PaddingValues =
    if (gutters === NoGutters) this else GutteredPadding(this, gutters)

private val NoGutters = PaddingValues(0.dp)

@Immutable
private data class GutteredPadding(val padding: PaddingValues, val gutters: PaddingValues) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
        padding.calculateLeftPadding(layoutDirection) + gutters.calculateLeftPadding(layoutDirection)

    override fun calculateTopPadding(): Dp = padding.calculateTopPadding() + gutters.calculateTopPadding()

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
        padding.calculateRightPadding(layoutDirection) + gutters.calculateRightPadding(layoutDirection)

    override fun calculateBottomPadding(): Dp = padding.calculateBottomPadding() + gutters.calculateBottomPadding()
}

/**
 * The same cap as [ContentWidth] as a modifier: takes the full width it is offered, then centres a box exactly
 * `min(width, max)` wide. Put it after `verticalScroll` on a scrolling column
 * (`Modifier.fillMaxSize().verticalScroll(state).contentWidth().padding(16.dp)`), so the whole window still scrolls
 * while the content stays readable; on a phone the column is exactly as wide as before.
 */
fun Modifier.contentWidth(max: Dp = ContentWidthDefaults.Max): Modifier = fillMaxWidth()
    .wrapContentWidth(Alignment.CenterHorizontally)
    .widthIn(max = max)
    .fillMaxWidth()

/**
 * Columns for a grid whose cells must be at least [minColumnWidth] wide, between [minColumns] and [maxColumns]:
 * as many as fit [availableWidth] with [spacing] between them. Pure, so it is unit tested.
 */
fun adaptiveColumnCount(
    availableWidth: Dp,
    minColumnWidth: Dp,
    spacing: Dp = 0.dp,
    minColumns: Int = 1,
    maxColumns: Int = Int.MAX_VALUE,
): Int {
    require(minColumnWidth > 0.dp) { "minColumnWidth must be positive" }
    require(minColumns in 1..maxColumns) { "Need 1 <= minColumns <= maxColumns" }
    val fitting = ((availableWidth + spacing) / (minColumnWidth + spacing)).toInt()
    return fitting.coerceIn(minColumns, maxColumns)
}

/**
 * Lazy-grid columns that adapt to the width like [GridCells.Adaptive], but never fewer than [minColumns] (a phone's
 * fixed count, so phone layouts stay as they were) and never more than [maxColumns].
 */
class AdaptiveColumns(
    private val minColumnWidth: Dp,
    private val minColumns: Int = 1,
    private val maxColumns: Int = Int.MAX_VALUE,
) : GridCells {
    init {
        require(minColumnWidth > 0.dp) { "minColumnWidth must be positive" }
        require(minColumns in 1..maxColumns) { "Need 1 <= minColumns <= maxColumns" }
    }

    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val count = adaptiveColumnCount(
            availableWidth = availableSize.toDp(),
            minColumnWidth = minColumnWidth,
            spacing = spacing.toDp(),
            minColumns = minColumns,
            maxColumns = maxColumns,
        )
        val gridSize = availableSize - spacing * (count - 1)
        val slotSize = gridSize / count
        val remaining = gridSize % count
        return List(count) { slotSize + if (it < remaining) 1 else 0 }
    }

    override fun equals(other: Any?): Boolean = other is AdaptiveColumns &&
        other.minColumnWidth == minColumnWidth &&
        other.minColumns == minColumns &&
        other.maxColumns == maxColumns

    override fun hashCode(): Int = (minColumnWidth.hashCode() * HASH_PRIME + minColumns) * HASH_PRIME + maxColumns
}

/**
 * Measures the width it is given and passes [content] the number of columns of at least [minColumnWidth] that fit
 * (see [adaptiveColumnCount]), for grids that are not lazy (rows of chunked items, flow rows of cards).
 */
@Composable
fun AdaptiveColumns(
    minColumnWidth: Dp,
    modifier: Modifier = Modifier,
    spacing: Dp = 0.dp,
    minColumns: Int = 1,
    maxColumns: Int = Int.MAX_VALUE,
    content: @Composable (columns: Int) -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val columns = adaptiveColumnCount(maxWidth, minColumnWidth, spacing, minColumns, maxColumns)
        content(columns)
    }
}

private const val HASH_PRIME = 31

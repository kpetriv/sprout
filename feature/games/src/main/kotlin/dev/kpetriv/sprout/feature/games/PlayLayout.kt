package dev.kpetriv.sprout.feature.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.ui.WindowWidthClass
import dev.kpetriv.sprout.core.ui.contentWidth
import dev.kpetriv.sprout.core.ui.rememberWindowWidthClass

/**
 * How a play screen is laid out in this window (M9-games §5, D-M9-G9), from the width class and never the
 * orientation, so an unfolded foldable gets the two columns too.
 */
internal enum class PlayLayout {
    /** Compact: the phone layout across the window. */
    Phone,

    /** Medium (a portrait tablet): the phone layout in one 640 dp column on the full-bleed field. */
    Column,

    /** Expanded (a landscape tablet): the prompt left and the answers right under one progress row. */
    TwoColumns,
}

@Composable
@ReadOnlyComposable
internal fun rememberPlayLayout(): PlayLayout = when (rememberWindowWidthClass()) {
    WindowWidthClass.Compact -> PlayLayout.Phone
    WindowWidthClass.Medium -> PlayLayout.Column
    WindowWidthClass.Expanded -> PlayLayout.TwoColumns
}

/** Sizes of the play layouts (M9-games §5). */
internal object PlayLayoutDefaults {
    /** The one column of a portrait tablet (and every single-column screen on a wider window). */
    val ColumnWidth: Dp = 640.dp

    /** The two columns of a landscape tablet together, centred on the field. */
    val WideWidth: Dp = 1120.dp

    /** The side gutter on the field (DESIGN-playground.md "spacing.gutter"). */
    val Gutter: Dp = 24.dp

    /** The gap between the two columns. */
    val ColumnGap: Dp = 48.dp

    /** The prompt column's share of the width on a landscape tablet. */
    const val PROMPT_WEIGHT: Float = 0.55f
    const val ANSWERS_WEIGHT: Float = 1f - PROMPT_WEIGHT

    /** An answer tile never grows wider than this; two of them and their gap make the answers' width. */
    val AnswerTileMaxWidth: Dp = 180.dp
    val TileGap: Dp = 14.dp
    val AnswersMaxWidth: Dp = AnswerTileMaxWidth * 2 + TileGap

    val SectionGap: Dp = 20.dp
}

/**
 * One round of a game on the field: [header] (the progress row and the countdown ring) at the top, then [prompt] and
 * [answers]. On a phone and a portrait tablet one column (640 dp at most), the prompt in the middle of the space the
 * answers leave at the bottom, scrolling when it does not fit (a large font); on a landscape tablet the header across
 * the top, the prompt left (55 %) and the answers right, both vertically centred, so the eyes move once.
 */
@Composable
internal fun RoundLayout(
    header: @Composable () -> Unit,
    prompt: @Composable ColumnScope.() -> Unit,
    answers: @Composable ColumnScope.() -> Unit,
) {
    if (rememberPlayLayout() == PlayLayout.TwoColumns) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .contentWidth(PlayLayoutDefaults.WideWidth)
                .padding(horizontal = PlayLayoutDefaults.Gutter, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(PlayLayoutDefaults.SectionGap),
        ) {
            header()
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(PlayLayoutDefaults.ColumnGap),
            ) {
                CenteredColumn(Modifier.weight(PlayLayoutDefaults.PROMPT_WEIGHT), prompt)
                CenteredColumn(Modifier.weight(PlayLayoutDefaults.ANSWERS_WEIGHT), answers)
            }
        }
    } else {
        FieldColumn(verticalArrangement = Arrangement.SpaceBetween) {
            header()
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = prompt,
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(PlayLayoutDefaults.SectionGap),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = answers,
            )
        }
    }
}

/**
 * A single column on the field, at most [PlayLayoutDefaults.ColumnWidth] wide and centred, at least as tall as the
 * field (so [verticalArrangement] can spread or centre its children) and scrolling when taller.
 */
@Composable
internal fun FieldColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(
        PlayLayoutDefaults.SectionGap,
        Alignment.CenterVertically,
    ),
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val fieldHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .contentWidth(PlayLayoutDefaults.ColumnWidth)
                .heightIn(min = fieldHeight)
                .padding(horizontal = PlayLayoutDefaults.Gutter, vertical = 8.dp),
            verticalArrangement = verticalArrangement,
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/** A column of a landscape tablet: its content vertically centred, scrolling when taller than the field. */
@Composable
private fun CenteredColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier = modifier.fillMaxHeight()) {
        val height = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = height),
            verticalArrangement = Arrangement.spacedBy(PlayLayoutDefaults.SectionGap, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

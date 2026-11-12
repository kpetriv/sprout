@file:Suppress("MatchingDeclarationName") // Named after its composables; the defaults object sits beside them.

package dev.kpetriv.sprout.feature.games.playground

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.Accent
import dev.kpetriv.sprout.core.designsystem.theme.AccentLevel
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.feature.games.R

/** Defaults for [NumberPad] and [ReadAloudTile]. */
internal object NumberPadDefaults {
    /** The pad never stretches wider than this (M9-games §5). */
    val MaxWidth: Dp = 420.dp

    /** Keys are the tier's tile height, but no taller than this (four rows must fit under a question). */
    val MaxKeyHeight: Dp = 72.dp

    val KeyGap: Dp = 12.dp
}

/**
 * The typed-answer pad (M9-games §2.2): a 3 by 4 grid of keys, 1 to 9, then backspace, 0 and "Go". Digits are Sky
 * tiles and backspace Coral; on the quiet Sapling set both are Field-panel tiles ([Accent.Panel], as the render
 * games-B-sapling-tables-play shows) with a Lime backspace icon. "Go" is Marigold ([Accent.Primary]) at every tier.
 * Each key is a [PlayTile] at the tier's tile height (at most 72 dp) and the pad at most 420 dp wide.
 * [goEnabled] = false dims "Go" (nothing typed yet).
 */
@Composable
internal fun NumberPad(
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
    onGo: () -> Unit,
    modifier: Modifier = Modifier,
    goEnabled: Boolean = true,
) {
    val tokens = Playground.tokens
    val quiet = tokens.accents == AccentLevel.QUIET
    val digit = if (quiet) Accent.Panel else Accent.Sky
    val keyHeight = maxOf(
        SproutTheme.tierTokens.minTouchTarget,
        minOf(tokens.tileHeight, NumberPadDefaults.MaxKeyHeight),
    )
    val keys = Modifier.height(keyHeight)
    Column(
        modifier = modifier.widthIn(max = NumberPadDefaults.MaxWidth).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NumberPadDefaults.KeyGap),
    ) {
        DIGIT_ROWS.forEach { row ->
            PadRow {
                row.forEach { value -> DigitKey(value, digit, onDigit, keys.weight(1f)) }
            }
        }
        PadRow {
            val backspace = stringResource(R.string.feature_games_backspace)
            PlayTile(
                accent = if (quiet) Accent.Panel else Accent.Coral,
                onClick = onBackspace,
                modifier = keys.weight(1f).semantics { contentDescription = backspace },
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(
                    imageVector = SproutIcons.Backspace,
                    contentDescription = null,
                    tint = if (quiet) Playground.colors.lime else LocalContentColor.current,
                )
            }
            DigitKey(0, digit, onDigit, keys.weight(1f))
            PlayTile(
                accent = Accent.Primary,
                onClick = onGo,
                enabled = goEnabled,
                modifier = keys.weight(1f),
                contentPadding = PaddingValues(0.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.feature_games_go),
                        style = PlaygroundType.tile.copy(color = LocalContentColor.current),
                    )
                    Icon(SproutIcons.Check, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun PadRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NumberPadDefaults.KeyGap),
        content = content,
    )
}

@Composable
private fun DigitKey(value: Int, accent: Accent, onDigit: (Int) -> Unit, modifier: Modifier) {
    PlayTile(
        accent = accent,
        onClick = { onDigit(value) },
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
    ) { Text(text = "$value", style = PlaygroundType.tile.copy(color = LocalContentColor.current)) }
}

private val DIGIT_ROWS = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))

/**
 * The read-aloud tile (M9-games §2.2, replacing `ReadAloudButton`'s "🔊"): a round Sky tile with the volume icon in
 * Ink, the tier's touch target across. Seedling shows it beside every heading and under the result.
 */
@Composable
internal fun ReadAloudTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.feature_games_read_aloud),
) {
    // The fill is a circle the touch target across; the edge sits below it.
    val touch = SproutTheme.tierTokens.minTouchTarget
    PlayTile(
        accent = Accent.Sky,
        onClick = onClick,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.size(width = touch, height = touch + Playground.tokens.tileEdge).semantics {
            this.contentDescription =
                contentDescription
        },
    ) { Icon(SproutIcons.VolumeUp, contentDescription = null) }
}

@Composable
internal fun NumberPadSample() {
    NumberPad(onDigit = {}, onBackspace = {}, onGo = {})
}

@Composable
internal fun ReadAloudTileSample() {
    ReadAloudTile(onClick = {})
}

@Preview(name = "Number pad", widthDp = 411)
@Composable
private fun NumberPadPreview(@PreviewParameter(KidTierPreviewProvider::class) tier: KidUiTier) =
    PlaygroundPreview(tier) { NumberPadSample() }

@Preview(name = "Read-aloud tile")
@Composable
private fun ReadAloudTilePreview(@PreviewParameter(KidTierPreviewProvider::class) tier: KidUiTier) =
    PlaygroundPreview(tier) { ReadAloudTileSample() }

package dev.kpetriv.sprout.feature.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.Accent
import dev.kpetriv.sprout.core.designsystem.theme.PlaygroundAccent
import dev.kpetriv.sprout.core.games.Symbol
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.ReadAloud
import dev.kpetriv.sprout.feature.games.playground.PictureGlyph
import dev.kpetriv.sprout.feature.games.playground.PlayTile
import dev.kpetriv.sprout.feature.games.playground.PlayTileSurface
import dev.kpetriv.sprout.feature.games.playground.Playground
import dev.kpetriv.sprout.feature.games.playground.PlaygroundType
import dev.kpetriv.sprout.feature.games.playground.ReadAloudTile
import dev.kpetriv.sprout.feature.games.playground.SequencePanel
import dev.kpetriv.sprout.feature.games.playground.ShapeGlyph

/** The Chalk edge under a Seedling picture tile: Chalk at this opacity over the field. */
private const val PICTURE_EDGE_ALPHA = 0.6f

/** How much of a tile its glyph fills. */
private const val GLYPH_SCALE = 0.6f

/**
 * A memory round on the field (M9-games §3). While the sequence shows ("Watch carefully!", one more symbol every
 * 700 ms in the [SequencePanel]) the choice tiles are dimmed and disabled; then the sequence hides, the panel announces
 * it once by name (its live region), and the kid taps it back in order: tapped slots get a Lime tick and "2 of 4
 * tapped" shows under the panel. A wrong tap ends the round. Seedling plays pictures on 104 dp Chalk tiles with the
 * instruction read aloud; Sprout and Sapling shapes on panel tiles, Sapling with two distractors among them and, in
 * timed mode, the bonus caption.
 */
@Composable
internal fun MemoryGame(
    state: GamePlayUiState.Playing,
    ui: RoundUi.Memory,
    header: @Composable () -> Unit,
    onEvent: (GamePlayEvent) -> Unit,
    readAloud: ReadAloud,
) {
    val tier = state.tier
    val instruction = stringResource(
        if (ui.showing) R.string.feature_games_memory_watch else R.string.feature_games_memory_tap,
    )
    if (tier == KidUiTier.SEEDLING) {
        val speech = rememberUpdatedState(readAloud)
        LaunchedEffect(state.roundKey, ui.showing) {
            if (speech.value !== ReadAloud.None) speech.value.speak(instruction)
        }
    }
    val sequence = ui.round.sequence
    RoundLayout(
        header = header,
        prompt = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            ) {
                Text(
                    text = instruction,
                    style = PlaygroundType.heading,
                    color = Playground.colors.chalk,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (tier == KidUiTier.SEEDLING && readAloud !== ReadAloud.None) {
                    ReadAloudTile(onClick = { readAloud.speak(instruction) })
                }
            }
            SequencePanel(
                sequence = sequence,
                filled = if (ui.showing) sequence.take(ui.shownCount) else ui.tapped,
                showing = ui.showing,
                pictures = tier == KidUiTier.SEEDLING,
            )
            if (!ui.showing && ui.tapped.isNotEmpty()) {
                // The panel's live region already says it; this is the visible copy.
                val tapped = ui.tapped.size
                Caption(
                    text = pluralStringResource(R.plurals.feature_games_memory_tapped, tapped, tapped, sequence.size),
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        },
        answers = {
            SymbolChoices(ui, tier, onEvent)
            if (state.timed) TimedCaption()
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SymbolChoices(ui: RoundUi.Memory, tier: KidUiTier, onEvent: (GamePlayEvent) -> Unit) {
    val size = choiceSize(tier)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PlayLayoutDefaults.TileGap, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(PlayLayoutDefaults.TileGap),
    ) {
        ui.round.choices.forEach { symbol ->
            SymbolTile(
                symbol = symbol,
                pictures = tier == KidUiTier.SEEDLING,
                size = size,
                enabled = !ui.showing,
                onClick = { onEvent(GamePlayEvent.TapSymbol(symbol)) },
            )
        }
    }
}

@Composable
private fun SymbolTile(symbol: Symbol, pictures: Boolean, size: Dp, enabled: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.size(size).semantics { contentDescription = symbol.label }
    if (pictures) {
        val colors = Playground.colors
        PlayTileSurface(
            colors = PlaygroundAccent(
                fill = colors.chalk,
                edge = colors.chalk.copy(alpha = PICTURE_EDGE_ALPHA).compositeOver(colors.field),
                content = colors.ink,
            ),
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            contentPadding = PaddingValues(0.dp),
        ) { PictureGlyph(symbol = symbol, size = size * GLYPH_SCALE, labelled = false) }
    } else {
        PlayTile(
            accent = Accent.Panel,
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            contentPadding = PaddingValues(0.dp),
        ) { ShapeGlyph(symbol = symbol, size = size * GLYPH_SCALE, labelled = false) }
    }
}

/** Sapling's timed memory: the bonus rule under the choices. */
@Composable
private fun TimedCaption() {
    val colors = Playground.colors
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DecorativeIcon(SproutIcons.Timer, tint = colors.lime)
        Text(
            text = stringResource(R.string.feature_games_memory_timed),
            style = PlaygroundType.figure,
            color = colors.lime,
        )
    }
}

private fun choiceSize(tier: KidUiTier): Dp = when (tier) {
    KidUiTier.SEEDLING -> 104.dp
    KidUiTier.SPROUT -> 72.dp
    KidUiTier.SAPLING -> 60.dp
}

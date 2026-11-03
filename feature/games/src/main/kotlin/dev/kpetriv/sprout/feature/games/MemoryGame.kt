package dev.kpetriv.sprout.feature.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.games.Symbol
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.ReadAloud
import dev.kpetriv.sprout.core.ui.TierScaledText
import dev.kpetriv.sprout.core.ui.TierTextRole

/**
 * A memory round. While the sequence shows (one more symbol every 700 ms) the choices are disabled and the symbols
 * shown so far are announced through a live region; then the sequence hides and the kid taps it back in order. A
 * wrong tap ends the round. Seedling plays pictures with read-aloud instructions; Sprout colours and shapes; Sapling
 * the same plus two distractors among the choices.
 */
@Composable
internal fun MemoryGame(
    ui: RoundUi.Memory,
    tier: KidUiTier,
    roundKey: Long,
    onEvent: (GamePlayEvent) -> Unit,
    readAloud: ReadAloud,
) {
    val instruction = stringResource(
        if (ui.showing) R.string.feature_games_memory_watch else R.string.feature_games_memory_tap,
    )
    if (tier == KidUiTier.SEEDLING) {
        val speech = rememberUpdatedState(readAloud)
        LaunchedEffect(roundKey, ui.showing) { if (speech.value !== ReadAloud.None) speech.value.speak(instruction) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TierScaledText(
                text = instruction,
                role = TierTextRole.Title,
                tier = tier,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (tier == KidUiTier.SEEDLING) ReadAloudButton(spoken = instruction, readAloud = readAloud)
        }
        SequenceCard(ui, tier)
        SymbolChoices(ui, tier, onEvent)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SequenceCard(ui: RoundUi.Memory, tier: KidUiTier) {
    val sequence = ui.round.sequence
    val visible = if (ui.showing) sequence.take(ui.shownCount) else ui.tapped
    val description = if (ui.showing) {
        visible.joinToString(", ") { it.label }
    } else {
        pluralStringResource(R.plurals.feature_games_memory_tapped, sequence.size, ui.tapped.size, sequence.size)
    }
    val hidden = stringResource(R.string.feature_games_memory_hidden)
    Card(modifier = Modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clearAndSetSemantics {
                    contentDescription = description.ifEmpty { hidden }
                    liveRegion = LiveRegionMode.Polite
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sequence.indices.forEach { index ->
                Slot(symbol = visible.getOrNull(index), size = slotSize(tier))
            }
        }
    }
}

@Composable
private fun Slot(symbol: Symbol?, size: Dp) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (symbol == null) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.surface,
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (symbol != null) Text(text = symbol.glyph, style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SymbolChoices(ui: RoundUi.Memory, tier: KidUiTier, onEvent: (GamePlayEvent) -> Unit) {
    val touch = SproutTheme.tierTokens.minTouchTarget
    val buttonSize = maxOf(touch, choiceSize(tier))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ui.round.choices.forEach { symbol ->
            FilledTonalButton(
                onClick = { onEvent(GamePlayEvent.TapSymbol(symbol)) },
                enabled = !ui.showing,
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(4.dp),
                modifier = Modifier
                    .sizeIn(minWidth = buttonSize, minHeight = buttonSize)
                    .semantics { contentDescription = symbol.label },
            ) {
                Text(
                    text = symbol.glyph,
                    style = if (tier == KidUiTier.SEEDLING) {
                        MaterialTheme.typography.displaySmall
                    } else {
                        MaterialTheme.typography.headlineMedium
                    },
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        }
    }
}

private fun slotSize(tier: KidUiTier): Dp = when (tier) {
    KidUiTier.SEEDLING -> 64.dp
    KidUiTier.SPROUT -> 52.dp
    KidUiTier.SAPLING -> 44.dp
}

private fun choiceSize(tier: KidUiTier): Dp = when (tier) {
    KidUiTier.SEEDLING -> 96.dp
    KidUiTier.SPROUT -> 72.dp
    KidUiTier.SAPLING -> 60.dp
}

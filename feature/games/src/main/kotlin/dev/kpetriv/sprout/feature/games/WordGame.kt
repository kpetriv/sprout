package dev.kpetriv.sprout.feature.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.TierScaledText
import dev.kpetriv.sprout.core.ui.TierTextRole

/**
 * A word scramble round (Sprout and Sapling; never Seedling, D-M5-6). The kid taps the scrambled letters in order
 * (Sapling can also type the word); "Clear" starts over. The round ends when the answer is as long as the word.
 * Sprout gets a one-word hint.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WordGame(ui: RoundUi.Word, tier: KidUiTier, onEvent: (GamePlayEvent) -> Unit) {
    val touch = SproutTheme.tierTokens.minTouchTarget
    val round = ui.round
    val answerDescription = if (ui.answer.isEmpty()) {
        stringResource(R.string.feature_games_word_answer_empty)
    } else {
        stringResource(R.string.feature_games_word_answer_description, ui.answer.uppercase())
    }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        TierScaledText(
            text = stringResource(R.string.feature_games_word_prompt),
            role = TierTextRole.Title,
            tier = tier,
        )
        val hint = round.hint
        if (hint != null) {
            TierScaledText(
                text = stringResource(R.string.feature_games_word_hint, hint),
                role = TierTextRole.Body,
                tier = tier,
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = answerDescription },
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            round.word.indices.forEach { index -> AnswerSlot(ui.answer.getOrNull(index), slotSize(tier)) }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            round.scrambled.forEachIndexed { index, letter ->
                LetterTile(
                    letter = letter,
                    position = index + 1,
                    count = round.scrambled.length,
                    used = index in ui.used,
                    size = maxOf(touch, slotSize(tier)),
                    onClick = { onEvent(GamePlayEvent.TapLetter(index)) },
                )
            }
        }
        if (tier == KidUiTier.SAPLING) {
            OutlinedTextField(
                value = ui.answer,
                onValueChange = { onEvent(GamePlayEvent.WordTyped(it)) },
                label = { Text(stringResource(R.string.feature_games_word_answer_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedButton(
            onClick = { onEvent(GamePlayEvent.ClearWord) },
            enabled = ui.answer.isNotEmpty(),
            modifier = Modifier.heightIn(min = touch),
        ) {
            TierScaledText(
                text = stringResource(R.string.feature_games_word_clear),
                role = TierTextRole.Label,
                tier = tier,
            )
        }
    }
}

@Composable
private fun AnswerSlot(letter: Char?, size: Dp) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (letter == null) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (letter != null) Text(text = letter.uppercase(), style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun LetterTile(letter: Char, position: Int, count: Int, used: Boolean, size: Dp, onClick: () -> Unit) {
    // The position keeps two tiles with the same letter apart for TalkBack ("Letter R, 3 of 6", M7 §8).
    val description = stringResource(R.string.feature_games_word_letter, letter.uppercase(), position, count)
    FilledTonalButton(
        onClick = onClick,
        enabled = !used,
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(4.dp),
        modifier = Modifier.sizeIn(minWidth = size, minHeight = size).semantics { contentDescription = description },
    ) {
        Text(
            text = letter.uppercase(),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

private fun slotSize(tier: KidUiTier): Dp = when (tier) {
    KidUiTier.SEEDLING, KidUiTier.SPROUT -> 52.dp
    KidUiTier.SAPLING -> 40.dp
}

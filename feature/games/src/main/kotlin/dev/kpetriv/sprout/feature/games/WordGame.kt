package dev.kpetriv.sprout.feature.games

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.Accent
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.feature.games.playground.PlayTile
import dev.kpetriv.sprout.feature.games.playground.Playground
import dev.kpetriv.sprout.feature.games.playground.PlaygroundType

private val SlotGap = 8.dp
private val MinSlot = 32.dp
private val DashLength = 6.dp
private val DashStroke = 2.dp

/**
 * A word scramble round on the field (Sprout and Sapling; never Seedling, D-M5-6). The panel holds "Unscramble the
 * word", Sprout's hint as a Lime pill and the answer slots (placed letters on Sky, the rest dashed); under it the
 * Marigold letter tiles, which dim when used, Sapling's "Type the word" field, and the outline "Clear". The round
 * ends when the answer is as long as the word (there is no submit tile).
 */
@Composable
internal fun WordGame(
    ui: RoundUi.Word,
    tier: KidUiTier,
    header: @Composable () -> Unit,
    onEvent: (GamePlayEvent) -> Unit,
) {
    val round = ui.round
    RoundLayout(
        header = header,
        prompt = {
            FieldPanel {
                Text(
                    text = stringResource(R.string.feature_games_word_prompt),
                    style = PlaygroundType.heading,
                    color = Playground.colors.chalk,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                val hint = round.hint
                if (hint != null) HintPill(stringResource(R.string.feature_games_word_hint, hint))
                AnswerSlots(ui.answer, round.word.length, tier)
            }
        },
        answers = {
            LetterTiles(ui, tier, onEvent)
            if (tier == KidUiTier.SAPLING) TypedWord(ui.answer, onEvent)
            ActionTile(
                text = stringResource(R.string.feature_games_word_clear),
                onClick = { onEvent(GamePlayEvent.ClearWord) },
                accent = Accent.Outline,
                enabled = ui.answer.isNotEmpty(),
                icon = SproutIcons.Backspace,
            )
        },
    )
}

@Composable
private fun HintPill(text: String) {
    val colors = Playground.colors
    Text(
        text = text,
        style = PlaygroundType.figure,
        color = colors.ink,
        modifier = Modifier
            .background(colors.lime, CircleShape)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** One slot per letter of the word, as large as the tier's slot but shrunk to keep one row. */
@Composable
private fun AnswerSlots(answer: String, length: Int, tier: KidUiTier) {
    val description = if (answer.isEmpty()) {
        stringResource(R.string.feature_games_word_answer_empty)
    } else {
        stringResource(R.string.feature_games_word_answer_description, answer.uppercase())
    }
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val count = length.coerceAtLeast(1)
        val fitted = (maxWidth - SlotGap * (count - 1)) / count
        val size = fitted.coerceIn(MinSlot, slotSize(tier))
        Row(horizontalArrangement = Arrangement.spacedBy(SlotGap)) {
            repeat(length) { index -> Slot(answer.getOrNull(index), size) }
        }
    }
}

/**
 * One answer slot, drawn (not a [PlayTile], which would grow to the touch target): a placed letter on a Sky tile with
 * its edge, or a dashed outline. The whole row is one node for TalkBack.
 */
@Composable
private fun Slot(letter: Char?, size: Dp) {
    val colors = Playground.colors
    val corner = minOf(Playground.tokens.tileCorner, size / SLOT_CORNER_DIVISOR)
    val edge = Playground.tokens.tileEdge
    Box(
        modifier = Modifier.size(size).drawBehind {
            val radius = CornerRadius(corner.toPx())
            if (letter == null) {
                val stroke = DashStroke.toPx()
                val dash = DashLength.toPx()
                drawRoundRect(colors.fieldDeep, cornerRadius = radius)
                drawRoundRect(
                    color = colors.chalkSoft,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(this.size.width - stroke, this.size.height - stroke),
                    cornerRadius = radius,
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
                )
            } else {
                val fill = Size(this.size.width, this.size.height - edge.toPx())
                drawRoundRect(colors.skyEdge, topLeft = Offset(0f, edge.toPx()), size = fill, cornerRadius = radius)
                drawRoundRect(colors.sky, size = fill, cornerRadius = radius)
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        if (letter != null) {
            Text(
                text = letter.uppercase(),
                style = PlaygroundType.tile,
                color = colors.ink,
                modifier = Modifier.padding(bottom = edge),
            )
        }
    }
}

private const val SLOT_CORNER_DIVISOR = 4

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LetterTiles(ui: RoundUi.Word, tier: KidUiTier, onEvent: (GamePlayEvent) -> Unit) {
    val round = ui.round
    val size = maxOf(SproutTheme.tierTokens.minTouchTarget, slotSize(tier))
    FlowRow(
        modifier = Modifier.fillMaxWidth().widthIn(max = PlayLayoutDefaults.AnswersMaxWidth),
        horizontalArrangement = Arrangement.spacedBy(SlotGap, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(SlotGap),
    ) {
        round.scrambled.forEachIndexed { index, letter ->
            // The position keeps two tiles with the same letter apart for TalkBack ("Letter R, 3 of 6", M7 §8).
            val description = stringResource(
                R.string.feature_games_word_letter,
                letter.uppercase(),
                index + 1,
                round.scrambled.length,
            )
            PlayTile(
                accent = Accent.Marigold,
                onClick = { onEvent(GamePlayEvent.TapLetter(index)) },
                enabled = index !in ui.used,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(size).semantics { contentDescription = description },
            ) {
                Text(
                    text = letter.uppercase(),
                    style = PlaygroundType.tile.copy(color = LocalContentColor.current),
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        }
    }
}

@Composable
private fun TypedWord(answer: String, onEvent: (GamePlayEvent) -> Unit) {
    OutlinedTextField(
        value = answer,
        onValueChange = { onEvent(GamePlayEvent.WordTyped(it)) },
        label = { Text(stringResource(R.string.feature_games_word_answer_label)) },
        singleLine = true,
        textStyle = PlaygroundType.figure,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth().widthIn(max = PlayLayoutDefaults.AnswersMaxWidth),
    )
}

private fun slotSize(tier: KidUiTier): Dp = when (tier) {
    KidUiTier.SEEDLING, KidUiTier.SPROUT -> 56.dp
    KidUiTier.SAPLING -> 44.dp
}

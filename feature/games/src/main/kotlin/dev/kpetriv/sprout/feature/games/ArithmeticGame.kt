package dev.kpetriv.sprout.feature.games

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.theme.Accent
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.games.ArithmeticRound
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.ReadAloud
import dev.kpetriv.sprout.feature.games.playground.NumberPad
import dev.kpetriv.sprout.feature.games.playground.NumberPadDefaults
import dev.kpetriv.sprout.feature.games.playground.PlayTile
import dev.kpetriv.sprout.feature.games.playground.Playground
import dev.kpetriv.sprout.feature.games.playground.PlaygroundType
import dev.kpetriv.sprout.feature.games.playground.ReadAloudTile
import dev.kpetriv.sprout.feature.games.playground.StreakPill

/** Most dots drawn under a Seedling picture choice; bigger numbers show the numeral only. */
private const val MAX_CHOICE_DOTS = 10
private const val CHOICE_COLUMNS = 2
private val CountingDot = 48.dp
private val ChoiceDot = 10.dp

/**
 * A Numbers round on the field (M9-games §3, §4). Seedling: "How many?" (or the sum) with a Sky read-aloud tile, the
 * dots as Lime circles on a panel (described by their number), four 112 dp Marigold picture tiles (a numeral over that
 * many dots); the prompt is read aloud when it appears and when the tile is tapped. Sprout: the sum at 68 sp, 2 by 2
 * Marigold tiles and the streak pill. Sapling: the expression, a ± tile and the answer box, "Right · Streak" and the
 * quiet number pad, whose Marigold "Go" checks the answer (answers can be negative).
 */
@Composable
internal fun ArithmeticGame(
    state: GamePlayUiState.Playing,
    ui: RoundUi.Arithmetic,
    header: @Composable () -> Unit,
    onEvent: (GamePlayEvent) -> Unit,
    readAloud: ReadAloud,
) {
    val tier = state.tier
    val round = ui.round
    val question = stringResource(R.string.feature_games_question_description, round.spokenPrompt)
    val spoken = if (round.dots != null) round.spokenPrompt else question
    if (tier == KidUiTier.SEEDLING) {
        val speech = rememberUpdatedState(readAloud)
        LaunchedEffect(state.roundKey) { if (speech.value !== ReadAloud.None) speech.value.speak(spoken) }
    }
    val choices = round.choices
    RoundLayout(
        header = header,
        prompt = {
            Prompt(ui, tier, spoken, readAloud)
            if (choices == null) {
                TypedAnswer(ui.typed, onEvent)
                TallyLine(right = state.right, streak = state.streak)
            }
        },
        answers = {
            if (choices == null) {
                // One primary action: the pad's "Go" checks the answer (as in Times tables).
                val canCheck = ui.typed.toIntOrNull() != null
                NumberPad(
                    onDigit = { onEvent(GamePlayEvent.Digit(it)) },
                    onBackspace = { onEvent(GamePlayEvent.Backspace) },
                    onGo = { onEvent(GamePlayEvent.SubmitAnswer) },
                    goEnabled = canCheck,
                )
            } else {
                Choices(choices, pictures = tier == KidUiTier.SEEDLING, onEvent = onEvent)
                if (tier == KidUiTier.SPROUT && state.streak > 0) StreakPill(count = state.streak)
            }
        },
    )
}

private val PadWidth = NumberPadDefaults.MaxWidth

@Composable
private fun Prompt(ui: RoundUi.Arithmetic, tier: KidUiTier, spoken: String, readAloud: ReadAloud) {
    val round = ui.round
    val counting = round.dots != null
    val readLabel = stringResource(R.string.feature_games_read_aloud)
    // Seedling: tapping the prompt (the question and its dots) reads it aloud too, as the prompt card did before the
    // Playground. The read-aloud tile sits inside it, so the two targets never sit side by side.
    val speakOnTap = if (tier == KidUiTier.SEEDLING && readAloud !== ReadAloud.None) {
        Modifier
            .heightIn(min = SproutTheme.tierTokens.minTouchTarget)
            .clickable(onClickLabel = readLabel, role = Role.Button) { readAloud.speak(spoken) }
    } else {
        Modifier
    }
    Column(
        modifier = Modifier.fillMaxWidth().then(speakOnTap),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { PromptContent(round, counting, tier, spoken, readAloud) }
}

@Composable
private fun PromptContent(
    round: ArithmeticRound,
    counting: Boolean,
    tier: KidUiTier,
    spoken: String,
    readAloud: ReadAloud,
) {
    val colors = Playground.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
    ) {
        Text(
            text = round.prompt,
            style = if (counting) PlaygroundType.amount else PlaygroundType.prompt,
            color = colors.chalk,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f, fill = false).semantics { contentDescription = spoken },
        )
        if (tier == KidUiTier.SEEDLING && readAloud !== ReadAloud.None) {
            ReadAloudTile(onClick = { readAloud.speak(spoken) })
        }
    }
    val dots = round.dots
    if (dots != null) CountingDots(dots)
}

/** The dots to count, Lime circles on a panel; TalkBack cannot count drawn dots, so it hears "5 dots" (review L8). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CountingDots(dots: Int) {
    val colors = Playground.colors
    val description = pluralStringResource(R.plurals.feature_games_dots, dots, dots)
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.fieldPanel, RoundedCornerShape(PlayDefaults.PanelCorner))
            .padding(24.dp)
            .clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(dots) { Box(modifier = Modifier.size(CountingDot).background(colors.lime, CircleShape)) }
    }
}

/** 2 by 2 Marigold answer tiles, each at most 180 dp wide; Seedling's draw that many dots under the numeral. */
@Composable
private fun Choices(choices: List<Int>, pictures: Boolean, onEvent: (GamePlayEvent) -> Unit) {
    val height = Playground.tokens.tileHeight
    Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = PlayLayoutDefaults.AnswersMaxWidth),
        verticalArrangement = Arrangement.spacedBy(PlayLayoutDefaults.TileGap),
    ) {
        choices.chunked(CHOICE_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(PlayLayoutDefaults.TileGap)) {
                row.forEach { value ->
                    PlayTile(
                        accent = Accent.Marigold,
                        onClick = { onEvent(GamePlayEvent.Choose(value)) },
                        contentPadding = PaddingValues(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(height)
                            .semantics { contentDescription = value.toString() },
                    ) { ChoiceLabel(value, pictures) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceLabel(value: Int, pictures: Boolean) {
    val ink = LocalContentColor.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.clearAndSetSemantics { },
    ) {
        Text(text = value.toString(), style = PlaygroundType.tileLarge.copy(color = ink))
        if (pictures && value in 1..MAX_CHOICE_DOTS) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) { repeat(value) { Dot(ChoiceDot, ink) } }
        }
    }
}

@Composable
private fun Dot(size: Dp, color: Color) {
    Box(modifier = Modifier.size(size).background(color, CircleShape))
}

/** Sapling's typed answer: the ± tile and the answer box on a panel. */
@Composable
private fun TypedAnswer(typed: String, onEvent: (GamePlayEvent) -> Unit) {
    val minus = stringResource(R.string.feature_games_minus)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = PadWidth)
            .background(Playground.colors.fieldPanel, RoundedCornerShape(PlayDefaults.PanelCorner))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PlayTile(
            accent = Accent.Panel,
            onClick = { onEvent(GamePlayEvent.ToggleMinus) },
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.height(actionHeight()).semantics { contentDescription = minus },
        ) {
            Text(
                text = "±",
                style = PlaygroundType.tile.copy(color = LocalContentColor.current),
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
        AnswerBox(
            typed = typed,
            onEvent = onEvent,
            canCheck = typed.toIntOrNull() != null,
            modifier = Modifier.weight(1f),
        )
    }
}

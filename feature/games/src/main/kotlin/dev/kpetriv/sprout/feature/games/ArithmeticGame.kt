package dev.kpetriv.sprout.feature.games

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.ReadAloud
import dev.kpetriv.sprout.core.ui.TierScaledText
import dev.kpetriv.sprout.core.ui.TierTextRole

/** Most dots drawn under a Seedling picture choice; bigger numbers show the numeral only. */
private const val MAX_CHOICE_DOTS = 10
private const val CHOICE_COLUMNS = 2

/**
 * An arithmetic round. Seedling: big picture choices (a numeral over that many dots), dots to count (described to
 * screen readers by their number), and the prompt read aloud when it appears and when tapped. Sprout: four numeric
 * choices. Sapling: a typed answer (with a minus button, answers can be negative) checked on IME done or "Check".
 */
@Composable
internal fun ArithmeticGame(
    ui: RoundUi.Arithmetic,
    tier: KidUiTier,
    roundKey: Long,
    onEvent: (GamePlayEvent) -> Unit,
    readAloud: ReadAloud,
) {
    val round = ui.round
    val question = stringResource(R.string.feature_games_question_description, round.spokenPrompt)
    val spoken = if (round.dots != null) round.spokenPrompt else question
    if (tier == KidUiTier.SEEDLING) {
        val speech = rememberUpdatedState(readAloud)
        LaunchedEffect(roundKey) { if (speech.value !== ReadAloud.None) speech.value.speak(spoken) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PromptCard(ui, tier, spoken, readAloud)
        val choices = round.choices
        if (choices == null) {
            TypedAnswer(ui.typed, onEvent)
        } else {
            Choices(choices, pictures = tier == KidUiTier.SEEDLING, tier = tier, onEvent = onEvent)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PromptCard(ui: RoundUi.Arithmetic, tier: KidUiTier, spoken: String, readAloud: ReadAloud) {
    val round = ui.round
    val speakOnTap = if (tier == KidUiTier.SEEDLING && readAloud !== ReadAloud.None) {
        Modifier.clickable { readAloud.speak(spoken) }
    } else {
        Modifier
    }
    Card(modifier = Modifier.fillMaxWidth().then(speakOnTap)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = round.prompt,
                    style = when (tier) {
                        KidUiTier.SEEDLING -> MaterialTheme.typography.displayMedium
                        KidUiTier.SPROUT -> MaterialTheme.typography.displaySmall
                        KidUiTier.SAPLING -> MaterialTheme.typography.headlineLarge
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f, fill = false).semantics { contentDescription = spoken },
                )
                if (tier == KidUiTier.SEEDLING) ReadAloudButton(spoken = spoken, readAloud = readAloud)
            }
            val dots = round.dots
            if (dots != null) {
                // TalkBack cannot count drawn dots, so the group says how many there are ("5 dots", review L8).
                val dotsDescription = pluralStringResource(R.plurals.feature_games_dots, dots, dots)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.clearAndSetSemantics { contentDescription = dotsDescription },
                ) {
                    repeat(dots) {
                        Text(text = "🔵", style = MaterialTheme.typography.headlineLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun Choices(choices: List<Int>, pictures: Boolean, tier: KidUiTier, onEvent: (GamePlayEvent) -> Unit) {
    val touch = SproutTheme.tierTokens.minTouchTarget
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        choices.chunked(CHOICE_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { value ->
                    FilledTonalButton(
                        onClick = { onEvent(GamePlayEvent.Choose(value)) },
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = touch * if (pictures) 2 else 1)
                            .semantics { contentDescription = value.toString() },
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clearAndSetSemantics { },
                        ) {
                            TierScaledText(text = value.toString(), role = TierTextRole.Balance, tier = tier)
                            if (pictures && value in 1..MAX_CHOICE_DOTS) {
                                Text(
                                    text = "●".repeat(value),
                                    style = MaterialTheme.typography.titleMedium,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TypedAnswer(typed: String, onEvent: (GamePlayEvent) -> Unit) {
    val touch = SproutTheme.tierTokens.minTouchTarget
    val minus = stringResource(R.string.feature_games_minus)
    val canCheck = typed.toIntOrNull() != null
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { onEvent(GamePlayEvent.ToggleMinus) },
                contentPadding = PaddingValues(4.dp),
                modifier = Modifier
                    .sizeIn(minWidth = touch, minHeight = touch)
                    .semantics { contentDescription = minus },
            ) { Text(text = "±", style = MaterialTheme.typography.titleLarge) }
            OutlinedTextField(
                value = typed,
                onValueChange = { onEvent(GamePlayEvent.AnswerChanged(it)) },
                label = { Text(stringResource(R.string.feature_games_answer_label)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canCheck) onEvent(GamePlayEvent.SubmitAnswer) }),
                modifier = Modifier.weight(1f),
            )
        }
        Button(
            onClick = { onEvent(GamePlayEvent.SubmitAnswer) },
            enabled = canCheck,
            modifier = Modifier.fillMaxWidth().heightIn(min = touch),
        ) { Text(stringResource(R.string.feature_games_check)) }
    }
}

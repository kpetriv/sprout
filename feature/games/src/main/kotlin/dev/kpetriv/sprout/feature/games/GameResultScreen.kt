package dev.kpetriv.sprout.feature.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.Confetti
import dev.kpetriv.sprout.core.ui.MoneyFormatter
import dev.kpetriv.sprout.core.ui.ReadAloud
import dev.kpetriv.sprout.core.ui.TierScaledText
import dev.kpetriv.sprout.core.ui.TierTextRole
import dev.kpetriv.sprout.core.ui.rememberMoneyFormatter

/**
 * The end of a play: the score ("You got 7 of 8!"), what it earned in tier language ("+8 coins" / "You earned
 * +£0.08"), and "Play again" / "Done". [Confetti] plays for a perfect play or any credit, never when the cap was
 * already reached (that play was "for fun", D-M5-7). A clamped credit says the daily limit was reached; a refused one
 * offers a retry. Seedling hears the score and the coins once they are known.
 */
@Composable
internal fun GameResultContent(
    state: GamePlayUiState.Result,
    onEvent: (GamePlayEvent) -> Unit,
    onFinished: () -> Unit,
    readAloud: ReadAloud,
) {
    val tier = state.tier
    val formatter = rememberMoneyFormatter()
    val score = state.score
    val scoreText = stringResource(R.string.feature_games_score, score.correct, score.total)
    val creditLines = creditLines(state, formatter)
    val credit = state.credit
    val celebrate = (credit is CreditUi.Credited && credit.amount.isPositive) ||
        (score.perfect && credit != CreditUi.CapReached && credit != CreditUi.Submitting)
    if (tier == KidUiTier.SEEDLING && credit != CreditUi.Submitting) {
        val spoken = (listOf(scoreText) + creditLines.map { it.text }).joinToString(" ")
        val speech = rememberUpdatedState(readAloud)
        LaunchedEffect(credit) { if (speech.value !== ReadAloud.None) speech.value.speak(spoken) }
    }
    var confettiDone by rememberSaveable(score.correct, score.total) { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Glyph(if (score.perfect) "🏆" else "⭐")
            TierScaledText(text = scoreText, role = TierTextRole.Title, tier = tier, textAlign = TextAlign.Center)
            if (score.perfect) BonusLine(stringResource(R.string.feature_games_perfect), tier)
            if (score.timedBonus) BonusLine(stringResource(R.string.feature_games_timed_bonus), tier)
            CreditBlock(credit, creditLines, tier, onEvent)
            ResultButtons(busy = credit == CreditUi.Submitting, onEvent = onEvent, onFinished = onFinished)
        }
        if (celebrate && !confettiDone) {
            Confetti(onFinished = { confettiDone = true }, modifier = Modifier.fillMaxSize())
        }
    }
}

/** One line of the credit block; [emphasis] lines use the big balance style. */
private data class CreditLine(val text: String, val emphasis: Boolean = false)

@Composable
private fun creditLines(state: GamePlayUiState.Result, formatter: MoneyFormatter): List<CreditLine> {
    val tier = state.tier
    val seedling = tier == KidUiTier.SEEDLING
    return when (val credit = state.credit) {
        CreditUi.Submitting -> listOf(CreditLine(stringResource(R.string.feature_games_saving)))

        is CreditUi.Credited -> {
            val earned = creditEarned(credit.amount, state.currency, tier, formatter)
            when {
                seedling && credit.clamped -> listOf(
                    CreditLine(earned, emphasis = true),
                    CreditLine(stringResource(R.string.feature_games_clamped_seedling)),
                )

                seedling -> listOf(CreditLine(earned, emphasis = true))

                credit.clamped -> listOf(CreditLine(stringResource(R.string.feature_games_clamped, earned)))

                credit.remainingAfter.isPositive -> listOf(
                    CreditLine(stringResource(R.string.feature_games_credited, earned), emphasis = true),
                    CreditLine(
                        stringResource(
                            R.string.feature_games_remaining,
                            creditAmount(credit.remainingAfter, state.currency, tier, formatter),
                        ),
                    ),
                )

                else -> listOf(CreditLine(stringResource(R.string.feature_games_credited, earned), emphasis = true))
            }
        }

        CreditUi.CapReached -> listOf(
            CreditLine(
                stringResource(if (seedling) R.string.feature_games_capped_seedling else R.string.feature_games_capped),
            ),
        )

        CreditUi.NoAccount -> listOf(CreditLine(stringResource(R.string.feature_games_no_account)))

        CreditUi.Refused -> listOf(CreditLine(stringResource(R.string.feature_games_refused)))

        CreditUi.Unknown -> listOf(CreditLine(stringResource(R.string.feature_games_unknown)))
    }
}

@Composable
private fun CreditBlock(credit: CreditUi, lines: List<CreditLine>, tier: KidUiTier, onEvent: (GamePlayEvent) -> Unit) {
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (credit == CreditUi.Submitting) CircularProgressIndicator(modifier = Modifier.size(32.dp))
        lines.forEach { line ->
            TierScaledText(
                text = line.text,
                role = if (line.emphasis) TierTextRole.Balance else TierTextRole.Body,
                tier = tier,
                textAlign = TextAlign.Center,
                color = if (line.emphasis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    if (credit == CreditUi.Refused) {
        Button(onClick = { onEvent(GamePlayEvent.RetryCredit) }) {
            TierScaledText(text = stringResource(R.string.feature_games_retry), role = TierTextRole.Label, tier = tier)
        }
    }
}

@Composable
private fun BonusLine(text: String, tier: KidUiTier) {
    TierScaledText(
        text = text,
        role = TierTextRole.Body,
        tier = tier,
        color = MaterialTheme.colorScheme.tertiary,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ResultButtons(busy: Boolean, onEvent: (GamePlayEvent) -> Unit, onFinished: () -> Unit) {
    val touch = SproutTheme.tierTokens.minTouchTarget
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = { onEvent(GamePlayEvent.PlayAgain) },
            enabled = !busy,
            modifier = Modifier.weight(1f).heightIn(min = touch),
        ) { TierScaledText(stringResource(R.string.feature_games_play_again), TierTextRole.Label, SproutTheme.kidTier) }
        // Leaving while the credit is saved would cancel it; Done waits for the result.
        Button(
            onClick = onFinished,
            enabled = !busy,
            modifier = Modifier.weight(1f).heightIn(min = touch),
        ) { TierScaledText(stringResource(R.string.feature_games_done), TierTextRole.Label, SproutTheme.kidTier) }
    }
}

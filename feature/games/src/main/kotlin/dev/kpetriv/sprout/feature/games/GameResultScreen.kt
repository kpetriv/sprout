package dev.kpetriv.sprout.feature.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.Accent
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.Confetti
import dev.kpetriv.sprout.core.ui.MoneyFormatter
import dev.kpetriv.sprout.core.ui.ReadAloud
import dev.kpetriv.sprout.core.ui.rememberMoneyFormatter
import dev.kpetriv.sprout.feature.games.playground.PlayTile
import dev.kpetriv.sprout.feature.games.playground.Playground
import dev.kpetriv.sprout.feature.games.playground.PlaygroundType
import dev.kpetriv.sprout.feature.games.playground.ReadAloudTile
import dev.kpetriv.sprout.feature.games.playground.RewardCard
import dev.kpetriv.sprout.feature.games.playground.Rosette

/**
 * The end of a play on the field (M9-games §3.1, "games-B1-result"), one column on every window: the rosette for a
 * perfect play (a Lime star otherwise, and always when the play was "for fun", D-M5-7), the score ("You got 7 of 8!"),
 * "Perfect!" and "Timed bonus!" in Lime, then what it earned: a credit is the Lime [RewardCard] (Seedling: "+8
 * pennies" alone, D-M5-10; Sprout and Sapling: the amount with today's remaining or the clamped line), every other
 * outcome (saving, for fun, refused, unknown) the same copy as before on a panel card, with "Try again" when it can be
 * retried. Then the outline "Play again" and the Marigold "Done". [Confetti] in Marigold, Sky and Coral plays for a
 * perfect play or any credit, never when the cap was already reached. Seedling hears the score and the credit once
 * they are known, and gets a read-aloud tile under the card.
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
    val credit = state.credit
    val outcome = creditOutcome(state, formatter)
    val spoken = (listOf(scoreText) + outcome.spoken).joinToString(" ")
    val forFun = credit.forFun
    val celebrate = celebrates(state)
    if (tier == KidUiTier.SEEDLING && credit != CreditUi.Submitting) {
        val speech = rememberUpdatedState(readAloud)
        LaunchedEffect(credit) { if (speech.value !== ReadAloud.None) speech.value.speak(spoken) }
    }
    var confettiDone by rememberSaveable(score.correct, score.total) { mutableStateOf(false) }
    val colors = Playground.colors
    Box(modifier = Modifier.fillMaxSize()) {
        FieldColumn(verticalArrangement = Arrangement.SpaceBetween) {
            Spacer(modifier = Modifier.size(0.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Not while saving either: a perfect play that turns out capped must not flicker from rosette to star.
                Rosette(perfect = score.perfect && !forFun && credit != CreditUi.Submitting)
                Text(
                    text = scoreText,
                    style = PlaygroundType.amount,
                    color = colors.chalk,
                    textAlign = TextAlign.Center,
                )
                if (score.perfect) BonusLine(stringResource(R.string.feature_games_perfect))
                if (score.timedBonus) BonusLine(stringResource(R.string.feature_games_timed_bonus))
                CreditCard(credit, outcome, seedling = tier == KidUiTier.SEEDLING)
                CreditActions(state, onEvent, readAloud, spoken)
            }
            ResultButtons(busy = credit == CreditUi.Submitting, onEvent = onEvent, onFinished = onFinished)
        }
        if (celebrate && !confettiDone) {
            Confetti(
                onFinished = { confettiDone = true },
                colors = listOf(colors.marigold, colors.sky, colors.coral),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** "For fun" plays (nothing earned, or the cap was already reached, D-M5-7) get no rosette and no confetti. */
private val CreditUi.forFun: Boolean get() = this == CreditUi.CapReached || this == CreditUi.NothingEarned

/** Confetti for any credit, or for a perfect play that was not "for fun" once it is saved. */
private fun celebrates(state: GamePlayUiState.Result): Boolean {
    val credit = state.credit
    return (credit is CreditUi.Credited && credit.amount.isPositive) ||
        (state.score.perfect && !credit.forFun && credit != CreditUi.Submitting)
}

/** "Try again" for a credit that can be retried, and Seedling's read-aloud tile once the credit is known. */
@Composable
private fun CreditActions(
    state: GamePlayUiState.Result,
    onEvent: (GamePlayEvent) -> Unit,
    readAloud: ReadAloud,
    spoken: String,
) {
    val credit = state.credit
    if (credit.retryable) {
        ActionTile(
            text = stringResource(R.string.feature_games_retry),
            onClick = { onEvent(GamePlayEvent.RetryCredit) },
            accent = Accent.Sky,
        )
    }
    if (state.tier == KidUiTier.SEEDLING && readAloud !== ReadAloud.None && credit != CreditUi.Submitting) {
        ReadAloudTile(onClick = { readAloud.speak(spoken) })
    }
}

/**
 * What the play earned, as the card shows it: a credit's [amount] with its [caption] (the reward card), or the
 * [message] of any other outcome; [spoken] is what Seedling hears after the score.
 */
private data class CreditOutcome(val amount: String? = null, val caption: String? = null, val message: String? = null) {
    val spoken: List<String> get() = listOfNotNull(amount, caption, message)
}

@Composable
private fun creditOutcome(state: GamePlayUiState.Result, formatter: MoneyFormatter): CreditOutcome {
    val seedling = state.tier == KidUiTier.SEEDLING
    return when (val credit = state.credit) {
        is CreditUi.Credited -> creditedOutcome(credit, state, formatter)

        CreditUi.Submitting -> CreditOutcome(message = stringResource(R.string.feature_games_saving))

        CreditUi.NothingEarned -> CreditOutcome(
            message = stringResource(
                if (seedling) R.string.feature_games_nothing_earned_seedling else R.string.feature_games_nothing_earned,
            ),
        )

        CreditUi.CapReached -> CreditOutcome(
            message = stringResource(
                if (seedling) R.string.feature_games_capped_seedling else R.string.feature_games_capped,
            ),
        )

        CreditUi.NoAccount -> CreditOutcome(message = stringResource(R.string.feature_games_no_account))

        CreditUi.Refused -> CreditOutcome(message = stringResource(R.string.feature_games_refused))

        CreditUi.CheckDate -> CreditOutcome(message = stringResource(R.string.feature_games_check_date))

        CreditUi.Unknown -> CreditOutcome(message = stringResource(R.string.feature_games_unknown))
    }
}

/** A positive credit: in full with what is left today, or clamped to the cap; Seedling counts minor units. */
@Composable
private fun creditedOutcome(
    credit: CreditUi.Credited,
    state: GamePlayUiState.Result,
    formatter: MoneyFormatter,
): CreditOutcome {
    val tier = state.tier
    val earned = creditEarned(credit.amount, state.currency, tier, formatter)
    val caption = when {
        tier == KidUiTier.SEEDLING && credit.clamped -> stringResource(R.string.feature_games_clamped_seedling)

        tier == KidUiTier.SEEDLING -> null

        credit.clamped -> stringResource(R.string.feature_games_clamped, earned)

        credit.remainingAfter.isPositive -> stringResource(
            R.string.feature_games_remaining,
            creditAmount(credit.remainingAfter, state.currency, tier, formatter),
        )

        else -> null
    }
    return CreditOutcome(amount = earned, caption = caption)
}

/** The reward card for a credit, a panel card with the copy for anything else; announced when it changes. */
@Composable
private fun CreditCard(credit: CreditUi, outcome: CreditOutcome, seedling: Boolean) {
    val live = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
    val amount = outcome.amount
    if (amount != null) {
        RewardCard(
            amount = amount,
            caption = outcome.caption,
            // Seedling's card holds the minor-unit words only (D-M5-10).
            label = if (seedling) null else stringResource(R.string.feature_games_game_reward),
            modifier = live,
        )
        return
    }
    PlayTile(
        accent = Accent.Panel,
        onClick = null,
        modifier = live.fillMaxWidth(),
        contentPadding = PaddingValues(20.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (credit == CreditUi.Submitting) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp), color = Playground.colors.lime)
            }
            Text(
                text = outcome.message.orEmpty(),
                style = PlaygroundType.figure,
                color = Playground.colors.chalk,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun BonusLine(text: String) {
    Text(text = text, style = PlaygroundType.heading, color = Playground.colors.lime, textAlign = TextAlign.Center)
}

@Composable
private fun ResultButtons(busy: Boolean, onEvent: (GamePlayEvent) -> Unit, onFinished: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PlayLayoutDefaults.TileGap)) {
        ActionTile(
            text = stringResource(R.string.feature_games_play_again),
            onClick = { onEvent(GamePlayEvent.PlayAgain) },
            accent = Accent.Outline,
            enabled = !busy,
            modifier = Modifier.weight(1f),
        )
        // Leaving while the credit is saved would cancel it; Done waits for the result.
        ActionTile(
            text = stringResource(R.string.feature_games_done),
            onClick = onFinished,
            enabled = !busy,
            icon = SproutIcons.ChevronRight,
            modifier = Modifier.weight(1f),
        )
    }
}

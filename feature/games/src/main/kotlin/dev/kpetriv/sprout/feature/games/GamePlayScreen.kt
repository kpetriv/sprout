package dev.kpetriv.sprout.feature.games

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.theme.ProvideKidTier
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.ReadAloud
import dev.kpetriv.sprout.feature.games.playground.CountdownRing
import dev.kpetriv.sprout.feature.games.playground.PlayField
import dev.kpetriv.sprout.feature.games.playground.PlaygroundTheme
import dev.kpetriv.sprout.feature.games.playground.SegmentProgress

/**
 * Navigation entry for one play of [kind] by [kidId] at [tier]: the start card, the rounds and the result, all in this
 * route. "Play again" starts a new play with a new seed; "Done" calls [onFinished].
 *
 * @param readAloud speaks Seedling prompts when they appear and when tapped; with [ReadAloud.None] the read-aloud
 *   tiles are hidden.
 */
@Composable
fun GamePlayRoute(
    kidId: KidId,
    tier: KidUiTier,
    kind: GameKind,
    onFinished: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    readAloud: ReadAloud = ReadAloud.None,
) {
    val args = Bundle().apply {
        putString(GameViewModel.KID_ID_KEY, kidId.value)
        putString(GameViewModel.TIER_KEY, tier.name)
        putString(GameViewModel.KIND_KEY, kind.name)
    }
    val viewModel = gamesViewModel<GameViewModel>(
        key = "games:play:${kidId.value}:${tier.name}:${kind.name}",
        args = args,
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    GamePlayScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onFinished = onFinished,
        onBack = onBack,
        readAloud = readAloud,
        modifier = modifier,
    )
}

/**
 * One play on the Playground (M9-games §3): [PlaygroundTheme] inside [ProvideKidTier], on a full-bleed [PlayField]
 * from the start card to the result. The start card (Times tables: [TablesSetupContent]), a round of
 * [ArithmeticGame], [MemoryGame], [WordGame] or [TablesGame] under the segmented progress (and the countdown ring in
 * timed mode and in Times tables), or the result. While the result says "Saving…" both the top bar's back and system
 * back are disabled (M5 review M1).
 */
@Composable
fun GamePlayScreen(
    state: GamePlayUiState,
    onEvent: (GamePlayEvent) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    readAloud: ReadAloud = ReadAloud.None,
) {
    // Leaving cannot cancel the credit any more (it runs on the app's scope), but the result is only shown here.
    val saving = (state as? GamePlayUiState.Result)?.credit == CreditUi.Submitting
    BackHandler(enabled = saving) { /* Wait for the credit: system back does nothing while it is saved. */ }
    ProvideKidTier(state.tier) {
        PlaygroundTheme(state.tier) {
            val setup = (state as? GamePlayUiState.Playing)?.setup
            PlayField(
                title = gameName(state.kind),
                onBack = onBack,
                backEnabled = !saving,
                modifier = modifier,
                trailing = if (setup != null) {
                    { SetupChip(setup) }
                } else {
                    null
                },
            ) {
                when (state) {
                    is GamePlayUiState.Start -> if (state.setup != null) {
                        TablesSetupContent(state, state.setup, onEvent)
                    } else {
                        StartCard(state, onEvent)
                    }

                    is GamePlayUiState.Playing -> PlayingContent(state, onEvent, readAloud)

                    is GamePlayUiState.Result -> GameResultContent(state, onEvent, onFinished, readAloud)

                    is GamePlayUiState.Unavailable -> UnavailableContent(onBack)
                }
            }
        }
    }
}

@Composable
private fun PlayingContent(state: GamePlayUiState.Playing, onEvent: (GamePlayEvent) -> Unit, readAloud: ReadAloud) {
    val header: @Composable () -> Unit = { RoundHeader(state) }
    when (val round = state.round) {
        is RoundUi.Arithmetic -> ArithmeticGame(state, round, header, onEvent, readAloud)
        is RoundUi.Memory -> MemoryGame(state, round, header, onEvent, readAloud)
        is RoundUi.Word -> WordGame(round, state.tier, header, onEvent)
        is RoundUi.Tables -> TablesGame(state, round, header, onEvent)
    }
}

/**
 * The progress row of a round: the segments with "3 of 8" and, once the round takes answers, the countdown ring in
 * timed mode; Times tables always show it (informational below Rock star, where a slow answer changes nothing).
 */
@Composable
private fun RoundHeader(state: GamePlayUiState.Playing) {
    val answering = (state.round as? RoundUi.Memory)?.showing != true
    val ring = answering && (state.timed || state.kind == GameKind.TABLES)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SegmentProgress(round = state.roundNumber, total = state.roundCount, modifier = Modifier.weight(1f))
        if (ring) CountdownRing(roundKey = state.roundKey, limitMs = state.timeLimitMs)
    }
}

@Composable
private fun UnavailableContent(onBack: () -> Unit) {
    ErrorContent(
        text = stringResource(R.string.feature_games_unavailable),
        onRetry = onBack,
        action = stringResource(R.string.feature_games_back),
    )
}

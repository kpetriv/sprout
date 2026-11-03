package dev.kpetriv.sprout.feature.games

import android.os.Bundle
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.theme.ProvideKidTier
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.ReadAloud
import dev.kpetriv.sprout.core.ui.TierScaledText
import dev.kpetriv.sprout.core.ui.TierTextRole

/**
 * Navigation entry for one play of [kind] by [kidId] at [tier]: the start card, the rounds and the result, all in this
 * route. "Play again" starts a new play with a new seed; "Done" calls [onFinished].
 *
 * @param readAloud speaks Seedling prompts when they appear and when tapped; with [ReadAloud.None] the read-aloud
 *   buttons are hidden.
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
 * One play inside [ProvideKidTier]: the start card (Sapling's "Timed" switch), a round of [ArithmeticGame],
 * [MemoryGame] or [WordGame] under a "3 of 8" progress line (and a time bar in timed mode), or the result.
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
    Scaffold(
        modifier = modifier,
        topBar = { GamesTopBar(title = gameName(state.kind), onBack = onBack) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            ProvideKidTier(state.tier) {
                when (state) {
                    is GamePlayUiState.Start -> StartCard(state, onEvent)
                    is GamePlayUiState.Playing -> PlayingContent(state, onEvent, readAloud)
                    is GamePlayUiState.Result -> GameResultContent(state, onEvent, onFinished, readAloud)
                    is GamePlayUiState.Unavailable -> UnavailableContent(onBack)
                }
            }
        }
    }
}

@Composable
private fun StartCard(state: GamePlayUiState.Start, onEvent: (GamePlayEvent) -> Unit) {
    val touch = SproutTheme.tierTokens.minTouchTarget
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Glyph(gameGlyph(state.kind))
                TierScaledText(
                    text = gameDescription(state.kind, state.tier),
                    role = TierTextRole.Title,
                    tier = state.tier,
                    textAlign = TextAlign.Center,
                )
                TierScaledText(
                    text = pluralStringResource(R.plurals.feature_games_rounds, state.rounds, state.rounds),
                    role = TierTextRole.Body,
                    tier = state.tier,
                )
                if (state.timedAvailable) TimedSwitch(state.timed, onEvent)
                Button(
                    onClick = { onEvent(GamePlayEvent.Start) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = touch),
                ) {
                    TierScaledText(
                        text = stringResource(R.string.feature_games_start),
                        role = TierTextRole.Title,
                        tier = state.tier,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimedSwitch(timed: Boolean, onEvent: (GamePlayEvent) -> Unit) {
    val label = stringResource(R.string.feature_games_timed)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            TierScaledText(text = label, role = TierTextRole.Title, tier = KidUiTier.SAPLING)
            TierScaledText(
                text = stringResource(R.string.feature_games_timed_description),
                role = TierTextRole.Label,
                tier = KidUiTier.SAPLING,
            )
        }
        Switch(
            checked = timed,
            onCheckedChange = { onEvent(GamePlayEvent.TimedChanged(it)) },
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

@Composable
private fun PlayingContent(state: GamePlayUiState.Playing, onEvent: (GamePlayEvent) -> Unit, readAloud: ReadAloud) {
    val progressDescription =
        stringResource(R.string.feature_games_progress_description, state.roundNumber, state.roundCount)
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LinearProgressIndicator(
                progress = { (state.roundNumber - 1).toFloat() / state.roundCount },
                modifier = Modifier.weight(1f),
            )
            TierScaledText(
                text = stringResource(R.string.feature_games_progress, state.roundNumber, state.roundCount),
                role = TierTextRole.Label,
                tier = state.tier,
                modifier = Modifier.semantics { contentDescription = progressDescription },
            )
        }
        val answering = (state.round as? RoundUi.Memory)?.showing != true
        if (state.timed && answering) TimeBar(state.roundKey, state.timeLimitMs)
        when (val round = state.round) {
            is RoundUi.Arithmetic -> ArithmeticGame(round, state.tier, state.roundKey, onEvent, readAloud)
            is RoundUi.Memory -> MemoryGame(round, state.tier, state.roundKey, onEvent, readAloud)
            is RoundUi.Word -> WordGame(round, state.tier, onEvent)
        }
    }
}

/** Drains from full to empty over the round's limit. Informational: a slow answer only loses the timed bonus. */
@Composable
private fun TimeBar(roundKey: Long, limitMs: Long) {
    val description = stringResource(R.string.feature_games_time_left)
    val left = remember(roundKey) { Animatable(1f) }
    LaunchedEffect(roundKey) {
        left.animateTo(0f, animationSpec = tween(limitMs.toInt(), easing = LinearEasing))
    }
    LinearProgressIndicator(
        progress = { left.value },
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
    )
}

@Composable
private fun UnavailableContent(onBack: () -> Unit) {
    ErrorContent(
        text = stringResource(R.string.feature_games_unavailable),
        onRetry = onBack,
        action = stringResource(R.string.feature_games_back),
    )
}

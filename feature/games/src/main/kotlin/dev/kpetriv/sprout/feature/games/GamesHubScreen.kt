package dev.kpetriv.sprout.feature.games

import android.os.Bundle
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.theme.ProvideKidTier
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.BalanceJar
import dev.kpetriv.sprout.core.ui.BalanceJarDefaults
import dev.kpetriv.sprout.core.ui.ReadAloud
import dev.kpetriv.sprout.core.ui.TierScaledText
import dev.kpetriv.sprout.core.ui.TierTextRole
import dev.kpetriv.sprout.core.ui.rememberMoneyFormatter

/**
 * Navigation entry for [kidId]'s brain games, rendered for [tier]. [onPlay] opens [GamePlayRoute] for a game.
 *
 * @param readAloud speaks Seedling text; with [ReadAloud.None] the read-aloud buttons are hidden.
 */
@Composable
fun GamesHubRoute(
    kidId: KidId,
    tier: KidUiTier,
    onPlay: (GameKind) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    readAloud: ReadAloud = ReadAloud.None,
) {
    val args = Bundle().apply {
        putString(GamesHubViewModel.KID_ID_KEY, kidId.value)
        putString(GamesHubViewModel.TIER_KEY, tier.name)
    }
    val viewModel = gamesViewModel<GamesHubViewModel>(key = "games:hub:${kidId.value}:${tier.name}", args = args)
    val state by viewModel.state.collectAsStateWithLifecycle()
    GamesHubScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onPlay = onPlay,
        onBack = onBack,
        readAloud = readAloud,
        modifier = modifier,
    )
}

/**
 * The games hub inside [ProvideKidTier]: what the kid can still earn today (Seedling: a jar counting named minor
 * units, "23 pennies", with read-aloud; Sprout and Sapling: an amount), or the cap-reached note, and one big tile per
 * game of the tier.
 */
@Composable
fun GamesHubScreen(
    state: GamesHubUiState,
    onEvent: (GamesHubEvent) -> Unit,
    onPlay: (GameKind) -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    readAloud: ReadAloud = ReadAloud.None,
) {
    Scaffold(
        modifier = modifier,
        topBar = { GamesTopBar(title = stringResource(R.string.feature_games_title), onBack = onBack) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                GamesHubUiState.Loading -> LoadingContent()

                GamesHubUiState.Error -> ErrorContent(
                    text = stringResource(R.string.feature_games_error_load_failed),
                    onRetry = { onEvent(GamesHubEvent.Retry) },
                )

                is GamesHubUiState.Ready -> ProvideKidTier(state.tier) {
                    HubContent(state = state, onPlay = onPlay, readAloud = readAloud)
                }
            }
        }
    }
}

@Composable
private fun HubContent(state: GamesHubUiState.Ready, onPlay: (GameKind) -> Unit, readAloud: ReadAloud) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.tier == KidUiTier.SEEDLING) SeedlingRemaining(state, readAloud) else ListRemaining(state)
        state.games.forEach { kind -> GameTile(kind = kind, tier = state.tier, onClick = { onPlay(kind) }) }
    }
}

@Composable
private fun SeedlingRemaining(state: GamesHubUiState.Ready, readAloud: ReadAloud) {
    val tier = KidUiTier.SEEDLING
    val formatter = rememberMoneyFormatter()
    val count = creditAmount(state.remaining, state.currency, tier, formatter)
    val text = if (state.capReached) {
        stringResource(R.string.feature_games_cap_reached_seedling)
    } else {
        stringResource(R.string.feature_games_remaining_seedling)
    }
    val spoken = if (state.capReached) text else stringResource(R.string.feature_games_remaining_seedling_spoken, count)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BalanceJar(
            level = BalanceJarDefaults.levelFor(state.remaining.minorUnits),
            countLabel = count,
            tier = tier,
            contentDescription = spoken,
            jarSize = 120.dp,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TierScaledText(
                text = text,
                role = TierTextRole.Body,
                tier = tier,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite },
            )
            ReadAloudButton(spoken = spoken, readAloud = readAloud)
        }
    }
}

@Composable
private fun ListRemaining(state: GamesHubUiState.Ready) {
    val formatter = rememberMoneyFormatter()
    val text = if (state.capReached) {
        stringResource(R.string.feature_games_cap_reached)
    } else {
        stringResource(
            R.string.feature_games_remaining,
            creditAmount(state.remaining, state.currency, state.tier, formatter),
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (state.capReached) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Glyph(if (state.capReached) "🌙" else "🪙")
            TierScaledText(
                text = text,
                role = TierTextRole.Body,
                tier = state.tier,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun GameTile(kind: GameKind, tier: KidUiTier, onClick: () -> Unit) {
    val name = gameName(kind)
    val description = gameDescription(kind, tier)
    val action = stringResource(R.string.feature_games_play_game, name)
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SproutTheme.tierTokens.minTouchTarget)
            .semantics { contentDescription = action },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Glyph(gameGlyph(kind))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TierScaledText(text = name, role = TierTextRole.Title, tier = tier)
                TierScaledText(text = description, role = TierTextRole.Label, tier = tier)
            }
        }
    }
}

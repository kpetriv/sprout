package dev.kpetriv.sprout.feature.games

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.components.CardMeaning
import dev.kpetriv.sprout.core.designsystem.components.Pictogram
import dev.kpetriv.sprout.core.designsystem.components.SproutCard
import dev.kpetriv.sprout.core.designsystem.components.SproutTopBar
import dev.kpetriv.sprout.core.designsystem.components.accentTint
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.ProvideKidTier
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.AdaptiveColumns
import dev.kpetriv.sprout.core.ui.BalanceJar
import dev.kpetriv.sprout.core.ui.BalanceJarDefaults
import dev.kpetriv.sprout.core.ui.ReadAloud
import dev.kpetriv.sprout.core.ui.contentWidth
import dev.kpetriv.sprout.core.ui.rememberMoneyFormatter

/**
 * Navigation entry for [kidId]'s brain games, rendered for [tier]. [onPlay] opens [GamePlayRoute] for a game. [onBack]
 * is null where the hub is a tab root (Sprout and Sapling, no back arrow) and set where it is pushed (Seedling).
 *
 * @param readAloud speaks Seedling text; with [ReadAloud.None] the read-aloud buttons are hidden.
 */
@Composable
fun GamesHubRoute(
    kidId: KidId,
    tier: KidUiTier,
    onPlay: (GameKind) -> Unit,
    onBack: (() -> Unit)?,
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
 * The games hub, a Morning garden screen (M9 §4, M9-games §1: the Playground begins when a game opens): what the kid
 * can still earn today (Seedling: a jar counting named minor units, "23 pennies", with read-aloud; Sprout and Sapling:
 * the amount on an Attention card), or the cap-reached note, and one card per game of the tier with its pictogram
 * (Times tables: the kid's tables, "3, 4, 6").
 */
@Composable
fun GamesHubScreen(
    state: GamesHubUiState,
    onEvent: (GamesHubEvent) -> Unit,
    onPlay: (GameKind) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    readAloud: ReadAloud = ReadAloud.None,
) {
    Scaffold(
        modifier = modifier,
        topBar = { SproutTopBar(title = stringResource(R.string.feature_games_title), onBack = onBack) },
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
            .contentWidth()
            .padding(horizontal = SproutSpacing.gutter, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(TILE_SPACING),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.tier == KidUiTier.SEEDLING) SeedlingRemaining(state, readAloud) else ListRemaining(state)
        // One column on a phone; two on a wider window (D-M7-2). Tiles keep the tier's touch target either way.
        AdaptiveColumns(
            minColumnWidth = GAME_TILE_MIN_WIDTH,
            spacing = TILE_SPACING,
            maxColumns = MAX_TILE_COLUMNS,
        ) { columns ->
            Column(verticalArrangement = Arrangement.spacedBy(TILE_SPACING)) {
                state.games.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(TILE_SPACING)) {
                        row.forEach { kind ->
                            GameTile(
                                kind = kind,
                                subtitle = gameDescription(kind, state.tier, state.tablesSetup),
                                onClick = { onPlay(kind) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

private val GAME_TILE_MIN_WIDTH = 280.dp
private val TILE_SPACING = 16.dp
private const val MAX_TILE_COLUMNS = 2
private val TilePictogram = 56.dp
private val BannerPictogram = 48.dp
private val SpeakerIcon = 32.dp

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
    SproutCard(meaning = CardMeaning.Attention, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BalanceJar(
                level = BalanceJarDefaults.levelFor(state.remaining.minorUnits),
                countLabel = count,
                tier = tier,
                contentDescription = spoken,
                jarSize = 120.dp,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (readAloud !== ReadAloud.None) SpeakerButton(onClick = { readAloud.speak(spoken) })
            }
        }
    }
}

/** A Leaf-tint speaker circle at the tier's touch size, read as "Read this to me" (as the Seedling chores have). */
@Composable
private fun SpeakerButton(onClick: () -> Unit) {
    val tint = CardMeaning.Money.accentTint()
    val description = stringResource(R.string.feature_games_read_aloud)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = tint.container,
        contentColor = tint.content,
        modifier = Modifier
            .size(SproutTheme.tierTokens.minTouchTarget)
            .semantics {
                role = Role.Button
                contentDescription = description
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(SproutIcons.VolumeUp, contentDescription = null, modifier = Modifier.size(SpeakerIcon))
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
    SproutCard(
        meaning = if (state.capReached) CardMeaning.Content else CardMeaning.Attention,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Pictogram(
                pictogram = if (state.capReached) Pictogram.MOON else Pictogram.TROPHY,
                size = BannerPictogram,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun GameTile(kind: GameKind, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val name = gameName(kind)
    // The subtitle is part of what TalkBack reads ("Play Times tables, 3, 4, 6"): the card's description replaces
    // its texts.
    val action = stringResource(R.string.feature_games_play_game_detail, name, subtitle)
    SproutCard(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SproutTheme.tierTokens.minTouchTarget)
            .semantics { contentDescription = action },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Pictogram(pictogram = gamePictogram(kind), size = TilePictogram)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = name, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(SproutIcons.ChevronRight, contentDescription = null)
        }
    }
}

private fun gamePictogram(kind: GameKind): Pictogram = when (kind) {
    GameKind.ARITHMETIC -> Pictogram.GAME_NUMBERS
    GameKind.MEMORY -> Pictogram.GAME_MEMORY
    GameKind.WORDS -> Pictogram.GAME_WORDS
    GameKind.TABLES -> Pictogram.GAME_TABLES
}

package dev.kpetriv.sprout.home

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.R
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.AgeBand
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.startup.StartupFailure

/** Home navigation entry: the kid list, backed by a [HomeViewModel] scoped to this entry. */
@Composable
fun HomeRoute(onKidClick: (KidId) -> Unit, modifier: Modifier = Modifier) {
    val viewModel: HomeViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeScreen(state = state, onKidClick = onKidClick, onEvent = viewModel::onEvent, modifier = modifier)
}

/**
 * The parent's home: every kid with age band and UI tier; tapping a row opens its wallet. A startup failure shows
 * as an error card with a retry button above the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onKidClick: (KidId) -> Unit,
    modifier: Modifier = Modifier,
    onEvent: (HomeEvent) -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.home_title)) }) },
    ) { innerPadding ->
        val contentModifier = Modifier.fillMaxSize().padding(innerPadding)
        when (state) {
            HomeUiState.Loading -> Box(contentModifier, contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            is HomeUiState.Error -> Column(contentModifier) {
                state.startupFailure?.let { StartupErrorCard(it, onRetry = { onEvent(HomeEvent.Retry) }) }
                CenteredMessage(
                    R.string.home_error,
                    Modifier.fillMaxWidth().weight(1f),
                    onRetry = { onEvent(HomeEvent.Retry) }.takeIf { state.startupFailure == null },
                )
            }

            is HomeUiState.Ready -> Column(contentModifier) {
                state.startupFailure?.let { StartupErrorCard(it, onRetry = { onEvent(HomeEvent.Retry) }) }
                if (state.kids.isEmpty()) {
                    CenteredMessage(R.string.home_empty, Modifier.fillMaxWidth().weight(1f))
                } else {
                    KidList(state.kids, onKidClick, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun KidList(kids: List<KidRow>, onKidClick: (KidId) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier) {
        items(kids, key = { it.id.value }) { kid ->
            ListItem(
                headlineContent = { Text(kid.firstName) },
                supportingContent = { Text(stringResource(kid.ageBand.labelRes())) },
                // A label, not a button: the row is the only focus and click target.
                trailingContent = { TierLabel(stringResource(kid.tier.labelRes())) },
                modifier = Modifier.clickable { onKidClick(kid.id) },
            )
            HorizontalDivider()
        }
    }
}

/** The tier shown like a chip but with no click action, so it is not a separate focus target. */
@Composable
private fun TierLabel(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun StartupErrorCard(failure: StartupFailure, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.home_startup_failed_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(failure.messageRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(onClick = onRetry) { Text(stringResource(R.string.home_retry)) }
        }
    }
}

@Composable
private fun CenteredMessage(@StringRes text: Int, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(
        modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
        if (onRetry != null) Button(onClick = onRetry) { Text(stringResource(R.string.home_retry)) }
    }
}

@StringRes
private fun StartupFailure.messageRes(): Int = when (this) {
    StartupFailure.KEYS -> R.string.home_startup_failed_keys
    StartupFailure.DATABASE -> R.string.home_startup_failed_database
    StartupFailure.UNKNOWN -> R.string.home_startup_failed_unknown
}

@StringRes
private fun AgeBand?.labelRes(): Int = when (this) {
    null -> R.string.home_age_band_unknown
    AgeBand.UNDER_5 -> R.string.home_age_band_under_5
    AgeBand.AGES_5_TO_7 -> R.string.home_age_band_5_to_7
    AgeBand.AGES_8_TO_11 -> R.string.home_age_band_8_to_11
    AgeBand.AGES_12_PLUS -> R.string.home_age_band_12_plus
}

@StringRes
private fun KidUiTier.labelRes(): Int = when (this) {
    KidUiTier.SEEDLING -> R.string.home_tier_seedling
    KidUiTier.SPROUT -> R.string.home_tier_sprout
    KidUiTier.SAPLING -> R.string.home_tier_sapling
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    SproutTheme {
        HomeScreen(
            state = HomeUiState.Ready(
                listOf(
                    KidRow(KidId("k1"), "Mia", AgeBand.AGES_5_TO_7, KidUiTier.SEEDLING),
                    KidRow(KidId("k2"), "Leo", AgeBand.AGES_8_TO_11, KidUiTier.SPROUT),
                    KidRow(KidId("k3"), "Zoe", AgeBand.AGES_12_PLUS, KidUiTier.SAPLING),
                ),
            ),
            onKidClick = {},
        )
    }
}

package dev.kpetriv.sprout.feature.games

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.kpetriv.sprout.core.ui.SproutPlaceholder

/** Entry point of the brain games feature. Placeholder until its milestone lands. */
@Composable
fun GamesScreen(modifier: Modifier = Modifier) {
    SproutPlaceholder(
        title = stringResource(R.string.feature_games_title),
        modifier = modifier,
    )
}

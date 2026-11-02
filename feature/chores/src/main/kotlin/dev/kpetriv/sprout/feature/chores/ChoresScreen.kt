package dev.kpetriv.sprout.feature.chores

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.kpetriv.sprout.core.ui.SproutPlaceholder

/** Entry point of the chores feature. Placeholder until its milestone lands. */
@Composable
fun ChoresScreen(modifier: Modifier = Modifier) {
    SproutPlaceholder(
        title = stringResource(R.string.feature_chores_title),
        modifier = modifier,
    )
}

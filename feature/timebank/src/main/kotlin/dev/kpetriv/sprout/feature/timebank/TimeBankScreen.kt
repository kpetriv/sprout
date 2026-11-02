package dev.kpetriv.sprout.feature.timebank

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.kpetriv.sprout.core.ui.SproutPlaceholder

/** Entry point of the time bank feature. Placeholder until its milestone lands. */
@Composable
fun TimeBankScreen(modifier: Modifier = Modifier) {
    SproutPlaceholder(
        title = stringResource(R.string.feature_timebank_title),
        modifier = modifier,
    )
}

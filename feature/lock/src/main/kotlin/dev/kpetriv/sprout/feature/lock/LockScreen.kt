package dev.kpetriv.sprout.feature.lock

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.kpetriv.sprout.core.ui.SproutPlaceholder

/** Entry point of the parent lock feature. Placeholder until its milestone lands. */
@Composable
fun LockScreen(modifier: Modifier = Modifier) {
    SproutPlaceholder(
        title = stringResource(R.string.feature_lock_title),
        modifier = modifier,
    )
}

package dev.kpetriv.sprout.feature.parenthome

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.kpetriv.sprout.core.ui.SproutPlaceholder

/** Entry point of the family dashboard feature. Placeholder until its milestone lands. */
@Composable
fun ParentHomeScreen(modifier: Modifier = Modifier) {
    SproutPlaceholder(
        title = stringResource(R.string.feature_parent_home_title),
        modifier = modifier,
    )
}

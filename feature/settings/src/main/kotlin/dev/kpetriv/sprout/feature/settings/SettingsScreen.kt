package dev.kpetriv.sprout.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.kpetriv.sprout.core.ui.SproutPlaceholder

/** Entry point of the settings feature. Placeholder until its milestone lands. */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    SproutPlaceholder(
        title = stringResource(R.string.feature_settings_title),
        modifier = modifier,
    )
}

package dev.kpetriv.sprout.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.kpetriv.sprout.core.ui.SproutPlaceholder

/** Entry point of the welcome to sprout feature. Placeholder until its milestone lands. */
@Composable
fun OnboardingScreen(modifier: Modifier = Modifier) {
    SproutPlaceholder(
        title = stringResource(R.string.feature_onboarding_title),
        modifier = modifier,
    )
}

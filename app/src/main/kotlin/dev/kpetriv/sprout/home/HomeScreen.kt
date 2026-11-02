package dev.kpetriv.sprout.home

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.kpetriv.sprout.R
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.ui.SproutPlaceholder

/** Placeholder home screen until onboarding and the role switch land. */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    Scaffold(modifier = modifier) { innerPadding ->
        SproutPlaceholder(
            title = stringResource(R.string.app_name),
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    SproutTheme { HomeScreen() }
}

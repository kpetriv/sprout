package dev.kpetriv.sprout.feature.timebank

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier

@Preview(name = "Seedling running", showBackground = true)
@Composable
private fun SeedlingRunningPreview() = SproutTheme {
    TimeBankScreen(state = TimeBankPreviewData.running(KidUiTier.SEEDLING), onEvent = {})
}

@Preview(name = "Sprout idle", showBackground = true)
@Composable
private fun SproutIdlePreview() = SproutTheme {
    TimeBankScreen(state = TimeBankPreviewData.idle(KidUiTier.SPROUT), onEvent = {})
}

@Preview(name = "Sapling empty", showBackground = true)
@Composable
private fun SaplingEmptyPreview() = SproutTheme {
    TimeBankScreen(state = TimeBankPreviewData.empty(KidUiTier.SAPLING), onEvent = {})
}

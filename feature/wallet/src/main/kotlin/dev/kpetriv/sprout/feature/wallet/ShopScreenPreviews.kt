package dev.kpetriv.sprout.feature.wallet

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier

@Composable
private fun ShopPreview(state: ShopUiState) {
    SproutTheme { ShopScreen(state = state, onEvent = {}) }
}

@Preview(name = "Seedling, can afford", showBackground = true, heightDp = 900)
@Composable
private fun ShopSeedlingPreview() = ShopPreview(ShopPreviewData.ready(KidUiTier.SEEDLING, affordable = true))

@Preview(name = "Seedling, can't afford", showBackground = true, heightDp = 900)
@Composable
private fun ShopSeedlingPoorPreview() = ShopPreview(ShopPreviewData.ready(KidUiTier.SEEDLING, affordable = false))

@Preview(name = "Sprout, can't afford", showBackground = true, heightDp = 900)
@Composable
private fun ShopSproutPreview() = ShopPreview(ShopPreviewData.ready(KidUiTier.SPROUT, affordable = false))

@Preview(name = "Sapling, can't afford", showBackground = true, heightDp = 1100)
@Composable
private fun ShopSaplingPreview() = ShopPreview(ShopPreviewData.ready(KidUiTier.SAPLING, affordable = false))

@Preview(name = "Parent browsing", showBackground = true, heightDp = 900)
@Composable
private fun ShopParentPreview() =
    ShopPreview(ShopPreviewData.ready(KidUiTier.SPROUT, affordable = true, canPurchase = false))

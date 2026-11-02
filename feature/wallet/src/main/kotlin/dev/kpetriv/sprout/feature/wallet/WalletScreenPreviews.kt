package dev.kpetriv.sprout.feature.wallet

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier

@Composable
private fun WalletPreview(state: WalletUiState) {
    SproutTheme { WalletScreen(state = state, onEvent = {}) }
}

@Preview(name = "Seedling", showBackground = true, heightDp = 900)
@Composable
private fun WalletSeedlingPreview() = WalletPreview(WalletPreviewData.ready(KidUiTier.SEEDLING))

@Preview(name = "Seedling, kid mode, large font", showBackground = true, heightDp = 900, fontScale = 1.5f)
@Composable
private fun WalletSeedlingLargeFontPreview() =
    WalletPreview(WalletPreviewData.ready(KidUiTier.SEEDLING, canAdjust = false))

@Preview(name = "Sprout", showBackground = true, heightDp = 1100)
@Composable
private fun WalletSproutPreview() = WalletPreview(WalletPreviewData.ready(KidUiTier.SPROUT))

@Preview(name = "Sapling", showBackground = true, heightDp = 1400)
@Composable
private fun WalletSaplingPreview() = WalletPreview(WalletPreviewData.ready(KidUiTier.SAPLING, anomaliesCount = 1))

@Preview(name = "Sapling, filtered", showBackground = true, heightDp = 1100)
@Composable
private fun WalletSaplingFilteredPreview() =
    WalletPreview(WalletPreviewData.ready(KidUiTier.SAPLING, filter = EventCategory.SHOP))

@Preview(name = "Loading", showBackground = true)
@Composable
private fun WalletLoadingPreview() = WalletPreview(WalletUiState.Loading)

@Preview(name = "Error", showBackground = true)
@Composable
private fun WalletErrorPreview() = WalletPreview(WalletUiState.Error(WalletError.LOAD_FAILED))

@Preview(name = "Adjustment form", showBackground = true)
@Composable
private fun ManualAdjustmentFormPreview() {
    SproutTheme {
        Surface {
            ManualAdjustmentForm(
                adjustment = WalletPreviewData.adjustment,
                onSubmit = { _, _, _ -> },
                onCancel = {},
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Preview(name = "Adjustment form, rejected", showBackground = true)
@Composable
private fun ManualAdjustmentFormErrorPreview() {
    SproutTheme {
        Surface {
            ManualAdjustmentForm(
                adjustment = WalletPreviewData.adjustment.copy(error = AdjustmentError.InsufficientFunds("£7.45")),
                onSubmit = { _, _, _ -> },
                onCancel = {},
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

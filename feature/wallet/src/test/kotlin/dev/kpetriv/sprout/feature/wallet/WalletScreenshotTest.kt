package dev.kpetriv.sprout.feature.wallet

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotTest
import dev.kpetriv.sprout.core.ui.ReadAloud
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Golden screenshots of the wallet and the shop, in src/test/screenshots. Ready states use a taller window so every
 * section of the scrolling content is in the image. Re-record with `./gradlew :feature:wallet:recordRoborazziDebug`.
 */
class WalletScreenshotTest : ScreenshotTest() {
    @Test
    @Config(qualifiers = TALL)
    fun `ready, kid mode, every tier`() = screenshot.captureTiers("wallet_ready_kid") { tier ->
        WalletScreen(state = WalletPreviewData.ready(tier, canAdjust = false), onEvent = {}, readAloud = speech)
    }

    @Test
    @Config(qualifiers = TALL)
    fun `ready, parent view, every tier`() = screenshot.captureTiers("wallet_ready_parent") { tier ->
        WalletScreen(state = WalletPreviewData.ready(tier, anomaliesCount = 1), onEvent = {}, readAloud = speech)
    }

    @Test
    fun loading() = screenshot.captureLightDark("wallet_loading") {
        WalletScreen(state = WalletUiState.Loading, onEvent = {})
    }

    @Test
    fun error() = screenshot.captureLightDark("wallet_error") {
        WalletScreen(state = WalletUiState.Error(WalletError.LOAD_FAILED), onEvent = {})
    }

    @Test
    fun `adjustment form`() = screenshot.captureLightDark("wallet_adjustment_form") {
        ManualAdjustmentForm(
            adjustment = WalletPreviewData.adjustment,
            onSubmit = { _, _, _ -> },
            onCancel = {},
            modifier = Modifier.padding(24.dp),
        )
    }

    @Test
    fun `adjustment form, prefilled from a receipt`() = screenshot.captureLightDark("wallet_adjustment_prefilled") {
        ManualAdjustmentForm(
            adjustment = WalletPreviewData.adjustmentPrefilled,
            onSubmit = { _, _, _ -> },
            onCancel = {},
            modifier = Modifier.padding(24.dp),
            onScanReceipt = {},
        )
    }

    @Test
    fun `adjustment form, rejected by the ledger`() = screenshot.captureLightDark("wallet_adjustment_form_rejected") {
        ManualAdjustmentForm(
            adjustment = WalletPreviewData.adjustment.copy(error = AdjustmentError.InsufficientFunds("£7.45")),
            onSubmit = { _, _, _ -> },
            onCancel = {},
            modifier = Modifier.padding(24.dp),
        )
    }

    @Test
    fun `adjustment form, parent signatures off`() = screenshot.captureLightDark("wallet_adjustment_form_unsigned") {
        ManualAdjustmentForm(
            adjustment = WalletPreviewData.adjustment.copy(unsignedWarning = true),
            onSubmit = { _, _, _ -> },
            onCancel = {},
            modifier = Modifier.padding(24.dp),
        )
    }

    @Test
    @Config(qualifiers = TALL)
    fun `shop, can afford, every tier`() = screenshot.captureTiers("shop_afford") { tier ->
        ShopScreen(state = ShopPreviewData.ready(tier, affordable = true), onEvent = {})
    }

    @Test
    @Config(qualifiers = TALL)
    fun `shop, cannot afford, every tier`() = screenshot.captureTiers("shop_cannot_afford") { tier ->
        ShopScreen(state = ShopPreviewData.ready(tier, affordable = false), onEvent = {})
    }

    private companion object {
        const val TALL = "+h1500dp"

        // Shows Seedling's "Read to me" button, which is hidden without a speech implementation.
        val speech = ReadAloud { }
    }
}

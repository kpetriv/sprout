package dev.kpetriv.sprout.feature.wallet

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotTest
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotWindow
import dev.kpetriv.sprout.core.ui.ReadAloud
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Golden screenshots of the wallet and the shop, in src/test/screenshots. Ready states use a taller window so every
 * section of the scrolling content is in the image. Re-record with `./gradlew :feature:wallet:recordRoborazziDebug`.
 */
class WalletScreenshotTest : ScreenshotTest(checkAccessibility = true) {
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

    @Test
    fun `kid wallet, loading, every tier`() = screenshot.captureTiers("wallet_loading_kid") {
        WalletScreen(state = WalletUiState.Loading, onEvent = {})
    }

    @Test
    fun `kid wallet, could not load, every tier`() = screenshot.captureTiers("wallet_error_kid") {
        WalletScreen(state = WalletUiState.Error(WalletError.LOAD_FAILED), onEvent = {})
    }

    @Test
    @Config(qualifiers = TALL)
    fun `kid wallet, no history yet, every tier`() = screenshot.captureTiers("wallet_history_empty") { tier ->
        WalletScreen(state = WalletPreviewData.noHistory(tier), onEvent = {}, readAloud = speech)
    }

    @Test
    @Config(qualifiers = TALL)
    fun `kid wallet, savings goal reached, every tier`() = screenshot.captureTiers("wallet_goal_reached") { tier ->
        WalletScreen(state = WalletPreviewData.goalReached(tier), onEvent = {}, readAloud = speech)
    }

    @Test
    @Config(qualifiers = TALL)
    fun `parent view, game plays not counted, every tier`() = screenshot.captureTiers("wallet_not_counted") { tier ->
        WalletScreen(state = WalletPreviewData.notCounted(tier), onEvent = {}, readAloud = speech)
    }

    @Test
    fun `kid wallet on a tablet, every tier`() = screenshot.captureWidthClassTiers(
        name = "wallet_ready_kid",
        windows = listOf(ScreenshotWindow.TABLET),
    ) { _, tier ->
        WalletScreen(state = WalletPreviewData.ready(tier, canAdjust = false), onEvent = {}, readAloud = speech)
    }

    @Test
    @Config(qualifiers = TALL)
    fun `kid wallet at font scale 2, every tier`() = screenshot.captureFontScaleTiers("wallet_ready_kid") { tier ->
        WalletScreen(state = WalletPreviewData.ready(tier, canAdjust = false), onEvent = {}, readAloud = speech)
    }

    @Test
    fun `shop, loading, every tier`() = screenshot.captureTiers("shop_loading") {
        ShopScreen(state = ShopUiState.Loading, onEvent = {})
    }

    @Test
    fun `shop, could not load, every tier`() = screenshot.captureTiers("shop_error") {
        ShopScreen(state = ShopUiState.Error(ShopError.LOAD_FAILED), onEvent = {})
    }

    @Test
    fun `shop, no rewards yet, every tier`() = screenshot.captureTiers("shop_empty") { tier ->
        ShopScreen(state = ShopPreviewData.ready(tier, affordable = true).copy(items = emptyList()), onEvent = {})
    }

    @Test
    fun `shop, confirm a purchase, every tier`() = screenshot.captureTiers("shop_confirm", screen = true) { tier ->
        val ready = ShopPreviewData.ready(tier, affordable = true)
        ShopScreen(state = ready.copy(selected = ready.items.first { it.canAfford }), onEvent = {})
    }

    @Test
    fun `shop, not yet, every tier`() = screenshot.captureTiers("shop_not_yet", screen = true) { tier ->
        val ready = ShopPreviewData.ready(tier, affordable = false)
        ShopScreen(state = ready.copy(selected = ready.items.last { !it.canAfford }), onEvent = {})
    }

    @Test
    fun `shop, bought, every tier`() = screenshot.captureTiers("shop_outcome_bought", screen = true) { tier ->
        val ready = ShopPreviewData.ready(tier, affordable = true)
        ShopScreen(
            state = ready.copy(message = ShopMessage.Purchased("30 min tablet time", minutesText = "30 min")),
            onEvent = {},
        )
    }

    @Test
    fun `shop, purchase failed, every tier`() = screenshot.captureTiers("shop_outcome_failed", screen = true) { tier ->
        val ready = ShopPreviewData.ready(tier, affordable = false)
        val failure = PurchaseFailure.NotEnoughMoney(ready.items.last().shortfallText)
        ShopScreen(state = ready.copy(message = ShopMessage.Failed(failure)), onEvent = {})
    }

    @Test
    @Config(qualifiers = TALL)
    fun `shop, parent browsing, every tier`() = screenshot.captureTiers("shop_parent") { tier ->
        ShopScreen(state = ShopPreviewData.ready(tier, affordable = true, canPurchase = false), onEvent = {})
    }

    private companion object {
        const val TALL = "+h1500dp"

        // Shows Seedling's "Read to me" button, which is hidden without a speech implementation.
        val speech = ReadAloud { }
    }
}

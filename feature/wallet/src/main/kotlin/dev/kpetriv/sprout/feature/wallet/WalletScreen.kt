package dev.kpetriv.sprout.feature.wallet

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.theme.ProvideKidTier
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.ReadAloud

/**
 * Navigation entry for the wallet of [kidId]. Creates a [WalletViewModel] scoped to the current
 * `ViewModelStoreOwner` (the nav entry), passing [kidId] and [mode] through its `SavedStateHandle`.
 *
 * @param mode [WalletMode.PARENT] lets the viewer make manual adjustments; M1 passes PARENT (no lock yet).
 * @param readAloud speaks Seedling text aloud; no text-to-speech implementation exists yet, and with
 *   [ReadAloud.None] the Seedling "Read to me" button is hidden.
 * @param onExport Sapling "Export CSV"; not wired to an exporter yet.
 * @param onOpenShop the Shop button (every tier); the app navigates to [ShopRoute] for the same kid and mode.
 * @param onOpenChores the Chores button (every tier); the app navigates to the kid's chores ("Today").
 */
@Composable
fun WalletRoute(
    kidId: KidId,
    mode: WalletMode,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    readAloud: ReadAloud = ReadAloud.None,
    onExport: () -> Unit = {},
    onOpenShop: () -> Unit = {},
    onOpenChores: () -> Unit = {},
) {
    val viewModel = featureViewModel<WalletViewModel>(
        key = "wallet:${kidId.value}:${mode.name}",
        args = Bundle().apply {
            putString(WalletViewModel.KID_ID_KEY, kidId.value)
            putString(WalletViewModel.MODE_KEY, mode.name)
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    WalletScreen(
        state = state,
        onEvent = viewModel::onEvent,
        modifier = modifier,
        onBack = onBack,
        readAloud = readAloud,
        onExport = onExport,
        onOpenShop = onOpenShop,
        onOpenChores = onOpenChores,
    )
}

/**
 * The wallet for any [WalletUiState]. Ready content renders inside [ProvideKidTier] for the kid's tier and switches
 * to [SeedlingWalletContent], [SproutWalletContent] or [SaplingWalletContent]; the parent-only
 * [ManualAdjustmentSheet] keeps standard (parent) sizing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    state: WalletUiState,
    onEvent: (WalletEvent) -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    readAloud: ReadAloud = ReadAloud.None,
    onExport: () -> Unit = {},
    onOpenShop: () -> Unit = {},
    onOpenChores: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val ready = state as? WalletUiState.Ready
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = ready?.let { stringResource(R.string.feature_wallet_title_named, it.kid.firstName) }
                            ?: stringResource(R.string.feature_wallet_title),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.feature_wallet_ic_arrow_back),
                            contentDescription = stringResource(R.string.feature_wallet_back),
                        )
                    }
                },
                actions = {
                    if (ready?.canAdjust == true) {
                        TextButton(onClick = { onEvent(WalletEvent.OpenAdjust) }) {
                            Text(stringResource(R.string.feature_wallet_adjust))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                WalletUiState.Loading -> LoadingContent()

                is WalletUiState.Error -> ErrorContent(state.reason, onRetry = { onEvent(WalletEvent.Refresh) })

                is WalletUiState.Ready -> ReadyContent(
                    state = state,
                    onEvent = onEvent,
                    readAloud = readAloud,
                    actions = WalletActions(onExport = onExport, onOpenShop = onOpenShop, onOpenChores = onOpenChores),
                )
            }
        }
    }

    if (ready != null) {
        val message = ready.message
        val messageText = when (message) {
            null -> null
            WalletMessage.AdjustmentSaved -> stringResource(R.string.feature_wallet_adjustment_saved)
            is WalletMessage.AdjustmentFailed -> message.error.message()
        }
        LaunchedEffect(message, messageText) {
            if (messageText != null) {
                snackbarHostState.showSnackbar(messageText)
                onEvent(WalletEvent.MessageShown)
            }
        }
        val adjustment = ready.adjustment
        if (ready.canAdjust && adjustment != null) {
            ManualAdjustmentSheet(
                adjustment = adjustment,
                onSubmit = { amountMinor, sign, reason ->
                    onEvent(WalletEvent.SubmitAdjust(amountMinor, sign, reason))
                },
                onDismiss = { onEvent(WalletEvent.DismissAdjust) },
                onReceiptPicked = { onEvent(WalletEvent.ReceiptPicked(it)) },
                onPrefillApplied = { onEvent(WalletEvent.PrefillApplied) },
            )
        }
    }
}

/** Navigation-style callbacks of the ready wallet. */
private class WalletActions(val onExport: () -> Unit, val onOpenShop: () -> Unit, val onOpenChores: () -> Unit)

@Composable
private fun ReadyContent(
    state: WalletUiState.Ready,
    onEvent: (WalletEvent) -> Unit,
    readAloud: ReadAloud,
    actions: WalletActions,
) {
    ProvideKidTier(state.tier) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.canAdjust && state.anomaliesCount > 0) AnomaliesBanner(state.anomaliesCount)
            when (state.tier) {
                KidUiTier.SEEDLING -> SeedlingWalletContent(
                    state = state,
                    readAloud = readAloud,
                    onOpenChores = actions.onOpenChores,
                    onOpenShop = actions.onOpenShop,
                )

                KidUiTier.SPROUT -> SproutWalletContent(
                    state = state,
                    onOpenChores = actions.onOpenChores,
                    onOpenShop = actions.onOpenShop,
                )

                KidUiTier.SAPLING -> SaplingWalletContent(
                    state = state,
                    onFilterChange = { onEvent(WalletEvent.FilterChanged(it)) },
                    onExport = actions.onExport,
                    onOpenChores = actions.onOpenChores,
                    onOpenShop = actions.onOpenShop,
                )
            }
        }
    }
}

@Composable
private fun AnomaliesBanner(count: Int) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = pluralStringResource(R.plurals.feature_wallet_anomalies, count, count),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun LoadingContent() {
    val description = stringResource(R.string.feature_wallet_loading)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = description })
    }
}

@Composable
private fun ErrorContent(reason: WalletError, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(
                when (reason) {
                    WalletError.KID_NOT_FOUND -> R.string.feature_wallet_error_kid_not_found
                    WalletError.LOAD_FAILED -> R.string.feature_wallet_error_load_failed
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Button(onClick = onRetry) { Text(stringResource(R.string.feature_wallet_retry)) }
    }
}

package dev.kpetriv.sprout.feature.wallet

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutTopBar
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.ProvideKidTier
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.ReadAloud

/**
 * Navigation entry for the wallet of [kidId]. Creates a [WalletViewModel] scoped to the current
 * `ViewModelStoreOwner` (the nav entry), passing [kidId] and [mode] through its `SavedStateHandle`.
 *
 * @param mode [WalletMode.PARENT] lets the viewer make manual adjustments and shows a back arrow ([onBack]); in
 *   [WalletMode.KID] the wallet is a tab root (M9 §5.2), so it has no back arrow and the system back does the rest.
 * @param readAloud speaks Seedling text aloud; no text-to-speech implementation exists yet, and with
 *   [ReadAloud.None] the Seedling "Read to me" button is hidden.
 * @param onExport Sapling "Export CSV"; not wired to an exporter yet.
 * @param onOpenShop the Shop button (every tier); the app navigates to [ShopRoute] for the same kid and mode.
 * @param onOpenChores the Chores button (every tier); the app navigates to the kid's chores ("Today").
 * @param onOpenTimeBank kid mode only: the screen-time badge (Sprout, Sapling) and the sun (Seedling) open the kid's
 *   time bank. A parent looking at the wallet cannot start a timer, so the badge is not a button there.
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
    onOpenTimeBank: () -> Unit = {},
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
        onBack = onBack.takeIf { mode == WalletMode.PARENT },
        readAloud = readAloud,
        onExport = onExport,
        onOpenShop = onOpenShop,
        onOpenChores = onOpenChores,
        onOpenTimeBank = onOpenTimeBank.takeIf { mode == WalletMode.KID },
    )
}

/**
 * The wallet for any [WalletUiState]. Ready content renders inside [ProvideKidTier] for the kid's tier and switches
 * to [SeedlingWalletContent], [SproutWalletContent] or [SaplingWalletContent]; colours come only from the Morning
 * garden components and the tier's colour level (M9 §2.6). In kid mode the top bar takes the tier too; a parent's
 * bar, the ledger-issues banner and the [ManualAdjustmentSheet] keep parent sizing.
 *
 * @param onBack the top bar's back arrow; null on a tab root (the kid wallet, M9 §5.2).
 * @param onOpenTimeBank opens the time bank from the screen-time badge or sun; null makes them display only.
 */
@Composable
fun WalletScreen(
    state: WalletUiState,
    onEvent: (WalletEvent) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    readAloud: ReadAloud = ReadAloud.None,
    onExport: () -> Unit = {},
    onOpenShop: () -> Unit = {},
    onOpenChores: () -> Unit = {},
    onOpenTimeBank: (() -> Unit)? = null,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val ready = state as? WalletUiState.Ready
    val kidTier = ready?.tier?.takeUnless { ready.isParentView }
    WithTier(kidTier) {
        Scaffold(
            modifier = modifier,
            topBar = {
                SproutTopBar(
                    title = ready?.let { stringResource(R.string.feature_wallet_title_named, it.kid.firstName) }
                        ?: stringResource(R.string.feature_wallet_title),
                    onBack = onBack,
                    trailing = {
                        if (ready?.canAdjust == true) {
                            // An icon, so the bar's title keeps the kid's name at every size.
                            IconButton(onClick = { onEvent(WalletEvent.OpenAdjust) }) {
                                Icon(
                                    SproutIcons.Edit,
                                    contentDescription = stringResource(R.string.feature_wallet_adjust),
                                )
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
                        actions = WalletActions(
                            onExport = onExport,
                            onOpenShop = onOpenShop,
                            onOpenChores = onOpenChores,
                            onOpenTimeBank = onOpenTimeBank,
                        ),
                    )
                }
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

/** [content] in kid mode for [tier], or as it is (parent sizing) without one. */
@Composable
private fun WithTier(tier: KidUiTier?, content: @Composable () -> Unit) {
    if (tier == null) content() else ProvideKidTier(tier, content)
}

/** Navigation-style callbacks of the ready wallet. */
internal class WalletActions(
    val onExport: () -> Unit = {},
    val onOpenShop: () -> Unit = {},
    val onOpenChores: () -> Unit = {},
    val onOpenTimeBank: (() -> Unit)? = null,
)

@Composable
private fun ReadyContent(
    state: WalletUiState.Ready,
    onEvent: (WalletEvent) -> Unit,
    readAloud: ReadAloud,
    actions: WalletActions,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (state.canAdjust && state.anomaliesCount > 0) AnomaliesBanner(state.anomaliesCount)
        ProvideKidTier(state.tier) {
            when (state.tier) {
                KidUiTier.SEEDLING -> SeedlingWalletContent(state = state, readAloud = readAloud, actions = actions)

                KidUiTier.SPROUT -> SproutWalletContent(state = state, actions = actions)

                KidUiTier.SAPLING -> SaplingWalletContent(
                    state = state,
                    onFilterChange = { onEvent(WalletEvent.FilterChanged(it)) },
                    actions = actions,
                )
            }
        }
    }
}

/**
 * The parent's ledger-issues banner: an error-tint card under the top bar. The words carry the state; the colour only
 * repeats it.
 */
@Composable
private fun AnomaliesBanner(count: Int) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = pluralStringResource(R.plurals.feature_wallet_anomalies, count, count),
        color = scheme.onErrorContainer,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SproutSpacing.gutter, vertical = SproutSpacing.sm)
            .background(scheme.errorContainer, MaterialTheme.shapes.medium)
            .padding(SproutSpacing.md),
    )
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
        modifier = Modifier.fillMaxSize().padding(SproutSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.md, Alignment.CenterVertically),
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
        SproutButton(text = stringResource(R.string.feature_wallet_retry), onClick = onRetry)
    }
}

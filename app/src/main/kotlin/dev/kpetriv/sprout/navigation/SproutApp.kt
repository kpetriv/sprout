package dev.kpetriv.sprout.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.kpetriv.sprout.BuildConfig
import dev.kpetriv.sprout.core.domainledger.ParentAuthorizationRequester
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.feature.chores.ApprovalsInboxRoute
import dev.kpetriv.sprout.feature.chores.ChoreEditorRoute
import dev.kpetriv.sprout.feature.chores.ChoreListRoute
import dev.kpetriv.sprout.feature.chores.KidTodayRoute
import dev.kpetriv.sprout.feature.lock.KidModeExitGate
import dev.kpetriv.sprout.feature.lock.RequireParent
import dev.kpetriv.sprout.feature.parenthome.ParentHomeRoute
import dev.kpetriv.sprout.feature.settings.SettingsRoute
import dev.kpetriv.sprout.feature.wallet.ShopRoute
import dev.kpetriv.sprout.feature.wallet.WalletMode
import dev.kpetriv.sprout.feature.wallet.WalletRoute
import dev.kpetriv.sprout.kids.KidPickPurpose
import dev.kpetriv.sprout.kids.KidPickerDialog
import dev.kpetriv.sprout.kids.KidsEvent
import dev.kpetriv.sprout.kids.KidsUiState
import dev.kpetriv.sprout.kids.KidsViewModel
import dev.kpetriv.sprout.kids.StartupErrorScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The app shell. Shows a full-screen error when the process-start work failed, otherwise the area for the current
 * [AppMode] ([ModeViewModel]):
 * - **Parent**: everything behind `RequireParent` (PIN or fingerprint; `FLAG_SECURE`), one Navigation 3 back stack
 *   from [ParentHomeKey]. "Switch to kid mode" picks a kid, locks the parent session and enters kid mode.
 * - **Kid**: the kid's wallet ([KidHomeKey]) with its shop and today's chores, no lock. Back at the root (system back
 *   or the wallet's back arrow) opens `KidModeExitGate`; a successful unlock returns to the parent area.
 *
 * @param requester the per-event parent prompt (`feature/lock`), for the dashboard's "Sign now": each prompt shows
 *   the reason the drain gives for that payment (kid, amount, period).
 */
@Composable
fun SproutApp(requester: ParentAuthorizationRequester, modifier: Modifier = Modifier) {
    val modeViewModel: ModeViewModel = hiltViewModel()
    val kidsViewModel: KidsViewModel = hiltViewModel()
    val mode by modeViewModel.mode.collectAsStateWithLifecycle()
    val kids by kidsViewModel.state.collectAsStateWithLifecycle()
    val startupFailure = when (val state = kids) {
        is KidsUiState.Ready -> state.startupFailure
        is KidsUiState.Error -> state.startupFailure
        KidsUiState.Loading -> null
    }
    if (startupFailure != null) {
        StartupErrorScreen(startupFailure, onRetry = { kidsViewModel.onEvent(KidsEvent.Retry) }, modifier = modifier)
        return
    }
    when (val current = mode) {
        AppMode.Parent -> ParentArea(
            requester = requester,
            kids = kids,
            onEnterKidMode = modeViewModel::enterKidMode,
            modifier = modifier,
        )

        is AppMode.Kid -> key(current.kidId) {
            KidArea(
                kidId = current.kidId,
                tierOf = modeViewModel::tierOf,
                onExit = modeViewModel::exitKidMode,
                modifier = modifier,
            )
        }
    }
}

/**
 * Entry decorators, in order: saveable state per entry, then a ViewModel store per entry (cleared on pop). Remembered
 * next to the back stack, so anything that takes the [NavDisplay] out of composition (the parent lock) keeps each
 * entry's saved state and ViewModels.
 */
@Composable
private fun entryDecorators() = listOf(
    rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
    rememberViewModelStoreNavEntryDecorator(),
)

@Composable
private fun ParentArea(
    requester: ParentAuthorizationRequester,
    kids: KidsUiState,
    onEnterKidMode: (KidId) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Hoisted above the gate so locking (auto-lock in the background) keeps where the parent was, and the entries'
    // saved state and ViewModels with it.
    val backStack = rememberNavBackStack(ParentHomeKey)
    val decorators = entryDecorators()
    var picking by rememberSaveable { mutableStateOf<KidPickPurpose?>(null) }
    RequireParent(modifier = modifier) {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryDecorators = decorators,
            entryProvider = entryProvider {
                entry<ParentHomeKey> {
                    ParentHomeRoute(
                        requester = requester,
                        onOpenChores = { backStack.push(ChoresKey) },
                        onOpenApprovals = { backStack.push(ApprovalsKey) },
                        onOpenShop = { picking = KidPickPurpose.SHOP },
                        onOpenSettings = { backStack.push(SettingsKey) },
                        onSwitchToKidMode = { picking = KidPickPurpose.KID_MODE },
                        onOpenKid = { kidId -> backStack.push(WalletKey(kidId, WalletMode.PARENT)) },
                    )
                }
                parentEntries(backStack)
            },
        )
        picking?.let { purpose ->
            KidPickerDialog(
                state = kids,
                purpose = purpose,
                onPick = { kidId ->
                    picking = null
                    when (purpose) {
                        KidPickPurpose.KID_MODE -> onEnterKidMode(kidId)
                        KidPickPurpose.SHOP -> backStack.push(ShopKey(kidId, WalletMode.PARENT))
                    }
                },
                onDismiss = { picking = null },
            )
        }
    }
}

/**
 * Opens [key] unless it is already on top: a double tap on a link, or a second tap while a slow lookup was running,
 * must not stack the same screen twice.
 */
internal fun NavBackStack<NavKey>.push(key: NavKey) {
    if (lastOrNull() != key) add(key)
}

/** The parent area's entries other than the dashboard. */
private fun EntryProviderScope<NavKey>.parentEntries(backStack: NavBackStack<NavKey>) {
    val pop: () -> Unit = { backStack.removeLastOrNull() }
    entry<WalletKey> { key ->
        WalletRoute(
            kidId = key.kidId,
            mode = key.mode,
            onBack = pop,
            onOpenShop = { backStack.push(ShopKey(key.kidId, key.mode)) },
            onOpenChores = { backStack.push(ChoresKey) },
        )
    }
    entry<ShopKey> { key -> ShopRoute(kidId = key.kidId, mode = key.mode, onBack = pop) }
    entry<ChoresKey> {
        ChoreListRoute(
            onCreateChore = { backStack.push(ChoreEditorKey()) },
            onEditChore = { choreId -> backStack.push(ChoreEditorKey(choreId)) },
            onOpenApprovals = { backStack.push(ApprovalsKey) },
            onBack = pop,
        )
    }
    entry<ChoreEditorKey> { key -> ChoreEditorRoute(choreId = key.choreId, onDone = pop, onBack = pop) }
    entry<ApprovalsKey> { ApprovalsInboxRoute(onBack = pop) }
    entry<SettingsKey> { SettingsRoute(onBack = pop, appVersion = BuildConfig.VERSION_NAME) }
}

@Composable
private fun KidArea(
    kidId: KidId,
    tierOf: suspend (KidId) -> KidUiTier?,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backStack = rememberNavBackStack(KidHomeKey(kidId))
    val decorators = entryDecorators()
    var exiting by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // The chores tap looks the tier up first; a second tap while that runs is ignored instead of opening it twice.
    var openingChores by remember { mutableStateOf<Job?>(null) }
    val atRoot = backStack.size <= 1
    val back: () -> Unit = { if (backStack.size > 1) backStack.removeLastOrNull() else exiting = true }
    // Back at the root never leaves kid mode by accident: it asks for the parent instead.
    BackHandler(enabled = atRoot && !exiting) { exiting = true }
    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = back,
        entryDecorators = decorators,
        entryProvider = entryProvider {
            entry<KidHomeKey> { key ->
                WalletRoute(
                    kidId = key.kidId,
                    mode = WalletMode.KID,
                    onBack = back,
                    onOpenShop = { backStack.push(ShopKey(key.kidId, WalletMode.KID)) },
                    onOpenChores = {
                        if (openingChores?.isActive != true) {
                            openingChores = scope.launch {
                                tierOf(key.kidId)?.let { backStack.push(KidChoresKey(key.kidId, it)) }
                            }
                        }
                    },
                )
            }
            entry<ShopKey> { key -> ShopRoute(kidId = key.kidId, mode = key.mode, onBack = back) }
            entry<KidChoresKey> { key -> KidTodayRoute(kidId = key.kidId, tier = key.tier, onBack = back) }
        },
    )
    if (exiting) {
        KidModeExitGate(
            onDismiss = { exiting = false },
            onExitConfirmed = {
                exiting = false
                onExit()
            },
        )
    }
}

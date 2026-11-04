package dev.kpetriv.sprout.navigation

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import dev.kpetriv.sprout.chat.rememberReadAloud
import dev.kpetriv.sprout.core.domainledger.ParentAuthorizationRequester
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.LocalWindowWidthClass
import dev.kpetriv.sprout.core.ui.WindowWidthClass
import dev.kpetriv.sprout.feature.chat.ChatThreadListRoute
import dev.kpetriv.sprout.feature.chat.ChatThreadPlaceholder
import dev.kpetriv.sprout.feature.chat.KidChatRoute
import dev.kpetriv.sprout.feature.chat.ParentChatRoute
import dev.kpetriv.sprout.feature.chores.ApprovalsInboxRoute
import dev.kpetriv.sprout.feature.chores.ChoreEditorPlaceholder
import dev.kpetriv.sprout.feature.chores.ChoreEditorRoute
import dev.kpetriv.sprout.feature.chores.ChoreListRoute
import dev.kpetriv.sprout.feature.chores.KidTodayRoute
import dev.kpetriv.sprout.feature.games.GamePlayRoute
import dev.kpetriv.sprout.feature.games.GamesHubRoute
import dev.kpetriv.sprout.feature.lock.KidModeExitGate
import dev.kpetriv.sprout.feature.lock.ParentGateViewModel
import dev.kpetriv.sprout.feature.lock.ParentSessionState
import dev.kpetriv.sprout.feature.lock.RequireParent
import dev.kpetriv.sprout.feature.lock.SecureWindow
import dev.kpetriv.sprout.feature.onboarding.OnboardingRoute
import dev.kpetriv.sprout.feature.onboarding.SigningSetupRoute
import dev.kpetriv.sprout.feature.parenthome.ParentHomeRoute
import dev.kpetriv.sprout.feature.settings.SettingsRoute
import dev.kpetriv.sprout.feature.settings.SettingsSection
import dev.kpetriv.sprout.feature.timebank.TimeBankRoute
import dev.kpetriv.sprout.feature.wallet.ShopRoute
import dev.kpetriv.sprout.feature.wallet.WalletMode
import dev.kpetriv.sprout.feature.wallet.WalletRoute
import dev.kpetriv.sprout.kids.KidPickPurpose
import dev.kpetriv.sprout.kids.KidPickerDialog
import dev.kpetriv.sprout.kids.KidsEvent
import dev.kpetriv.sprout.kids.KidsUiState
import dev.kpetriv.sprout.kids.KidsViewModel
import dev.kpetriv.sprout.kids.StartupErrorScreen
import dev.kpetriv.sprout.settings.PinConfirmationDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The app shell. Shows a full-screen error when the process-start work failed, otherwise the area for the current
 * [AppMode] ([ModeViewModel], derived from the session; [AppModeContent], which makes the onboarding, parent and kid
 * areas `FLAG_SECURE`):
 * - **Onboarding** (no session, a phone waiting for approval, or the trial's "Set up sync"): `feature/onboarding`,
 *   with `feature/lock`'s PIN setup (or, for an existing PIN, fingerprint-signing setup) as its last parent step.
 * - **Parent**: everything behind `RequireParent` (PIN or fingerprint; `FLAG_SECURE`), one Navigation 3 back stack
 *   from [ParentHomeKey]. "Switch to kid mode" picks a kid, locks the parent session and enters kid mode. In a real
 *   household the dashboard's Chat shortcut opens the chat inbox ([ChatThreadsKey]) and each kid's thread
 *   ([ChatThreadKey]); on an Expanded window the inbox and a thread, and the chore list and its editor, sit side by
 *   side (list-detail, M7 §5).
 * - **Kid**: the kid's wallet ([KidHomeKey]) with its shop and today's chores, no lock, and shortcuts to the kid's
 *   chat ([ChatThreadKey], real households only, read aloud by text-to-speech), screen time
 *   ([TimeBankKey]) and the brain games hub ([KidGamesKey], one [GamePlayKey] per play, read aloud by
 *   text-to-speech too), each in the kid's tier. On a parent's phone back at the root (system back or the wallet's
 *   back arrow) opens `KidModeExitGate`; a successful unlock returns to the parent area. On a kid's own linked phone
 *   there is no parent area: back at the root leaves the app.
 * - **SignInAgain**: the "Sign in again" gate for a phone whose sign-in ended; no household data behind it, but it
 *   takes credentials, so it is `FLAG_SECURE` too.
 * - **Removed**: the blocking wipe screen.
 *
 * @param requester the per-event parent prompt (`feature/lock`), for the dashboard's "Sign now": each prompt shows
 *   the reason the drain gives for that payment (kid, amount, period).
 */
@Composable
fun SproutApp(requester: ParentAuthorizationRequester, modifier: Modifier = Modifier) {
    val modeViewModel: ModeViewModel = hiltViewModel()
    val kidsViewModel: KidsViewModel = hiltViewModel()
    val mode by modeViewModel.mode.collectAsStateWithLifecycle()
    val chatAvailable by modeViewModel.chatAvailable.collectAsStateWithLifecycle()
    val kids by kidsViewModel.state.collectAsStateWithLifecycle()
    val startupFailure = when (val state = kids) {
        is KidsUiState.Ready -> state.startupFailure
        is KidsUiState.Error -> state.startupFailure
        KidsUiState.Loading -> null
    }
    // Fully drawn (Macrobenchmark's timeToFullDisplay, D-M7-43) once startup is over and the area has its first real
    // content: the parent's PIN screen or dashboard, kid home, onboarding, or the error.
    ReportDrawnWhen { startupFailure != null || (mode != AppMode.Loading && kids != KidsUiState.Loading) }
    if (startupFailure != null) {
        StartupErrorScreen(startupFailure, onRetry = { kidsViewModel.onEvent(KidsEvent.Retry) }, modifier = modifier)
        return
    }
    AppModeContent(
        mode = mode,
        modifier = modifier,
        onboarding = { current ->
            OnboardingArea(
                canLeave = current.canLeave,
                onStarted = { modeViewModel.onEvent(ModeEvent.BeginOnboarding) },
                onEnded = { modeViewModel.onEvent(ModeEvent.EndOnboarding) },
                modifier = modifier,
            )
        },
        parent = {
            ParentArea(
                requester = requester,
                kids = kids,
                chatAvailable = chatAvailable,
                onEnterKidMode = { modeViewModel.onEvent(ModeEvent.EnterKidMode(it)) },
                onSetUpSync = { modeViewModel.onEvent(ModeEvent.BeginOnboarding) },
                modifier = modifier,
            )
        },
        kid = { current ->
            key(current.kidId, current.linkedDevice) {
                KidArea(
                    kidId = current.kidId,
                    linkedDevice = current.linkedDevice,
                    chatAvailable = chatAvailable,
                    tierOf = modeViewModel::tierOf,
                    onExit = { modeViewModel.onEvent(ModeEvent.ExitKidMode) },
                    modifier = modifier,
                )
            }
        },
        signInAgain = { current -> SignInAgainRoute(current.gate, modifier) },
        removed = { current -> RemovedRoute(current.removal.reason, modifier) },
    )
}

/**
 * The area for [mode]. `FLAG_SECURE` is structural (D-M7-6, D-M7-7, threat model T15): the onboarding, parent, kid and
 * "Sign in again" areas each call `SecureWindow()` at their root, here, before any of their screens, so no screen
 * inside them (nor a dialog or sheet they open, which inherits the window's flag or sets its own) can forget it; the
 * onboarding area shows the link code (a credential), the parent and kid areas names and balances, and "Sign in again"
 * takes the account e-mail and password and, in its erase dialog, the parent PIN (audit 2 N4, D-M7-82). Loading and
 * the removed screen show no household data and take nothing, so they stay capturable. The areas are slots so
 * `SecureAreaTest` can check the flag per mode.
 */
@Composable
internal fun AppModeContent(
    mode: AppMode,
    onboarding: @Composable (AppMode.Onboarding) -> Unit,
    parent: @Composable () -> Unit,
    kid: @Composable (AppMode.Kid) -> Unit,
    signInAgain: @Composable (AppMode.SignInAgain) -> Unit,
    removed: @Composable (AppMode.Removed) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (mode) {
        AppMode.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        is AppMode.Onboarding -> {
            SecureWindow()
            onboarding(mode)
        }

        AppMode.Parent -> {
            SecureWindow()
            parent()
        }

        is AppMode.Kid -> {
            SecureWindow()
            kid(mode)
        }

        is AppMode.SignInAgain -> {
            SecureWindow()
            signInAgain(mode)
        }

        is AppMode.Removed -> removed(mode)
    }
}

@Composable
private fun OnboardingArea(canLeave: Boolean, onStarted: () -> Unit, onEnded: () -> Unit, modifier: Modifier) {
    val activity = LocalActivity.current
    OnboardingRoute(
        onStarted = onStarted,
        parentLock = { onDone -> ParentLockStep(onDone) },
        onFinished = onEnded,
        onExit = { if (canLeave) onEnded() else activity?.finish() },
        canLeave = canLeave,
        modifier = modifier,
    )
}

/**
 * Onboarding's last parent step, behind `RequireParent`: a new PIN (whose setup also turns fingerprint signing on), or
 * for a parent who already has one (from the trial) the PIN entry and then [SigningSetupRoute], since the purge
 * removed the trial's parent key.
 */
@Composable
private fun ParentLockStep(onDone: () -> Unit) {
    val gate: ParentGateViewModel = hiltViewModel()
    val gateState by gate.state.collectAsStateWithLifecycle()
    var newPin by rememberSaveable { mutableStateOf<Boolean?>(null) }
    if (newPin == null && gateState.session != ParentSessionState.Initializing) {
        newPin = gateState.session == ParentSessionState.SetupRequired
    }
    RequireParent {
        when (newPin) {
            true -> LaunchedEffect(Unit) { onDone() }
            false -> SigningSetupRoute(onDone = onDone)
            null -> Unit
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

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun ParentArea(
    requester: ParentAuthorizationRequester,
    kids: KidsUiState,
    chatAvailable: Boolean,
    onEnterKidMode: (KidId) -> Unit,
    onSetUpSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Hoisted above the gate so locking (auto-lock in the background) keeps where the parent was, and the entries'
    // saved state and ViewModels with it.
    val backStack = rememberNavBackStack(ParentHomeKey)
    val decorators = entryDecorators()
    var picking by rememberSaveable { mutableStateOf<KidPickPurpose?>(null) }
    // On an Expanded window the chat inbox and a thread, and the chore list and its editor, share the screen
    // (M7 §5, D-M7-2); a Compact or Medium window shows one pane as before.
    val listDetail = rememberListDetailSceneStrategy<NavKey>()
    RequireParent(modifier = modifier) {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryDecorators = decorators,
            sceneStrategies = listOf(listDetail),
            entryProvider = entryProvider {
                entry<ParentHomeKey> {
                    WithShortcuts(parentShortcuts(chatAvailable) { backStack.push(ChatThreadsKey) }) {
                        ParentHomeRoute(
                            requester = requester,
                            onOpenChores = { backStack.push(ChoresKey) },
                            onOpenApprovals = { backStack.push(ApprovalsKey) },
                            onOpenShop = { picking = KidPickPurpose.SHOP },
                            onOpenSettings = { backStack.push(SettingsKey) },
                            onSwitchToKidMode = { picking = KidPickPurpose.KID_MODE },
                            onOpenKid = { kidId -> backStack.push(WalletKey(kidId, WalletMode.PARENT)) },
                            onSetUpSync = onSetUpSync,
                            onReviewDevice = { deviceId -> backStack.push(DevicesKey(deviceId)) },
                            onRegisterSigningKey = { backStack.push(SecurityKey) },
                            onOpenAiSettings = { backStack.push(AiSettingsKey) },
                        )
                    }
                }
                parentEntries(backStack)
            },
        )
        // The parent PIN when a sensitive setting asks for the parent again and no fingerprint can answer (M7).
        PinConfirmationDialog()
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

/** Closes [key] if it is on top; a no-op otherwise (a second tap on a "Done" that already closed it). */
internal fun NavBackStack<NavKey>.popIf(key: NavKey) {
    if (lastOrNull() == key) removeLastOrNull()
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
    entry<ApprovalsKey> { ApprovalsInboxRoute(onBack = pop) }
    entry<SettingsKey> { SettingsRoute(onBack = pop) }
    entry<DevicesKey> { key ->
        SettingsRoute(
            onBack = pop,
            initialSection = SettingsSection.DEVICES,
            reviewDeviceId = key.reviewDeviceId,
        )
    }
    entry<SecurityKey> {
        SettingsRoute(
            onBack = pop,
            initialSection = SettingsSection.PARENT_SECURITY,
        )
    }
    entry<AiSettingsKey> {
        SettingsRoute(
            onBack = pop,
            initialSection = SettingsSection.AI,
        )
    }
    listDetailEntries(backStack, pop)
}

/**
 * The parent's list-detail flows (M7 §5, D-M7-2): the chore list and its editor, the chat inbox and a thread. On an
 * Expanded window `ListDetailSceneStrategy` shows each list with its open item (or a placeholder) beside it.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private fun EntryProviderScope<NavKey>.listDetailEntries(backStack: NavBackStack<NavKey>, pop: () -> Unit) {
    val choresList = ListDetailSceneStrategy.listPane(detailPlaceholder = { ChoreEditorPlaceholder() })
    val chatList = ListDetailSceneStrategy.listPane(detailPlaceholder = { ChatThreadPlaceholder() })
    entry<ChoresKey>(metadata = choresList) {
        ListPane {
            ChoreListRoute(
                onCreateChore = { backStack.openDetail(ChoreEditorKey()) },
                onEditChore = { choreId -> backStack.openDetail(ChoreEditorKey(choreId)) },
                onOpenApprovals = { backStack.push(ApprovalsKey) },
                onBack = pop,
                selectedChoreId = (backStack.lastOrNull() as? ChoreEditorKey)?.choreId,
            )
        }
    }
    entry<ChoreEditorKey>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
        ChoreEditorRoute(choreId = key.choreId, onDone = pop, onBack = pop)
    }
    entry<ChatThreadsKey>(metadata = chatList) {
        ListPane {
            ChatThreadListRoute(
                onOpenThread = { kidId -> backStack.openDetail(ChatThreadKey(kidId)) },
                onBack = pop,
                selectedThreadId = (backStack.lastOrNull() as? ChatThreadKey)?.kidId,
            )
        }
    }
    entry<ChatThreadKey>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
        ParentChatRoute(threadId = key.kidId, onBack = pop)
    }
}

/**
 * Opens [detail] beside its list: it replaces the detail already open (another thread, another chore), so back from
 * a list-detail screen returns to the list rather than walking through every item opened, on a phone too.
 */
private fun NavBackStack<NavKey>.openDetail(detail: NavKey) {
    val top = lastOrNull()
    when {
        top == detail -> Unit
        top != null && top::class == detail::class -> set(lastIndex, detail)
        else -> add(detail)
    }
}

/**
 * A list pane: laid out as on a phone ([WindowWidthClass.Compact]) because on an Expanded window it is the 360 dp
 * column of the list-detail scene, not the whole window (D-M7-35).
 */
@Composable
private fun ListPane(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalWindowWidthClass provides WindowWidthClass.Compact, content = content)
}

@Composable
private fun KidArea(
    kidId: KidId,
    linkedDevice: Boolean,
    chatAvailable: Boolean,
    tierOf: suspend (KidId) -> KidUiTier?,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = LocalActivity.current
    val backStack = rememberNavBackStack(KidHomeKey(kidId))
    val decorators = entryDecorators()
    var exiting by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // The chores and games taps look the tier up first; a second tap while that runs is ignored instead of opening a
    // screen twice.
    var opening by remember { mutableStateOf<Job?>(null) }
    val openWithTier: (KidId, (KidUiTier) -> NavKey) -> Unit = { id, keyFor ->
        if (opening?.isActive != true) {
            opening = scope.launch { tierOf(id)?.let { backStack.push(keyFor(it)) } }
        }
    }
    val atRoot = backStack.size <= 1
    val back: () -> Unit = {
        when {
            backStack.size > 1 -> backStack.removeLastOrNull()
            linkedDevice -> activity?.finish()
            else -> exiting = true
        }
    }
    // On a parent's phone back at the root never leaves kid mode by accident: it asks for the parent instead. A kid's
    // own phone has no parent area, so back at the root leaves the app as usual.
    BackHandler(enabled = atRoot && !exiting && !linkedDevice) { exiting = true }
    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = back,
        entryDecorators = decorators,
        entryProvider = entryProvider {
            entry<KidHomeKey> { key ->
                val shortcuts = kidShortcuts(
                    chat = chatAvailable,
                    onChat = { backStack.push(ChatThreadKey(key.kidId)) },
                    onScreenTime = { backStack.push(TimeBankKey(key.kidId)) },
                    onGames = { openWithTier(key.kidId) { tier -> KidGamesKey(key.kidId, tier) } },
                )
                WithShortcuts(shortcuts) {
                    WalletRoute(
                        kidId = key.kidId,
                        mode = WalletMode.KID,
                        onBack = back,
                        onOpenShop = { backStack.push(ShopKey(key.kidId, WalletMode.KID)) },
                        onOpenChores = { openWithTier(key.kidId) { tier -> KidChoresKey(key.kidId, tier) } },
                    )
                }
            }
            entry<ShopKey> { key -> ShopRoute(kidId = key.kidId, mode = key.mode, onBack = back) }
            entry<KidChoresKey> { key -> KidTodayRoute(kidId = key.kidId, tier = key.tier, onBack = back) }
            entry<ChatThreadKey> { key ->
                WithTier(key.kidId, tierOf) { tier ->
                    KidChatRoute(kidId = key.kidId, tier = tier, onBack = back, readAloud = rememberReadAloud())
                }
            }
            entry<TimeBankKey> { key ->
                WithTier(key.kidId, tierOf) { tier -> TimeBankRoute(kidId = key.kidId, tier = tier, onBack = back) }
            }
            entry<KidGamesKey> { key ->
                GamesHubRoute(
                    kidId = key.kidId,
                    tier = key.tier,
                    onPlay = { kind -> backStack.push(GamePlayKey(key.kidId, key.tier, kind)) },
                    onBack = back,
                    readAloud = rememberReadAloud(),
                )
            }
            entry<GamePlayKey> { key ->
                GamePlayRoute(
                    kidId = key.kidId,
                    tier = key.tier,
                    kind = key.kind,
                    // Back to the hub; a second tap on Done must not leave the hub too.
                    onFinished = { backStack.popIf(key) },
                    onBack = back,
                    readAloud = rememberReadAloud(),
                )
            }
        },
    )
    if (exiting && !linkedDevice) {
        KidModeExitGate(
            onDismiss = { exiting = false },
            onExitConfirmed = {
                exiting = false
                onExit()
            },
        )
    }
}

/**
 * [content] in [kidId]'s UI tier, looked up first ([tierOf]); a progress indicator meanwhile, and nothing for a kid
 * that does not exist (any more).
 */
@Composable
private fun WithTier(kidId: KidId, tierOf: suspend (KidId) -> KidUiTier?, content: @Composable (KidUiTier) -> Unit) {
    val tier by produceState<KidUiTier?>(initialValue = null, kidId) { value = tierOf(kidId) }
    val current = tier
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        content(current)
    }
}

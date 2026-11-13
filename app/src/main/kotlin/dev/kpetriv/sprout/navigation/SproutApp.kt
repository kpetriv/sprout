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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SinglePaneSceneStrategy
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
import dev.kpetriv.sprout.feature.wallet.RewardEditorPlaceholder
import dev.kpetriv.sprout.feature.wallet.RewardEditorRoute
import dev.kpetriv.sprout.feature.wallet.RewardListRoute
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
import kotlinx.coroutines.flow.Flow

/**
 * The app shell. Shows a full-screen error when the process-start work failed, otherwise the area for the current
 * [AppMode] ([ModeViewModel], derived from the session; [AppModeContent], which makes the onboarding, parent and kid
 * areas `FLAG_SECURE`):
 * - **Onboarding** (no session, a phone waiting for approval, or the trial's "Set up sync"): `feature/onboarding`,
 *   with `feature/lock`'s PIN setup (or, for an existing PIN, fingerprint-signing setup) as its last parent step.
 * - **Parent**: everything behind `RequireParent` (PIN or fingerprint; `FLAG_SECURE`), in the parent shell (M9 §5):
 *   a bottom bar (a rail on Expanded windows) with Home ([ParentHomeKey]), Chores ([ChoresKey]), Chat
 *   ([ChatThreadsKey], real households only) and Settings ([SettingsKey]), one back stack per tab
 *   ([TopLevelBackStack]). "Switch to kid mode" picks a kid, locks the parent session and enters kid mode. The
 *   dashboard's "Shop" opens the household's rewards ([RewardsKey], add and edit; "See the shop as a kid" picks a kid
 *   for the preview). On an Expanded window the chat inbox and a thread, the chore list and its editor, and the
 *   rewards list and its editor sit side by side (list-detail, M7 §5).
 * - **Kid**: the kid shell in the kid's tier: Wallet ([KidHomeKey]), Chores ([KidChoresKey]), Shop ([ShopKey]), Games
 *   ([KidGamesKey], one [GamePlayKey] per play, read aloud by text-to-speech) and Chat ([ChatThreadKey], real
 *   households only, read aloud too) on Sprout and Sapling; Wallet, Chores and Shop on Seedling, whose wallet keeps
 *   giant Chat, Screen time and Games buttons. Screen time ([TimeBankKey]) is pushed from the wallet's screen-time
 *   badge (Seedling: its sun and giant button). No lock. On a parent's phone back at the root (the system back; the
 *   kid wallet is a tab root without a back arrow) opens `KidModeExitGate`; a successful
 *   unlock returns to the parent area. On a kid's own linked phone there is no parent area: back at the root leaves
 *   the app.
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
                    observeTier = modeViewModel::observeTier,
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
 * Each tab's entry decorators, in order: saveable state per entry, then a ViewModel store per entry (cleared on pop).
 * One set per tab, as Navigation 3's multiple back stacks need, remembered under the tab's key next to the back
 * stacks, so anything that takes the [NavDisplay] out of composition (the parent lock) or a tab out of view (another
 * tab selected) keeps each entry's saved state and ViewModels.
 */
@Composable
internal fun rememberTabDecorators(tabs: List<NavKey>): Map<NavKey, List<NavEntryDecorator<NavKey>>> =
    tabs.associateWith { tab ->
        key(tab) {
            listOf(
                rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
                rememberViewModelStoreNavEntryDecorator(),
            )
        }
    }

/**
 * The shell's [NavDisplay] (M9 §5.2, D-M9-11): every tab's stack is decorated on its own, and the display gets the
 * first tab's entries followed by the selected tab's ([TopLevelBackStack.visibleTabs]). Back is enabled while that
 * list has somewhere to go; at the first tab's root it is not, so the system back (or the caller's own handler)
 * leaves the mode.
 *
 * Every entry's content key is scoped to its tab ([tabScoped]): the same screen can sit in two tabs at once (approvals
 * from the dashboard and from the chore list), and `NavDisplay` tells scenes, saved state and movable content apart
 * by content key alone.
 */
@Composable
internal fun ShellNavDisplay(
    navigation: TopLevelBackStack,
    decorators: Map<NavKey, List<NavEntryDecorator<NavKey>>>,
    entryProvider: (NavKey) -> NavEntry<NavKey>,
    modifier: Modifier = Modifier,
    sceneStrategies: List<SceneStrategy<NavKey>> = listOf(SinglePaneSceneStrategy()),
    onBack: () -> Unit = { navigation.back() },
) {
    val entries = navigation.tabs.associateWith { tab ->
        key(tab) {
            rememberDecoratedNavEntries(navigation.stackOf(tab), decorators.getValue(tab)) { navKey ->
                tabScoped(tab, entryProvider(navKey), navKey)
            }
        }
    }
    NavDisplay(
        entries = navigation.visibleTabs.flatMap { entries.getValue(it) },
        modifier = modifier,
        sceneStrategies = sceneStrategies,
        onBack = onBack,
    )
}

/** [entry] for [navKey] with its content key prefixed by [tab]'s, so each tab's copy of a screen is its own entry. */
internal fun tabScoped(tab: NavKey, entry: NavEntry<NavKey>, navKey: NavKey): NavEntry<NavKey> =
    NavEntry(navKey, contentKey = "$tab/${entry.contentKey}", metadata = entry.metadata) { entry.Content() }

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
    // Hoisted above the gate so locking (auto-lock in the background) keeps where the parent was in every tab, and
    // the entries' saved state and ViewModels with it.
    val tabs = parentTabs(chatAvailable)
    val roots = tabs.map { it.root }
    val navigation = rememberTopLevelBackStack(roots)
    val decorators = rememberTabDecorators(navigation.tabs)
    var picking by rememberSaveable { mutableStateOf<KidPickPurpose?>(null) }
    // On an Expanded window the chat inbox and a thread, the chore list and its editor, and the rewards list and its
    // editor share the screen (M7 §5, D-M7-2); a Compact or Medium window shows one pane as before.
    val listDetail = rememberListDetailSceneStrategy<NavKey>()
    // The shell sits inside the gate: no bar or rail on the PIN entry or setup (D-M9-10).
    RequireParent(modifier = modifier) {
        SproutShell(
            tabs = tabs,
            selectedIndex = navigation.selectedIndexIn(roots),
            onSelect = { navigation.select(roots[it]) },
        ) {
            ShellNavDisplay(
                navigation = navigation,
                decorators = decorators,
                sceneStrategies = listOf(listDetail),
                entryProvider = entryProvider {
                    entry<ParentHomeKey> {
                        ParentHomeRoute(
                            requester = requester,
                            onOpenApprovals = { navigation.push(ApprovalsKey) },
                            // The household's rewards first (add and edit); the kid's preview is one tap from there.
                            onOpenShop = { navigation.openRewards() },
                            onSwitchToKidMode = { picking = KidPickPurpose.KID_MODE },
                            onOpenKid = { kidId -> navigation.push(WalletKey(kidId, WalletMode.PARENT)) },
                            onSetUpSync = onSetUpSync,
                            onReviewDevice = { deviceId -> navigation.push(DevicesKey(deviceId)) },
                            onRegisterSigningKey = { navigation.push(SecurityKey) },
                            onOpenAiSettings = { navigation.push(AiSettingsKey) },
                        )
                    }
                    parentEntries(navigation, onPickShopKid = { picking = KidPickPurpose.SHOP })
                },
            )
        }
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

                        // Only reached from the rewards list: the preview opens above it, so "Edit rewards" there
                        // returns to the list (and the reward open beside it).
                        KidPickPurpose.SHOP -> navigation.push(ShopKey(kidId, WalletMode.PARENT))
                    }
                },
                onDismiss = { picking = null },
            )
        }
    }
}

/**
 * The parent area's entries other than the dashboard. Pushed screens go onto the selected tab ([TopLevelBackStack.push]
 * selects a tab instead when the key is one, such as the parent wallet's link to the chore list). A back arrow is the
 * system back; "Done" closes only the screen it is on. [onPickShopKid] asks which kid's shop to preview (the rewards
 * list's "See the shop as a kid").
 */
private fun EntryProviderScope<NavKey>.parentEntries(navigation: TopLevelBackStack, onPickShopKid: () -> Unit) {
    val back: () -> Unit = { navigation.back() }
    entry<WalletKey> { key ->
        WalletRoute(
            kidId = key.kidId,
            mode = key.mode,
            onBack = back,
            onOpenShop = { navigation.push(ShopKey(key.kidId, key.mode)) },
            onOpenChores = { navigation.push(ChoresKey) },
        )
    }
    entry<ShopKey> { key ->
        ShopRoute(
            kidId = key.kidId,
            mode = key.mode,
            onBack = back,
            onEditRewards = { navigation.openRewards() },
        )
    }
    entry<ApprovalsKey> { ApprovalsInboxRoute(onBack = back) }
    // The Settings tab root: no back arrow (M9 §5.2); system back still leaves the tab.
    entry<SettingsKey> { SettingsRoute(onBack = null) }
    entry<DevicesKey> { key ->
        SettingsRoute(
            onBack = back,
            initialSection = SettingsSection.DEVICES,
            reviewDeviceId = key.reviewDeviceId,
        )
    }
    entry<SecurityKey> {
        SettingsRoute(
            onBack = back,
            initialSection = SettingsSection.PARENT_SECURITY,
        )
    }
    entry<AiSettingsKey> {
        SettingsRoute(
            onBack = back,
            initialSection = SettingsSection.AI,
        )
    }
    listDetailEntries(navigation, back)
    rewardEntries(navigation, back, onPickShopKid)
}

/**
 * Opens the rewards list: back to it when it is already open below (keeping the reward open beside it), otherwise
 * pushed onto the selected tab.
 */
internal fun TopLevelBackStack.openRewards() = pushOrReturnTo(RewardsKey) { it is RewardEditorKey }

/**
 * The household's rewards (parent area only): the list, pushed onto the selected tab from the dashboard's "Shop" or a
 * shop preview's "Edit rewards", and its editor. On an Expanded window `ListDetailSceneStrategy` shows the list with
 * the open reward (or a placeholder) beside it, as for chores (M7 §5, D-M7-2).
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private fun EntryProviderScope<NavKey>.rewardEntries(
    navigation: TopLevelBackStack,
    back: () -> Unit,
    onPickShopKid: () -> Unit,
) {
    val rewardsList = ListDetailSceneStrategy.listPane(detailPlaceholder = { RewardEditorPlaceholder() })
    entry<RewardsKey>(metadata = rewardsList) {
        ListPane {
            RewardListRoute(
                onAddReward = { navigation.openDetail(RewardEditorKey()) },
                onEditReward = { rewardId -> navigation.openDetail(RewardEditorKey(rewardId)) },
                onSeeShop = onPickShopKid,
                // Leaves Rewards, with the reward open beside it on an Expanded window (a phone: the same pop).
                onBack = { navigation.leave(RewardsKey) },
                selectedRewardId = (navigation.current.lastOrNull() as? RewardEditorKey)?.rewardId,
            )
        }
    }
    entry<RewardEditorKey>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
        RewardEditorRoute(rewardId = key.rewardId, onDone = { navigation.popIf(key) }, onBack = back)
    }
}

/**
 * The parent's list-detail flows (M7 §5, D-M7-2): the chore list and its editor, the chat inbox and a thread, each the
 * root of its own tab. On an Expanded window `ListDetailSceneStrategy` shows each list with its open item (or a
 * placeholder) beside it. The rewards list and its editor are the same kind of flow, pushed rather than a tab root
 * ([rewardEntries]).
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private fun EntryProviderScope<NavKey>.listDetailEntries(navigation: TopLevelBackStack, back: () -> Unit) {
    val choresList = ListDetailSceneStrategy.listPane(detailPlaceholder = { ChoreEditorPlaceholder() })
    val chatList = ListDetailSceneStrategy.listPane(detailPlaceholder = { ChatThreadPlaceholder() })
    entry<ChoresKey>(metadata = choresList) {
        ListPane {
            ChoreListRoute(
                onCreateChore = { navigation.openDetail(ChoreEditorKey()) },
                onEditChore = { choreId -> navigation.openDetail(ChoreEditorKey(choreId)) },
                onOpenApprovals = { navigation.push(ApprovalsKey) },
                selectedChoreId = (navigation.stackOf(ChoresKey).lastOrNull() as? ChoreEditorKey)?.choreId,
            )
        }
    }
    entry<ChoreEditorKey>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
        ChoreEditorRoute(choreId = key.choreId, onDone = { navigation.popIf(key) }, onBack = back)
    }
    entry<ChatThreadsKey>(metadata = chatList) {
        ListPane {
            ChatThreadListRoute(
                onOpenThread = { kidId -> navigation.openDetail(ChatThreadKey(kidId)) },
                onBack = null,
                selectedThreadId = navigation.tabs
                    .takeIf { ChatThreadsKey in it }
                    ?.let { (navigation.stackOf(ChatThreadsKey).lastOrNull() as? ChatThreadKey)?.kidId },
            )
        }
    }
    entry<ChatThreadKey>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
        ParentChatRoute(threadId = key.kidId, onBack = back)
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

/**
 * Kid mode: the kid's tier is observed ([observeTier]; a parent can change it from another phone), and the kid shell
 * is built for it ([KidAreaContent]). On a parent's phone back at the root (system back or the wallet's back arrow)
 * opens `KidModeExitGate` and a successful unlock returns to the parent area; on a kid's own linked phone back at the
 * root leaves the app.
 */
@Composable
private fun KidArea(
    kidId: KidId,
    linkedDevice: Boolean,
    chatAvailable: Boolean,
    observeTier: (KidId) -> Flow<KidUiTier?>,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var exiting by rememberSaveable { mutableStateOf(false) }
    val tierFlow = remember(kidId) { observeTier(kidId) }
    val tier by tierFlow.collectAsStateWithLifecycle(initialValue = null)
    KidAreaContent(
        kidId = kidId,
        tier = tier,
        linkedDevice = linkedDevice,
        chatAvailable = chatAvailable,
        exiting = exiting,
        onOpenExitGate = { exiting = true },
        entries = KidRouteEntries,
        modifier = modifier,
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
 * The kid area without its exit gate, so its back routing is tested without Hilt: a progress indicator until [tier]
 * is known, then [KidShell] in that tier, rebuilt when the tier changes. Leaving (back at the first tab's root, from
 * the system back or a top-level back arrow, or back while the tier is still unknown) asks for the parent on a
 * parent's phone ([onOpenExitGate], not while [exiting] already) and finishes the activity on a kid's linked phone.
 */
@Composable
internal fun KidAreaContent(
    kidId: KidId,
    tier: KidUiTier?,
    linkedDevice: Boolean,
    chatAvailable: Boolean,
    exiting: Boolean,
    onOpenExitGate: () -> Unit,
    entries: KidEntries,
    modifier: Modifier = Modifier,
) {
    val activity = LocalActivity.current
    val leave: () -> Unit = { if (linkedDevice) activity?.finish() else onOpenExitGate() }
    // On a parent's phone back at the root never leaves kid mode by accident: it asks for the parent instead. A kid's
    // own phone has no parent area, so back at the root leaves the app as usual (the system's own handling).
    val interceptRootBack = !exiting && !linkedDevice
    if (tier == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        BackHandler(enabled = interceptRootBack, onBack = leave)
    } else {
        key(tier) {
            KidShell(
                kidId = kidId,
                tier = tier,
                chatAvailable = chatAvailable,
                onLeave = leave,
                interceptRootBack = interceptRootBack,
                entries = entries,
                modifier = modifier,
            )
        }
    }
}

/**
 * The kid shell's screens: what each key shows, given the shell's [navigation], the kid's tier, whether chat is
 * available (read when shown) and the shell's back. [KidRouteEntries] in the app; placeholders in tests.
 */
internal fun interface KidEntries {
    fun provider(
        navigation: TopLevelBackStack,
        tier: KidUiTier,
        chatAvailable: () -> Boolean,
        back: () -> Unit,
    ): (NavKey) -> NavEntry<NavKey>
}

/**
 * The kid shell in [tier] (M9 §5.1): Wallet, Chores, Shop, Games and Chat tabs (Wallet, Chores and Shop on Seedling,
 * whose wallet keeps the giant Chat, Screen time and Games buttons). The bar is hidden while a game is in play
 * ([GamePlayKey] on top of the selected tab) and while the time bank's timer runs (the screen asks, D-M9-10). Back at
 * the first tab's root calls [onLeave]; the system back there only when [interceptRootBack].
 */
@Composable
private fun KidShell(
    kidId: KidId,
    tier: KidUiTier,
    chatAvailable: Boolean,
    onLeave: () -> Unit,
    interceptRootBack: Boolean,
    entries: KidEntries,
    modifier: Modifier = Modifier,
) {
    val tabs = kidTabs(kidId, tier, chatAvailable)
    val roots = tabs.map { it.root }
    val navigation = rememberTopLevelBackStack(roots)
    val decorators = rememberTabDecorators(navigation.tabs)
    val chat by rememberUpdatedState(chatAvailable)
    val leave by rememberUpdatedState(onLeave)
    val back: () -> Unit = { if (!navigation.back()) leave() }
    BackHandler(enabled = navigation.atStart && interceptRootBack) { leave() }
    SproutShell(
        tabs = tabs,
        selectedIndex = navigation.selectedIndexIn(roots),
        onSelect = { navigation.select(roots[it]) },
        modifier = modifier,
        tier = tier,
        navigationHidden = navigation.top is GamePlayKey,
    ) {
        ShellNavDisplay(
            navigation = navigation,
            decorators = decorators,
            onBack = back,
            entryProvider = entries.provider(navigation, tier, { chat }, back),
        )
    }
}

/** The kid shell's real screens. */
private val KidRouteEntries = KidEntries { navigation, tier, chatAvailable, back ->
    entryProvider {
        entry<KidHomeKey> { key ->
            val shortcuts = kidShortcuts(
                tier = tier,
                chat = chatAvailable(),
                onChat = { navigation.push(ChatThreadKey(key.kidId)) },
                onScreenTime = { navigation.push(TimeBankKey(key.kidId)) },
                onGames = { navigation.push(KidGamesKey(key.kidId, tier)) },
            )
            WithShortcuts(shortcuts) {
                WalletRoute(
                    kidId = key.kidId,
                    mode = WalletMode.KID,
                    onBack = back,
                    onOpenShop = { navigation.push(ShopKey(key.kidId, WalletMode.KID)) },
                    onOpenChores = { navigation.push(KidChoresKey(key.kidId, tier)) },
                    onOpenTimeBank = { navigation.push(TimeBankKey(key.kidId)) },
                )
            }
        }
        entry<ShopKey> { key -> ShopRoute(kidId = key.kidId, mode = key.mode, onBack = back) }
        entry<KidChoresKey> { key -> KidTodayRoute(kidId = key.kidId, tier = key.tier) }
        entry<ChatThreadKey> { key ->
            // A tab root on Sprout and Sapling (no back arrow); pushed from the wallet on Seedling (M9 §5.2).
            KidChatRoute(
                kidId = key.kidId,
                tier = tier,
                onBack = back.takeIf { tier == KidUiTier.SEEDLING },
                readAloud = rememberReadAloud(),
            )
        }
        entry<TimeBankKey> { key -> TimeBankRoute(kidId = key.kidId, tier = tier, onBack = back) }
        entry<KidGamesKey> { key ->
            GamesHubRoute(
                kidId = key.kidId,
                tier = key.tier,
                onPlay = { kind -> navigation.push(GamePlayKey(key.kidId, key.tier, kind)) },
                // A tab root on Sprout and Sapling (no back arrow); pushed from the wallet on Seedling (M9 §5.2).
                onBack = back.takeIf { tier == KidUiTier.SEEDLING },
                readAloud = rememberReadAloud(),
            )
        }
        entry<GamePlayKey> { key ->
            GamePlayRoute(
                kidId = key.kidId,
                tier = key.tier,
                kind = key.kind,
                // Back to the hub; a second tap on Done must not leave the hub too.
                onFinished = { navigation.popIf(key) },
                onBack = back,
                readAloud = rememberReadAloud(),
            )
        }
    }
}

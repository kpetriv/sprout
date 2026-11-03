package dev.kpetriv.sprout.navigation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.startup.StartupState
import dev.kpetriv.sprout.startup.StartupStatus
import dev.kpetriv.sprout.sync.GateState
import dev.kpetriv.sprout.sync.Removal
import dev.kpetriv.sprout.sync.ShellPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Who the app is showing itself to. */
sealed interface AppMode {
    /** The session is being read (or startup is still running). */
    data object Loading : AppMode

    /**
     * Onboarding (M3 §6): no session, a second parent phone waiting for approval, a parent whose PIN and signing step
     * has not finished, or the trial household's "Set up sync". [canLeave] when there is a trial household to go back
     * to.
     */
    data class Onboarding(val canLeave: Boolean) : AppMode

    /** The parent area, behind the parent lock. */
    data object Parent : AppMode

    /**
     * Kid mode for [kidId]. On a parent's phone ([linkedDevice] false) there is no lock until the parent opens the
     * kid-mode exit gate. On a kid's own linked phone ([linkedDevice] true) there is no parent area at all and back
     * at the root leaves the app.
     */
    data class Kid(val kidId: KidId, val linkedDevice: Boolean = false) : AppMode

    /** The "Sign in again" gate (audit H2): no household data behind it. */
    data class SignInAgain(val gate: GateState) : AppMode

    /** This phone's copy of the household is being, or has been, erased (M3 §6, D-M3-47). */
    data class Removed(val removal: Removal) : AppMode
}

/** Locks the parent area; the app binds it to `feature/lock`'s `ParentSession.lock`. */
fun interface ParentLock {
    fun lock()
}

/**
 * The shell's own state next to the session: the removal ([RemovalHandler.state]), the "Sign in again" gate
 * ([CredentialGate.state]) and the household whose parent setup is pending ([ParentSetupTracker.pending], a digest).
 */
interface ShellSignals {
    val removal: StateFlow<Removal?>
    val gate: StateFlow<GateState?>
    val parentSetupPending: StateFlow<String?>
}

/** The signed-in session as the shell sees it: still loading, or the stored session (null when signed out). */
internal sealed interface SessionView {
    data object Loading : SessionView

    data class Loaded(val session: Session?) : SessionView
}

/** One snapshot of [ShellSignals]. */
internal data class ShellState(
    val removal: Removal? = null,
    val gate: GateState? = null,
    val parentSetupPending: String? = null,
)

/**
 * The [AppMode] for a session (M3 §6):
 * - nothing is decided while the session loads (startup still running included);
 * - a removal wins over everything;
 * - the "Sign in again" gate comes next, for a real session;
 * - no session, or a PARENT session whose device still waits for approval or whose PIN and signing step has not
 *   finished, is onboarding; so is the trial session while the parent is setting up sync ([onboarding]);
 * - a KID session is kid mode for its kid only, with no parent area;
 * - a PARENT session (the trial one included) is the parent area, or kid mode for [kidModeKid] on this phone.
 */
internal fun appModeOf(session: SessionView, onboarding: Boolean, kidModeKid: KidId?, shell: ShellState): AppMode {
    if (session !is SessionView.Loaded) return AppMode.Loading
    val current = session.session
    val real = current?.takeUnless { it.isLocalTrial }
    return when {
        shell.removal != null -> AppMode.Removed(shell.removal)

        real != null && shell.gate != null -> AppMode.SignInAgain(shell.gate)

        current == null -> AppMode.Onboarding(canLeave = false)

        onboarding -> AppMode.Onboarding(canLeave = current.isLocalTrial)

        current.role == Role.KID -> AppMode.Kid(checkNotNull(current.kidId), linkedDevice = true)

        current.deviceStatus == RegistrationStatus.PENDING -> AppMode.Onboarding(canLeave = false)

        real != null && shell.parentSetupPending == ShellPreferences.digestOf(real.householdId) ->
            AppMode.Onboarding(canLeave = false)

        kidModeKid != null -> AppMode.Kid(kidModeKid)

        else -> AppMode.Parent
    }
}

/** What the shell asks of [ModeViewModel]. */
sealed interface ModeEvent {
    /** Switch to kid mode for [kidId] and lock the parent area. */
    data class EnterKidMode(val kidId: KidId) : ModeEvent

    /** Back to the parent area; only after the kid-mode exit gate unlocked the parent session. */
    data object ExitKidMode : ModeEvent

    /** Onboarding is on screen (no session, or the trial household's "Set up sync"). */
    data object BeginOnboarding : ModeEvent

    /** Onboarding finished or was left: the mode follows the session again. */
    data object EndOnboarding : ModeEvent
}

/**
 * The app's [AppMode], derived from the stored session ([SessionStore.observe]) and the [ShellSignals]. Kid mode on a
 * parent's phone is kept in the [SavedStateHandle] so it survives process death (parent mode then starts locked
 * anyway, the parent session being in memory only). Entering kid mode locks the parent session, so handing the
 * device to a kid never leaves the parent area one tap away. Onboarding stays on screen until it says it is finished,
 * even once it saved the session (the PIN and signing step comes after that). Until startup has finished the mode is
 * Loading, so neither a session about to appear nor a removal found during startup flashes anything else.
 */
@HiltViewModel
class ModeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val kids: KidRepository,
    private val parentLock: ParentLock,
    sessions: SessionStore,
    signals: ShellSignals,
    startup: StartupStatus,
) : ViewModel() {
    private val onboarding = MutableStateFlow(false)

    val mode: StateFlow<AppMode> = combine(
        combine(sessionViews(sessions), startup.state) { session, state ->
            if (state == StartupState.Running) SessionView.Loading else session
        },
        onboarding,
        savedStateHandle.getStateFlow<String?>(KID_ID_KEY, null),
        combine(signals.removal, signals.gate, signals.parentSetupPending, ::ShellState),
    ) { session, onboardingNow, kidId, shell ->
        appModeOf(session, onboardingNow, kidId?.let(::KidId), shell)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppMode.Loading)

    fun onEvent(event: ModeEvent) {
        when (event) {
            is ModeEvent.EnterKidMode -> {
                savedStateHandle[KID_ID_KEY] = event.kidId.value
                parentLock.lock()
            }

            ModeEvent.ExitKidMode -> savedStateHandle[KID_ID_KEY] = null

            ModeEvent.BeginOnboarding -> onboarding.value = true

            ModeEvent.EndOnboarding -> onboarding.value = false
        }
    }

    /** The kid's UI tier, or null when the kid does not exist. */
    suspend fun tierOf(kidId: KidId): KidUiTier? = kids.getKid(kidId)?.uiTier

    // A database closed by a wipe ends the stream instead of crashing; the app restarts right after a wipe.
    private fun sessionViews(sessions: SessionStore): Flow<SessionView> =
        sessions.observe().map<Session?, SessionView> { SessionView.Loaded(it) }.catch { }

    companion object {
        /** [SavedStateHandle] key of the kid-mode kid id; absent in parent mode. */
        const val KID_ID_KEY = "kidModeKidId"
    }
}

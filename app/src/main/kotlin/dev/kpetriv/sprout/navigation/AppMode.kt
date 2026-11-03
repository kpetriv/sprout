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
import dev.kpetriv.sprout.sync.RemovalReason
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
    /** The session is being read. */
    data object Loading : AppMode

    /**
     * Onboarding (M3 §6): no session, a second parent phone waiting for approval, or the trial household's
     * "Set up sync". [canLeave] when there is a trial household to go back to.
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

    /** This phone was removed from the household and wipes its data (M3 §6). */
    data class Removed(val reason: RemovalReason) : AppMode
}

/** Locks the parent area; the app binds it to `feature/lock`'s `ParentSession.lock`. */
fun interface ParentLock {
    fun lock()
}

/** Why this phone must wipe, or null; the app binds it to `SyncController.removal`. */
fun interface RemovalSignal {
    fun removal(): StateFlow<RemovalReason?>
}

/** The signed-in session as the shell sees it: still loading, or the stored session (null when signed out). */
internal sealed interface SessionView {
    data object Loading : SessionView

    data class Loaded(val session: Session?) : SessionView
}

/**
 * The [AppMode] for a session (M3 §6):
 * - a removal wins over everything;
 * - no session, or a PARENT session whose device still waits for approval, is onboarding; so is the trial session
 *   while the parent is setting up sync ([onboarding]);
 * - a KID session is kid mode for its kid only, with no parent area;
 * - a PARENT session (the trial one included) is the parent area, or kid mode for [kidModeKid] on this phone.
 */
internal fun appModeOf(
    session: SessionView,
    onboarding: Boolean,
    kidModeKid: KidId?,
    removal: RemovalReason?,
): AppMode {
    val current = (session as? SessionView.Loaded)?.session
    return when {
        removal != null -> AppMode.Removed(removal)
        session !is SessionView.Loaded -> AppMode.Loading
        current == null -> AppMode.Onboarding(canLeave = false)
        onboarding -> AppMode.Onboarding(canLeave = current.isLocalTrial)
        current.role == Role.KID -> AppMode.Kid(checkNotNull(current.kidId), linkedDevice = true)
        current.deviceStatus == RegistrationStatus.PENDING -> AppMode.Onboarding(canLeave = false)
        kidModeKid != null -> AppMode.Kid(kidModeKid)
        else -> AppMode.Parent
    }
}

/**
 * The app's [AppMode], derived from the stored session ([SessionStore.observe]). Kid mode on a parent's phone is kept
 * in the [SavedStateHandle] so it survives process death (parent mode then starts locked anyway, the parent session
 * being in memory only). Entering kid mode locks the parent session, so handing the device to a kid never leaves the
 * parent area one tap away. Onboarding stays on screen until it says it is finished, even once it saved the session
 * (the PIN and signing step comes after that).
 */
@HiltViewModel
class ModeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val kids: KidRepository,
    private val parentLock: ParentLock,
    sessions: SessionStore,
    removal: RemovalSignal,
    startup: StartupStatus,
) : ViewModel() {
    private val onboarding = MutableStateFlow(false)

    // The session is only final once startup restored and reconciled it (and seeded the debug trial); until then the
    // mode is Loading, so a session that is about to appear never flashes onboarding.
    val mode: StateFlow<AppMode> = combine(
        combine(sessionViews(sessions), startup.state) { session, state ->
            if (state == StartupState.Running) SessionView.Loading else session
        },
        onboarding,
        savedStateHandle.getStateFlow<String?>(KID_ID_KEY, null),
        removal.removal(),
    ) { session, onboardingNow, kidId, removed ->
        appModeOf(session, onboardingNow, kidId?.let(::KidId), removed)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppMode.Loading)

    /** Switches to kid mode for [kidId] and locks the parent area. */
    fun enterKidMode(kidId: KidId) {
        savedStateHandle[KID_ID_KEY] = kidId.value
        parentLock.lock()
    }

    /** Back to the parent area. Call only after the kid-mode exit gate unlocked the parent session. */
    fun exitKidMode() {
        savedStateHandle[KID_ID_KEY] = null
    }

    /** Onboarding is on screen (no session, or the trial household's "Set up sync"). */
    fun beginOnboarding() {
        onboarding.value = true
    }

    /** Onboarding finished or was left: the mode follows the session again. */
    fun endOnboarding() {
        onboarding.value = false
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

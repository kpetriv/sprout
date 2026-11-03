package dev.kpetriv.sprout.navigation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Who the app is showing itself to. */
sealed interface AppMode {
    /** The parent area, behind the parent lock. */
    data object Parent : AppMode

    /** Kid mode for [kidId]: no lock, until the parent opens the kid-mode exit gate. */
    data class Kid(val kidId: KidId) : AppMode
}

/** Locks the parent area; the app binds it to `feature/lock`'s `ParentSession.lock`. */
fun interface ParentLock {
    fun lock()
}

/**
 * The app's [AppMode], kept in the [SavedStateHandle] so kid mode survives process death (parent mode then starts
 * locked anyway, the parent session being in memory only). Entering kid mode locks the parent session, so handing
 * the device to a kid never leaves the parent area one tap away.
 */
@HiltViewModel
class ModeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val kids: KidRepository,
    private val parentLock: ParentLock,
) : ViewModel() {
    val mode: StateFlow<AppMode> = savedStateHandle.getStateFlow<String?>(KID_ID_KEY, null)
        .map(::modeOf)
        .stateIn(viewModelScope, SharingStarted.Eagerly, modeOf(savedStateHandle[KID_ID_KEY]))

    /** Switches to kid mode for [kidId] and locks the parent area. */
    fun enterKidMode(kidId: KidId) {
        savedStateHandle[KID_ID_KEY] = kidId.value
        parentLock.lock()
    }

    /** Back to the parent area. Call only after the kid-mode exit gate unlocked the parent session. */
    fun exitKidMode() {
        savedStateHandle[KID_ID_KEY] = null
    }

    /** The kid's UI tier, or null when the kid does not exist. */
    suspend fun tierOf(kidId: KidId): KidUiTier? = kids.getKid(kidId)?.uiTier

    private fun modeOf(kidId: String?): AppMode = kidId?.let { AppMode.Kid(KidId(it)) } ?: AppMode.Parent

    companion object {
        /** [SavedStateHandle] key of the kid-mode kid id; absent in parent mode. */
        const val KID_ID_KEY = "kidModeKidId"
    }
}

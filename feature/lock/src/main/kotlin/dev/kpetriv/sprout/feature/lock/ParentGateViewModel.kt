package dev.kpetriv.sprout.feature.lock

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@Immutable
data class ParentGateUiState(
    val session: ParentSessionState = ParentSessionState.Initializing,
    /** For [ParentSignaturePolicyBanner]: whether money events on this device can carry a parent signature. */
    val parentAuthAvailability: ParentAuthAvailability = ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE,
)

sealed interface ParentGateEvent {
    /** Lock the parent area now (for example on entering kid mode). */
    data object Lock : ParentGateEvent

    /** Re-read the biometric availability (the parent may have enrolled a fingerprint in system settings). */
    data object RefreshAvailability : ParentGateEvent
}

/** Session state for [RequireParent] and [KidModeExitGate], plus the parent-signature availability. */
@HiltViewModel
class ParentGateViewModel @Inject constructor(private val session: ParentSession) : ViewModel() {
    private val availability = MutableStateFlow(session.biometricAvailability())

    val state: StateFlow<ParentGateUiState> = combine(session.state, availability, ::ParentGateUiState)
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            ParentGateUiState(session.state.value, availability.value),
        )

    fun onEvent(event: ParentGateEvent) {
        when (event) {
            ParentGateEvent.Lock -> session.lock()
            ParentGateEvent.RefreshAvailability -> availability.value = session.biometricAvailability()
        }
    }
}

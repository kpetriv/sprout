package dev.kpetriv.sprout.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.model.HouseholdId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The settings hub: which sections exist (the trial household has no account, devices or household data). */
@HiltViewModel
class SettingsHubViewModel @Inject constructor(account: AccountControl) : ViewModel() {
    /** True for a real (synced) household; false for the trial or while unknown. */
    val synced: StateFlow<Boolean> = account.observe()
        .map { !it.trial && it.householdId != null }
        .catch { emit(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)
}

data class AccountUiState(
    val email: String? = null,
    val householdId: HouseholdId? = null,
    val confirmingSignOut: Boolean = false,
    val signingOut: Boolean = false,
) {
    override fun toString(): String = "AccountUiState(householdId=$householdId, signingOut=$signingOut)"
}

sealed interface AccountEvent {
    data object AskSignOut : AccountEvent

    data object DismissSignOut : AccountEvent

    data object ConfirmSignOut : AccountEvent
}

/**
 * Settings → Account: the signed-in parent and "Sign out", which erases this phone's copy of the household after a
 * confirmation (D-M3-47); the app then shows the erase screen and restarts into onboarding.
 */
@HiltViewModel
class AccountViewModel @Inject constructor(private val account: AccountControl) : ViewModel() {
    private val local = MutableStateFlow(AccountUiState())

    val state: StateFlow<AccountUiState> = combine(account.observe().catch { }, local) { info, localState ->
        localState.copy(email = info.email, householdId = info.householdId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AccountUiState())

    fun onEvent(event: AccountEvent) {
        when (event) {
            AccountEvent.AskSignOut -> local.update { it.copy(confirmingSignOut = true) }

            AccountEvent.DismissSignOut -> local.update { it.copy(confirmingSignOut = false) }

            AccountEvent.ConfirmSignOut -> {
                if (local.value.signingOut) return
                local.update { it.copy(confirmingSignOut = false, signingOut = true) }
                viewModelScope.launch {
                    attempt { account.signOut() }
                    local.update { it.copy(signingOut = false) }
                }
            }
        }
    }
}

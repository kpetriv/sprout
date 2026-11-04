package dev.kpetriv.sprout.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings → On-device AI.
 *
 * @property settings null while loading or after [loadFailed].
 * @property isSaving a switch change is being written.
 */
data class AiSettingsUiState(
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val settings: AiSettings? = null,
    val isSaving: Boolean = false,
    val message: SettingsMessage? = null,
) {
    /** The consent row is offered while the model can be (or is being) downloaded. */
    val showsDownloadConsent: Boolean
        get() = settings?.status == AiModelStatus.DOWNLOADABLE || settings?.status == AiModelStatus.DOWNLOADING

    /** The consent switch works only with AI on and nothing being written. */
    val canChangeConsent: Boolean get() = settings?.enabled == true && !isSaving
}

sealed interface AiSettingsEvent {
    data class EnabledChanged(val enabled: Boolean) : AiSettingsEvent

    /** On asks the system to download the model on Wi-Fi (D-M6-6); off withdraws the consent. */
    data class DownloadConsentChanged(val consent: Boolean) : AiSettingsEvent

    data object MessageShown : AiSettingsEvent
}

/** Shows [AiSettingsSource]'s status and preferences and writes the two switches; a failed write shows a message. */
@HiltViewModel
class AiSettingsViewModel @Inject constructor(private val source: AiSettingsSource) : ViewModel() {
    private val local = MutableStateFlow(Local())

    val state: StateFlow<AiSettingsUiState> = combine(settings(), local) { loaded, current ->
        when (loaded) {
            Load.Loading -> AiSettingsUiState(message = current.message)

            Load.Failed -> AiSettingsUiState(isLoading = false, loadFailed = true, message = current.message)

            is Load.Ready -> AiSettingsUiState(
                isLoading = false,
                settings = loaded.value,
                isSaving = current.isSaving,
                message = current.message,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AiSettingsUiState())

    fun onEvent(event: AiSettingsEvent) {
        when (event) {
            is AiSettingsEvent.EnabledChanged -> write { source.setEnabled(event.enabled) }

            is AiSettingsEvent.DownloadConsentChanged -> if (state.value.canChangeConsent) {
                write { source.setDownloadConsent(event.consent) }
            }

            AiSettingsEvent.MessageShown -> local.update { it.copy(message = null) }
        }
    }

    private fun settings(): Flow<Load<AiSettings>> = source.observe()
        .map<AiSettings, Load<AiSettings>> { Load.Ready(it) }
        .onStart { emit(Load.Loading) }
        .catch { emit(Load.Failed) }

    private fun write(block: suspend () -> Unit) {
        if (local.value.isSaving || state.value.settings == null) return
        local.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val ok = attempt(block)
            local.update { it.copy(isSaving = false, message = if (ok) null else SettingsMessage.SAVE_FAILED) }
        }
    }

    private data class Local(val isSaving: Boolean = false, val message: SettingsMessage? = null)
}

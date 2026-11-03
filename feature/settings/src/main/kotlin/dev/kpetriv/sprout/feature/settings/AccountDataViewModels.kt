package dev.kpetriv.sprout.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.time.HouseholdCalendar
import kotlinx.coroutines.CancellationException
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

/** Settings → Account: the signed-in parent and "Sign out" (the data on this phone stays; onboarding follows). */
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

/** A finished export waiting for the parent to pick where to save it. [bytes] is the zip. */
class PendingExport(val fileName: String, val bytes: ByteArray) {
    override fun toString(): String = "PendingExport(fileName=$fileName, size=${bytes.size})"
}

enum class DataMessage {
    EXPORTED,
    EXPORT_FAILED,
    EXPORT_TOO_LARGE,
    DELETE_FAILED,
    NETWORK,
}

data class DataUiState(
    val householdId: HouseholdId? = null,
    val exporting: Boolean = false,
    val pendingExport: PendingExport? = null,
    /** The delete dialog: what the parent typed so far. Null while closed. */
    val deleteTyped: String? = null,
    val deleting: Boolean = false,
    val message: DataMessage? = null,
) {
    /** The household id's first [CONFIRMATION_LENGTH] characters, which the parent types to confirm a deletion. */
    val deleteConfirmation: String get() = householdId?.value?.take(CONFIRMATION_LENGTH).orEmpty()

    val canConfirmDelete: Boolean
        get() = !deleting && deleteConfirmation.isNotEmpty() && deleteTyped == deleteConfirmation

    companion object {
        const val CONFIRMATION_LENGTH: Int = 8
    }
}

sealed interface DataEvent {
    data object Export : DataEvent

    /** The parent picked where to save [PendingExport] ([destination] is the document URI), or cancelled (null). */
    data class ExportDestination(val destination: String?) : DataEvent

    data object AskDelete : DataEvent

    data class DeleteTyped(val text: String) : DataEvent

    data object DismissDelete : DataEvent

    data object ConfirmDelete : DataEvent

    data object MessageShown : DataEvent
}

/**
 * Settings → Data. "Export household" fetches the `exportHousehold` bundle and offers a zip with the JSON and a
 * derived `ledger.csv` ([HouseholdExport]) for the system file picker. "Delete household" needs the household id's
 * first 8 characters typed, deletes it on the server, then erases this phone and restarts to onboarding.
 */
@HiltViewModel
class DataViewModel @Inject constructor(
    private val data: HouseholdData,
    private val writer: ExportWriter,
    private val calendar: HouseholdCalendar,
    account: AccountControl,
) : ViewModel() {
    private val local = MutableStateFlow(DataUiState())

    val state: StateFlow<DataUiState> = combine(account.observe().catch { }, local) { info, localState ->
        localState.copy(householdId = info.householdId)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DataUiState())

    fun onEvent(event: DataEvent) {
        when (event) {
            DataEvent.Export -> export()

            is DataEvent.ExportDestination -> save(event.destination)

            DataEvent.AskDelete -> local.update { it.copy(deleteTyped = "") }

            is DataEvent.DeleteTyped -> local.update {
                if (it.deleteTyped == null) it else it.copy(deleteTyped = event.text.trim().take(MAX_TYPED))
            }

            DataEvent.DismissDelete -> local.update { if (it.deleting) it else it.copy(deleteTyped = null) }

            DataEvent.ConfirmDelete -> delete()

            DataEvent.MessageShown -> local.update { it.copy(message = null) }
        }
    }

    private fun export() {
        if (local.value.exporting || local.value.pendingExport != null) return
        local.update { it.copy(exporting = true) }
        viewModelScope.launch {
            val result = safely { data.export() }
            local.update { state ->
                result.fold(
                    onSuccess = { bundle ->
                        val name = "sprout-export-${calendar.today()}.zip"
                        state.copy(exporting = false, pendingExport = PendingExport(name, HouseholdExport.zip(bundle)))
                    },
                    onFailure = { failure ->
                        val message = when ((failure as? AdminException)?.failure) {
                            AdminFailure.TOO_LARGE -> DataMessage.EXPORT_TOO_LARGE
                            AdminFailure.NETWORK -> DataMessage.NETWORK
                            else -> DataMessage.EXPORT_FAILED
                        }
                        state.copy(exporting = false, message = message)
                    },
                )
            }
        }
    }

    private fun save(destination: String?) {
        val pending = local.value.pendingExport ?: return
        if (destination == null) {
            local.update { it.copy(pendingExport = null) }
            return
        }
        viewModelScope.launch {
            val written = attempt { writer.write(destination, pending.bytes) }
            local.update {
                it.copy(
                    pendingExport = null,
                    message = if (written) DataMessage.EXPORTED else DataMessage.EXPORT_FAILED,
                )
            }
        }
    }

    private fun delete() {
        if (!state.value.canConfirmDelete) return
        local.update { it.copy(deleting = true) }
        viewModelScope.launch {
            val result = safely { data.deleteHousehold() }
            if (result.isSuccess) {
                data.eraseAndRestart()
            } else {
                val message = if ((result.exceptionOrNull() as? AdminException)?.failure == AdminFailure.NETWORK) {
                    DataMessage.NETWORK
                } else {
                    DataMessage.DELETE_FAILED
                }
                local.update { it.copy(deleting = false, deleteTyped = null, message = message) }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Any failure becomes a message.
    private suspend fun <T> safely(block: suspend () -> Result<T>): Result<T> = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    private companion object {
        const val MAX_TYPED = 64
    }
}

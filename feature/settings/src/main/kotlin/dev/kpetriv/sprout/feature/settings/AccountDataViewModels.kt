package dev.kpetriv.sprout.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.common.Dispatcher
import dev.kpetriv.sprout.core.common.SproutDispatchers
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.time.HouseholdCalendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/** Why the "Sign in again to confirm" step did not get through. */
enum class ReauthProblem {
    WRONG_ACCOUNT,
    SIGN_IN_FAILED,
    NETWORK,
}

/**
 * The "Sign in again to confirm" step of a deletion (D-M3-34: the server needs a sign-in within 5 minutes).
 *
 * @property usesPassword the debug path (e-mail/password against the Auth emulator) asks for the password; release
 *   builds use Google.
 */
data class ReauthUi(val usesPassword: Boolean, val busy: Boolean = false, val problem: ReauthProblem? = null)

data class DataUiState(
    val householdId: HouseholdId? = null,
    val exporting: Boolean = false,
    val pendingExport: PendingExport? = null,
    /** The delete dialog: what the parent typed so far. Null while closed. */
    val deleteTyped: String? = null,
    val deleting: Boolean = false,
    /** The "Sign in again to confirm" step; null while not needed. */
    val reauth: ReauthUi? = null,
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

    /** Sign in again to confirm the deletion; [password] on the debug path only. Never logged. */
    class SignInAgain(val activityContext: Context, val password: String?) : DataEvent {
        override fun toString(): String = "SignInAgain(<redacted>)"
    }

    data object DismissReauth : DataEvent

    data object MessageShown : DataEvent
}

/**
 * Settings → Data. "Export household" fetches the `exportHousehold` bundle and builds a zip with the JSON and a
 * derived `ledger.csv` ([HouseholdExport], on [dispatcher], before the state changes) for the system file picker.
 * "Delete household" needs the household id's first 8 characters typed, deletes it on the server, then erases this
 * phone and restarts to onboarding. When the server wants a recent sign-in ([AdminFailure.REAUTH_REQUIRED]) the
 * parent signs in again ([HouseholdData.signInAgain], same account) and the deletion is retried once.
 */
@HiltViewModel
class DataViewModel @Inject constructor(
    private val data: HouseholdData,
    private val writer: ExportWriter,
    private val calendar: HouseholdCalendar,
    account: AccountControl,
    @param:Dispatcher(SproutDispatchers.IO) private val dispatcher: CoroutineDispatcher,
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

            is DataEvent.SignInAgain -> signInAgain(event.activityContext, event.password)

            DataEvent.DismissReauth -> local.update { if (it.reauth?.busy == true) it else it.copy(reauth = null) }

            DataEvent.MessageShown -> local.update { it.copy(message = null) }
        }
    }

    private fun export() {
        if (local.value.exporting || local.value.pendingExport != null) return
        local.update { it.copy(exporting = true) }
        viewModelScope.launch {
            // The zip (JSON and CSV of the whole household) is built off the main thread, before the state changes.
            val result = safely { data.export() }
                .mapCatching { bundle -> withContext(dispatcher) { HouseholdExport.zip(bundle) } }
            local.update { state ->
                result.fold(
                    onSuccess = { zip ->
                        val name = "sprout-export-${calendar.today()}.zip"
                        state.copy(exporting = false, pendingExport = PendingExport(name, zip))
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
            when {
                result.isSuccess -> data.eraseAndRestart()

                result.failure() == AdminFailure.REAUTH_REQUIRED -> local.update {
                    it.copy(deleting = false, deleteTyped = null, reauth = ReauthUi(data.signInUsesPassword))
                }

                else -> local.update { it.copy(deleting = false, deleteTyped = null, message = deleteMessage(result)) }
            }
        }
    }

    /** Signs the same parent in again, then retries the deletion once. */
    private fun signInAgain(activityContext: Context, password: String?) {
        val reauth = local.value.reauth ?: return
        if (reauth.busy) return
        local.update { it.copy(reauth = reauth.copy(busy = true, problem = null)) }
        viewModelScope.launch {
            val signedIn = safely { data.signInAgain(activityContext, password) }
            if (signedIn.isFailure) {
                val problem = when (signedIn.failure()) {
                    AdminFailure.WRONG_ACCOUNT -> ReauthProblem.WRONG_ACCOUNT
                    AdminFailure.NETWORK -> ReauthProblem.NETWORK
                    else -> ReauthProblem.SIGN_IN_FAILED
                }
                local.update { it.copy(reauth = reauth.copy(busy = false, problem = problem)) }
                return@launch
            }
            val result = safely { data.deleteHousehold() }
            if (result.isSuccess) {
                data.eraseAndRestart()
            } else {
                local.update { it.copy(reauth = null, message = deleteMessage(result)) }
            }
        }
    }

    private fun Result<*>.failure(): AdminFailure? = (exceptionOrNull() as? AdminException)?.failure

    private fun deleteMessage(result: Result<*>): DataMessage =
        if (result.failure() == AdminFailure.NETWORK) DataMessage.NETWORK else DataMessage.DELETE_FAILED

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

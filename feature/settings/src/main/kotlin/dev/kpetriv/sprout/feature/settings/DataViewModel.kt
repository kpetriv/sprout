package dev.kpetriv.sprout.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.common.Dispatcher
import dev.kpetriv.sprout.core.common.SproutDispatchers
import dev.kpetriv.sprout.core.database.HouseholdPolicyLimits
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A finished export waiting for the parent to pick where to save it. [bytes] is the zip. */
class PendingExport(val fileName: String, val bytes: ByteArray) {
    override fun toString(): String = "PendingExport(fileName=$fileName, size=${bytes.size})"
}

/** Where a running export is; [done] of [total] counts media items in [ExportStage.MEDIA], else both are 0. */
data class ExportProgress(val stage: ExportStage, val done: Int = 0, val total: Int = 0) {
    /** 0..1 while copying media; null (indeterminate) otherwise. */
    val fraction: Float? get() = if (stage == ExportStage.MEDIA && total > 0) done.toFloat() / total else null
}

enum class ExportStage {
    /** Waiting for the parent's fingerprint or PIN. */
    CONFIRMING,

    /** Fetching the household bundle from the server. */
    HOUSEHOLD,

    /** Decrypting the chat on this phone. */
    CHAT,

    /** Copying photos and voice notes from the blob cache (fetching missing ones). */
    MEDIA,

    /** Building the zip. */
    PACKING,
}

enum class DataMessage {
    EXPORTED,
    EXPORT_FAILED,
    EXPORT_TOO_LARGE,
    DELETE_FAILED,
    NETWORK,

    /** The parent's fingerprint or PIN check failed (locked out, or the prompt broke): nothing happened. */
    NOT_CONFIRMED,
    RETENTION_SAVED,
    RETENTION_FAILED,
    CHAT_DELETED,

    /** Deleted on this phone; the server copy goes when the server is reachable again. */
    CHAT_DELETED_ON_THIS_PHONE,
    CHAT_DELETE_FAILED,
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

/**
 * The chat retention row (M7 §2). [days] is the policy's value (null = forever); [pending] is a shorter period
 * waiting for the parent's confirmation, because it deletes older messages on every phone.
 */
data class RetentionUi(val days: Int?, val pending: RetentionChoice? = null, val saving: Boolean = false) {
    /** The offered periods, plus the current one when another device set a period not in the list. */
    val options: List<Int?> get() = (RETENTION_OPTIONS + listOf(days)).distinct().sortedBy { it ?: Int.MAX_VALUE }

    companion object {
        /** Forever, a month, three months, a year (M7 §2). */
        val RETENTION_OPTIONS: List<Int?> = HouseholdPolicyLimits.RETENTION_CHOICES
    }
}

/** A retention period the parent picked; [days] null is "forever". */
data class RetentionChoice(val days: Int?)

data class DataUiState(
    val householdId: HouseholdId? = null,
    val exportProgress: ExportProgress? = null,
    val pendingExport: PendingExport? = null,
    /** The delete dialog: what the parent typed so far. Null while closed. */
    val deleteTyped: String? = null,
    val deleting: Boolean = false,
    /** The "Sign in again to confirm" step; null while not needed. */
    val reauth: ReauthUi? = null,
    /** The chat retention row; null until the household policy is read. */
    val retention: RetentionUi? = null,
    /** The "Delete chat history now" dialog: what the parent typed so far. Null while closed. */
    val chatDeleteTyped: String? = null,
    val purgingChat: Boolean = false,
    val message: DataMessage? = null,
) {
    val exporting: Boolean get() = exportProgress != null

    /** The household id's first [CONFIRMATION_LENGTH] characters, which the parent types to confirm a deletion. */
    val deleteConfirmation: String get() = householdId?.value?.take(CONFIRMATION_LENGTH).orEmpty()

    val canConfirmDelete: Boolean
        get() = !deleting && deleteConfirmation.isNotEmpty() && deleteTyped == deleteConfirmation

    /** [CHAT_DELETE_WORD] typed (any case) and no purge running. */
    val canConfirmChatDelete: Boolean
        get() = !purgingChat && chatDeleteTyped?.trim()?.equals(CHAT_DELETE_WORD, ignoreCase = true) == true

    override fun toString(): String =
        "DataUiState(householdId=$householdId, exportProgress=$exportProgress, message=$message)"

    companion object {
        const val CONFIRMATION_LENGTH: Int = 8

        /** What the parent types to confirm "Delete chat history now". */
        const val CHAT_DELETE_WORD: String = "DELETE"
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

    /** The parent picked a retention period; a shorter one asks first. */
    data class RetentionChosen(val days: Int?) : DataEvent

    data object ConfirmRetention : DataEvent

    data object DismissRetention : DataEvent

    data object AskDeleteChat : DataEvent

    data class ChatDeleteTyped(val text: String) : DataEvent

    data object DismissDeleteChat : DataEvent

    data object ConfirmDeleteChat : DataEvent

    data object MessageShown : DataEvent
}

/**
 * Settings → Data.
 *
 * - "Keep chat messages for" writes the household policy's retention ([HouseholdRules]) after [ParentConfirmation]; a
 *   shorter period first asks, because every phone then deletes older messages (D-M7-8).
 * - "Delete chat history now" needs [DataUiState.CHAT_DELETE_WORD] typed and the parent's fingerprint or PIN
 *   ([ParentConfirmation]), then [ChatHistoryPurger] deletes every message here and on the server.
 * - "Export household" (v2) asks for the fingerprint or PIN, fetches the `exportHousehold` bundle, reads the chat
 *   decrypted on this phone and its media ([ChatExportSource], best effort per item), and builds one zip
 *   ([HouseholdExport], on [dispatcher]) for the system file picker, with [ExportProgress] along the way.
 * - "Delete household" needs the household id's first 8 characters typed, deletes it on the server, then erases this
 *   phone and restarts to onboarding. When the server wants a recent sign-in ([AdminFailure.REAUTH_REQUIRED]) the
 *   parent signs in again ([HouseholdData.signInAgain], same account) and the deletion is retried once.
 */
@HiltViewModel
@Suppress("LongParameterList", "TooManyFunctions") // The Data section's ports; one handler per action.
class DataViewModel @Inject constructor(
    private val data: HouseholdData,
    private val writer: ExportWriter,
    private val calendar: HouseholdCalendar,
    account: AccountControl,
    private val rules: HouseholdRules,
    private val purger: ChatHistoryPurger,
    private val chat: ChatExportSource,
    private val confirmation: ParentConfirmation,
    @param:Dispatcher(SproutDispatchers.IO) private val dispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val local = MutableStateFlow(DataUiState())

    /** The retention row's own state ([RetentionUi.pending], [RetentionUi.saving]); its days come from the policy. */
    private val retentionLocal = MutableStateFlow(RetentionUi(days = null))

    private val policy = rules.observe()
        .map<HouseholdRulesState, HouseholdRulesState?> { it }
        .onStart { emit(null) }
        .catch { emit(null) }

    val state: StateFlow<DataUiState> =
        combine(account.observe().catch { }, policy, retentionLocal, local) { info, rules, retention, now ->
            val row = rules?.let { retention.copy(days = it.chatRetentionDays) }
            now.copy(householdId = info.householdId, retention = row)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, DataUiState())

    fun onEvent(event: DataEvent) {
        when (event) {
            DataEvent.Export -> export()
            is DataEvent.ExportDestination -> save(event.destination)
            DataEvent.MessageShown -> local.update { it.copy(message = null) }
            else -> if (!onChatEvent(event)) onDeleteEvent(event)
        }
    }

    /** Deleting the household and the sign-in it may need. */
    private fun onDeleteEvent(event: DataEvent) {
        when (event) {
            DataEvent.AskDelete -> local.update { it.copy(deleteTyped = "") }

            is DataEvent.DeleteTyped -> local.update {
                if (it.deleteTyped == null) it else it.copy(deleteTyped = event.text.trim().take(MAX_TYPED))
            }

            DataEvent.DismissDelete -> local.update { if (it.deleting) it else it.copy(deleteTyped = null) }

            DataEvent.ConfirmDelete -> delete()

            is DataEvent.SignInAgain -> signInAgain(event.activityContext, event.password)

            DataEvent.DismissReauth -> local.update { if (it.reauth?.busy == true) it else it.copy(reauth = null) }

            else -> Unit
        }
    }

    /** The retention row and "Delete chat history now"; false for any other event. */
    private fun onChatEvent(event: DataEvent): Boolean {
        when (event) {
            is DataEvent.RetentionChosen -> chooseRetention(event.days)

            DataEvent.ConfirmRetention -> state.value.retention?.pending?.let { saveRetention(it.days) }

            DataEvent.DismissRetention -> updateRetention { if (it.saving) it else it.copy(pending = null) }

            DataEvent.AskDeleteChat -> local.update { if (it.purgingChat) it else it.copy(chatDeleteTyped = "") }

            is DataEvent.ChatDeleteTyped -> local.update {
                if (it.chatDeleteTyped == null) it else it.copy(chatDeleteTyped = event.text.take(MAX_TYPED))
            }

            DataEvent.DismissDeleteChat -> local.update { if (it.purgingChat) it else it.copy(chatDeleteTyped = null) }

            DataEvent.ConfirmDeleteChat -> deleteChat()

            else -> return false
        }
        return true
    }

    private fun chooseRetention(days: Int?) {
        val current = state.value.retention
        val inRange = days == null || days in 1..HouseholdRulesState.MAX_RETENTION_DAYS
        val unchanged = current == null || days == current.days
        if (unchanged || current.saving || !inRange) return
        val shorter = days != null && (current.days == null || days < current.days)
        if (shorter) updateRetention { it.copy(pending = RetentionChoice(days)) } else saveRetention(days)
    }

    /** Asks for the parent (D-M7-17: policy writes are parent-authorised in settings), then writes the policy. */
    private fun saveRetention(days: Int?) {
        if (state.value.retention?.saving == true) return
        updateRetention { it.copy(saving = true) }
        viewModelScope.launch {
            val message = when (confirm(ConfirmationPurpose.CHANGE_CHAT_RETENTION)) {
                ConfirmationOutcome.CONFIRMED -> {
                    val saved = attempt { rules.setChatRetentionDays(days) }
                    if (saved) DataMessage.RETENTION_SAVED else DataMessage.RETENTION_FAILED
                }

                ConfirmationOutcome.CANCELLED -> null

                ConfirmationOutcome.FAILED -> DataMessage.NOT_CONFIRMED
            }
            // A dismissed prompt keeps a pending confirmation open for another try.
            updateRetention {
                if (message ==
                    null
                ) {
                    it.copy(saving = false)
                } else {
                    it.copy(pending = null, saving = false)
                }
            }
            if (message != null) local.update { it.copy(message = message) }
        }
    }

    private fun updateRetention(transform: (RetentionUi) -> RetentionUi) = retentionLocal.update(transform)

    private fun deleteChat() {
        if (!state.value.canConfirmChatDelete) return
        local.update { it.copy(purgingChat = true) }
        viewModelScope.launch {
            val outcome = when (confirm(ConfirmationPurpose.DELETE_CHAT_HISTORY)) {
                ConfirmationOutcome.CONFIRMED -> purge()
                ConfirmationOutcome.CANCELLED -> null
                ConfirmationOutcome.FAILED -> DataMessage.NOT_CONFIRMED
            }
            local.update {
                // A cancelled prompt keeps the dialog open to try again; anything else closes it.
                if (outcome == null) {
                    it.copy(purgingChat = false)
                } else {
                    it.copy(purgingChat = false, chatDeleteTyped = null, message = outcome)
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // A failing adapter is a failed purge, not a crash.
    private suspend fun purge(): DataMessage {
        val outcome = try {
            purger.purgeAll()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ChatPurgeOutcome.FAILED
        }
        return when (outcome) {
            ChatPurgeOutcome.PURGED -> DataMessage.CHAT_DELETED
            ChatPurgeOutcome.PURGED_ON_THIS_PHONE -> DataMessage.CHAT_DELETED_ON_THIS_PHONE
            ChatPurgeOutcome.FAILED -> DataMessage.CHAT_DELETE_FAILED
        }
    }

    private fun export() {
        if (local.value.exporting || local.value.pendingExport != null) return
        progress(ExportProgress(ExportStage.CONFIRMING))
        viewModelScope.launch {
            when (confirm(ConfirmationPurpose.EXPORT_HOUSEHOLD)) {
                ConfirmationOutcome.CONFIRMED -> buildExport()

                ConfirmationOutcome.CANCELLED -> local.update { it.copy(exportProgress = null) }

                ConfirmationOutcome.FAILED ->
                    local.update { it.copy(exportProgress = null, message = DataMessage.NOT_CONFIRMED) }
            }
        }
    }

    private suspend fun buildExport() {
        progress(ExportProgress(ExportStage.HOUSEHOLD))
        val result = safely {
            data.export().map { bundle ->
                progress(ExportProgress(ExportStage.CHAT))
                val content = readChat()
                progress(ExportProgress(ExportStage.PACKING))
                // The zip (JSON, CSV, chat and media) is built off the main thread, before the state changes.
                withContext(dispatcher) { HouseholdExport.zip(bundle, content) }
            }
        }
        local.update { state ->
            result.fold(
                onSuccess = { zip ->
                    val name = "sprout-export-${calendar.today()}.zip"
                    state.copy(exportProgress = null, pendingExport = PendingExport(name, zip))
                },
                onFailure = { failure ->
                    val message = when ((failure as? AdminException)?.failure) {
                        AdminFailure.TOO_LARGE -> DataMessage.EXPORT_TOO_LARGE
                        AdminFailure.NETWORK -> DataMessage.NETWORK
                        else -> DataMessage.EXPORT_FAILED
                    }
                    state.copy(exportProgress = null, message = message)
                },
            )
        }
    }

    /** The chat and every media file that could be had; a thread whose media fails lists them as missing. */
    @Suppress("TooGenericExceptionCaught") // One unreadable thread's media must not sink the export.
    private suspend fun readChat(): ChatExportContent {
        val threads = withContext(dispatcher) { chat.threads() }
        val total = HouseholdExport.mediaFileNames(threads).size
        val media = LinkedHashMap<String, ByteArray>()
        val missing = mutableListOf<ChatMissingMedia>()
        fun done() = (media.keys + missing.map { it.fileName }).size.coerceAtMost(total)
        if (total > 0) progress(ExportProgress(ExportStage.MEDIA, done = 0, total = total))
        threads.forEach { thread ->
            val sink = ChatMediaSink { fileName, bytes ->
                // The reader wipes its buffer after this returns.
                media[fileName] = bytes.copyOf()
                progress(ExportProgress(ExportStage.MEDIA, done = done(), total = total))
            }
            missing += try {
                withContext(dispatcher) { chat.media(thread, sink) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                thread.messages.mapNotNull { it.media }.filter { it !in media }.distinct()
                    .map { ChatMissingMedia(it, NOT_AVAILABLE) }
            }
            if (total > 0) progress(ExportProgress(ExportStage.MEDIA, done = done(), total = total))
        }
        return ChatExportContent(threads, media, missing)
    }

    private fun progress(progress: ExportProgress) = local.update { it.copy(exportProgress = progress) }

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

    /** [ParentConfirmation.confirm]; a throwing adapter counts as [ConfirmationOutcome.FAILED]. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun confirm(purpose: ConfirmationPurpose): ConfirmationOutcome = try {
        confirmation.confirm(purpose)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        ConfirmationOutcome.FAILED
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

        /** Why media is missing when the reader itself failed (its own reason name for "could not be had"). */
        const val NOT_AVAILABLE = "NOT_AVAILABLE"
    }
}

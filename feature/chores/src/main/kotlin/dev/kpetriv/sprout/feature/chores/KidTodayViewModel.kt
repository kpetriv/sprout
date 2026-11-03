package dev.kpetriv.sprout.feature.chores

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.database.ChoreRepository
import dev.kpetriv.sprout.core.database.FiatCurrencyProvider
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.database.PhotoStore
import dev.kpetriv.sprout.core.database.SubmissionFilter
import dev.kpetriv.sprout.core.domainhousehold.Chore
import dev.kpetriv.sprout.core.domainhousehold.ChoreProposalRules
import dev.kpetriv.sprout.core.domainhousehold.ChoreReward
import dev.kpetriv.sprout.core.domainhousehold.ChoreScheduler
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.ChoreState
import dev.kpetriv.sprout.core.model.ChoreSubmission
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.MAX_NOTE_LENGTH
import dev.kpetriv.sprout.core.model.MAX_TITLE_LENGTH
import dev.kpetriv.sprout.core.model.PhotoRef
import dev.kpetriv.sprout.core.model.SubmissionStatus
import dev.kpetriv.sprout.core.time.Clock
import dev.kpetriv.sprout.core.time.HouseholdCalendar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Where one of today's chores stands for the kid. */
enum class OccurrenceStatus {
    TO_DO,

    /** Submitted; waiting for a parent. */
    WAITING,

    /** A parent rejected it (optionally with a reason). */
    NOT_ACCEPTED,

    /** Approved and paid. */
    DONE,
}

data class TodayChoreUi(
    val choreId: ChoreId,
    val title: String,
    val description: String?,
    val emoji: String,
    val dueOn: CalendarDate,
    val reward: RewardUi,
    val status: OccurrenceStatus,
    val rejectionReason: String? = null,
)

data class UpcomingChoreUi(
    val choreId: ChoreId,
    val title: String,
    val emoji: String,
    val dueOn: CalendarDate,
    val reward: RewardUi,
)

data class ProposedChoreUi(val choreId: ChoreId, val title: String, val reward: RewardUi)

enum class KidTodayError { KID_NOT_FOUND, LOAD_FAILED }

/** The Sprout / Sapling submission sheet: an optional note (at most [MAX_NOTE_LENGTH]) and photo. */
data class SubmitSheetUi(
    val choreId: ChoreId,
    val title: String,
    val note: String = "",
    val hasPhoto: Boolean = false,
    val isSubmitting: Boolean = false,
)

/** The Sapling "Propose a chore" sheet with a suggested reward; [errors] appear after the first try. */
data class ProposeSheetUi(
    val title: String = "",
    val moneyText: String = "",
    val minutesText: String = "",
    val errors: Set<EditorError> = emptySet(),
    val isSubmitting: Boolean = false,
)

sealed interface KidTodayMessage {
    data object Submitted : KidTodayMessage

    data object SubmitFailed : KidTodayMessage

    data object PhotoFailed : KidTodayMessage

    data object Proposed : KidTodayMessage

    data object ProposeFailed : KidTodayMessage
}

sealed interface KidTodayUiState {
    data object Loading : KidTodayUiState

    data class Error(val reason: KidTodayError) : KidTodayUiState

    /**
     * [today] is every chore due today, to-do first; Seedling shows only [seedlingCards]. [upcoming] covers the
     * next seven days (Sprout and Sapling). [celebration] is the chore just finished, for the confetti.
     */
    data class Ready(
        val kidName: String,
        val tier: KidUiTier,
        val date: CalendarDate,
        val today: List<TodayChoreUi>,
        val upcoming: List<UpcomingChoreUi> = emptyList(),
        val proposals: List<ProposedChoreUi> = emptyList(),
        val currency: Currency.Fiat,
        val submitting: Set<ChoreId> = emptySet(),
        val sheet: SubmitSheetUi? = null,
        val propose: ProposeSheetUi? = null,
        val celebration: String? = null,
        val message: KidTodayMessage? = null,
    ) : KidTodayUiState {
        val canPropose: Boolean get() = ChoreProposalRules.canPropose(tier)
        val seedlingCards: List<TodayChoreUi> get() = today.take(MAX_SEEDLING_CARDS)

        companion object {
            /** Seedling sees at most this many picture cards at once. */
            const val MAX_SEEDLING_CARDS: Int = 3
        }
    }
}

sealed interface KidTodayEvent {
    /** Seedling's one-tap "Done!": submits today's occurrence with no note or photo. */
    data class Done(val choreId: ChoreId) : KidTodayEvent

    data class OpenSubmit(val choreId: ChoreId) : KidTodayEvent

    data class NoteChanged(val text: String) : KidTodayEvent

    data class PhotoCaptured(val bytes: ByteArray) : KidTodayEvent {
        override fun equals(other: Any?): Boolean = other is PhotoCaptured && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data object PhotoFailed : KidTodayEvent

    data object RemovePhoto : KidTodayEvent

    data object ConfirmSubmit : KidTodayEvent

    data object DismissSubmit : KidTodayEvent

    data object OpenPropose : KidTodayEvent

    data class ProposeTitleChanged(val text: String) : KidTodayEvent

    data class ProposeMoneyChanged(val text: String) : KidTodayEvent

    data class ProposeMinutesChanged(val text: String) : KidTodayEvent

    data object ConfirmPropose : KidTodayEvent

    data object DismissPropose : KidTodayEvent

    data object CelebrationShown : KidTodayEvent

    data object MessageShown : KidTodayEvent

    data object Retry : KidTodayEvent
}

/**
 * A kid's chores for today ([ChoreScheduler.isDueOn]) and the next seven days, per [KidUiTier]. Arguments from the
 * [SavedStateHandle]: [KID_ID_KEY] and [TIER_KEY] (a [KidUiTier] name). "Today" comes from [HouseholdCalendar] and
 * moves on at the household's midnight while the screen stays open.
 *
 * Each occurrence is submitted at most once from here (a submit in flight or an existing submission blocks a
 * second one; the repository is idempotent too). A captured photo is encrypted through [PhotoStore] right away, so
 * no plaintext stays in memory; it is deleted if the kid removes it or closes the sheet, and attached when the
 * submission is stored. The open submit sheet (chore, note, pending photo) lives in the [SavedStateHandle], so it
 * survives process death while the camera app is in front. Only Sapling kids can propose chores
 * ([ChoreProposalRules.canPropose]); a proposal is a [ChoreState.PROPOSED] chore for a parent to accept.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
@Suppress("LongParameterList") // Repositories, photo store, currency and calendar are separate collaborators.
class KidTodayViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val chores: ChoreRepository,
    private val kids: KidRepository,
    private val photoStore: PhotoStore,
    private val fiat: FiatCurrencyProvider,
    private val clock: Clock,
    private val calendar: HouseholdCalendar,
) : ViewModel() {
    private val kidId = KidId(checkNotNull(savedStateHandle.get<String>(KID_ID_KEY)) { "kidId argument missing" })
    private val tier = savedStateHandle.get<String>(TIER_KEY)?.let(KidUiTier::valueOf) ?: KidUiTier.SEEDLING
    private val local = MutableStateFlow(Local(sheet = restoredSheet()))
    private val retries = MutableStateFlow(0)

    /** The kid's data for today, derived from the repositories and the calendar; read by the event handlers. */
    private val loaded: StateFlow<Loaded> = retries.flatMapLatest {
        kids.observeKids().map { list -> list.firstOrNull { it.id == kidId } }.distinctUntilChanged()
            .flatMapLatest { kid ->
                if (kid == null) {
                    flowOf(Loaded.Failed(KidTodayError.KID_NOT_FOUND))
                } else {
                    combine(
                        chores.observeChores(kidId),
                        chores.observeSubmissions(SubmissionFilter.ForKid(kidId)),
                        calendar.todayUpdates(),
                    ) { choreList, submissions, today ->
                        Loaded.Ready(Data(kid.firstName, kid.householdId, choreList, submissions, today)) as Loaded
                    }
                }
            }.catch { emit(Loaded.Failed(KidTodayError.LOAD_FAILED)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), Loaded.Loading)

    val state: StateFlow<KidTodayUiState> = combine(loaded, local) { data, localState ->
        when (data) {
            Loaded.Loading -> KidTodayUiState.Loading
            is Loaded.Failed -> KidTodayUiState.Error(data.reason)
            is Loaded.Ready -> ready(data.data, localState)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), KidTodayUiState.Loading)

    private val latest: Data? get() = (loaded.value as? Loaded.Ready)?.data

    fun onEvent(event: KidTodayEvent) {
        when (event) {
            is KidTodayEvent.Done -> submit(event.choreId, note = null, celebrate = true)

            is KidTodayEvent.OpenSubmit -> openSubmit(event.choreId)

            is KidTodayEvent.NoteChanged -> updateSheet { it.copy(note = event.text.take(MAX_NOTE_LENGTH)) }

            is KidTodayEvent.PhotoCaptured -> savePhoto(event.bytes)

            KidTodayEvent.PhotoFailed -> local.update { it.copy(message = KidTodayMessage.PhotoFailed) }

            KidTodayEvent.RemovePhoto -> if (local.value.sheet?.isSubmitting == false) {
                discardPendingPhoto()
                updateSheet { it.copy(hasPhoto = false) }
            }

            KidTodayEvent.ConfirmSubmit -> confirmSubmit()

            KidTodayEvent.DismissSubmit -> closeSheet()

            else -> onOtherEvent(event)
        }
    }

    private fun onOtherEvent(event: KidTodayEvent) {
        when (event) {
            KidTodayEvent.OpenPropose -> if (ChoreProposalRules.canPropose(tier)) {
                local.update { it.copy(propose = ProposeSheetUi()) }
            }

            is KidTodayEvent.ProposeTitleChanged -> updatePropose { it.copy(title = event.text.take(MAX_TITLE_LENGTH)) }

            is KidTodayEvent.ProposeMoneyChanged -> updatePropose { it.copy(moneyText = event.text.take(AMOUNT_CHARS)) }

            is KidTodayEvent.ProposeMinutesChanged ->
                updatePropose { it.copy(minutesText = event.text.take(AMOUNT_CHARS)) }

            KidTodayEvent.ConfirmPropose -> confirmPropose()

            KidTodayEvent.DismissPropose -> local.update { it.copy(propose = null) }

            KidTodayEvent.CelebrationShown -> local.update { it.copy(celebration = null) }

            KidTodayEvent.MessageShown -> local.update { it.copy(message = null) }

            KidTodayEvent.Retry -> retries.update { it + 1 }

            else -> Unit
        }
    }

    private fun ready(data: Data, localState: Local): KidTodayUiState.Ready {
        val currency = fiat.fiatCurrency()
        val active = data.chores.filter { it.state == ChoreState.ACTIVE }
        val today = active.mapNotNull { todayItem(it, data, currency) }
            .sortedWith(compareBy<TodayChoreUi> { it.status.ordinal }.thenBy { it.title.lowercase() })
        val upcoming = if (tier == KidUiTier.SEEDLING) {
            emptyList()
        } else {
            active.flatMap { chore ->
                ChoreScheduler.occurrences(chore, data.today.plusDays(1), data.today.plusDays(UPCOMING_DAYS))
                    .map { occurrence ->
                        val emoji = ChoreEmoji.forTitle(chore.title)
                        UpcomingChoreUi(chore.id, chore.title, emoji, occurrence.dueOn, reward(chore, currency))
                    }
            }.sortedWith(compareBy<UpcomingChoreUi> { it.dueOn }.thenBy { it.title.lowercase() })
        }
        val proposals = if (ChoreProposalRules.canPropose(tier)) {
            data.chores.filter { it.state == ChoreState.PROPOSED }
                .map { ProposedChoreUi(it.id, it.title, reward(it, currency)) }
        } else {
            emptyList()
        }
        return KidTodayUiState.Ready(
            kidName = data.kidName,
            tier = tier,
            date = data.today,
            today = today,
            upcoming = upcoming,
            proposals = proposals,
            currency = currency,
            submitting = localState.submitting,
            sheet = localState.sheet,
            propose = localState.propose,
            celebration = localState.celebration,
            message = localState.message,
        )
    }

    /**
     * Today's entry for [chore], or null when it is not due today. A one-off "any time" chore (`Once(null)`) has a
     * single occurrence keyed on [ChoreScheduler.anyTimeDueOn] whatever day it is done, so it pays once: it stays in
     * the list (waiting, or not accepted) until it is approved, and then disappears.
     */
    @Suppress("ReturnCount") // Guard clauses: each early return is a precondition that is not met.
    private fun todayItem(chore: Chore, data: Data, currency: Currency.Fiat): TodayChoreUi? {
        if (!ChoreScheduler.isDueOn(chore, data.today)) return null
        val dueOn = ChoreScheduler.occurrenceDate(chore, data.today)
        val submission = data.submissions.firstOrNull { it.choreId == chore.id && it.dueOn == dueOn }
        val anyTime = chore.schedule == ChoreSchedule.Once(null)
        if (anyTime && submission?.status == SubmissionStatus.APPROVED) return null
        return TodayChoreUi(
            choreId = chore.id,
            title = chore.title,
            description = chore.description,
            emoji = ChoreEmoji.forTitle(chore.title),
            dueOn = dueOn,
            reward = reward(chore, currency),
            status = submission.toStatus(),
            rejectionReason = submission?.rejectionReason?.takeIf { submission.status == SubmissionStatus.REJECTED },
        )
    }

    private fun openSubmit(choreId: ChoreId) {
        val item = currentToday(choreId) ?: return
        if (item.status != OccurrenceStatus.TO_DO) return
        discardPendingPhoto()
        updateSheet(SubmitSheetUi(choreId, item.title))
    }

    /**
     * Encrypts the captured photo at once and remembers its file in the [SavedStateHandle]; a photo already pending
     * is replaced (and deleted). A photo that arrives after the sheet was closed is deleted again.
     */
    private fun savePhoto(bytes: ByteArray) {
        val sheet = local.value.sheet ?: return
        if (sheet.isSubmitting) return
        viewModelScope.launch {
            val saved = attempt { photoStore.save(kidId, bytes) }.getOrNull()
            when {
                saved == null -> local.update { it.copy(message = KidTodayMessage.PhotoFailed) }

                local.value.sheet?.choreId != sheet.choreId || local.value.sheet?.isSubmitting == true ->
                    attempt { photoStore.delete(saved) }

                else -> {
                    discardPendingPhoto()
                    savedStateHandle[PHOTO_FILE_KEY] = saved.fileName
                    savedStateHandle[PHOTO_SHA_KEY] = saved.sha256
                    updateSheet { it.copy(hasPhoto = true) }
                }
            }
        }
    }

    /** The encrypted photo waiting to be submitted with the open sheet, if any. */
    private fun pendingPhoto(): PhotoRef? {
        val file = savedStateHandle.get<String>(PHOTO_FILE_KEY)
        val sha = savedStateHandle.get<String>(PHOTO_SHA_KEY)
        return if (file == null || sha == null) null else runCatching { PhotoRef(file, sha) }.getOrNull()
    }

    /** Deletes the pending photo (best effort) and forgets it. */
    private fun discardPendingPhoto() {
        val pending = pendingPhoto()
        forgetPendingPhoto()
        if (pending != null) viewModelScope.launch { attempt { photoStore.delete(pending) } }
    }

    private fun forgetPendingPhoto() {
        savedStateHandle.remove<String>(PHOTO_FILE_KEY)
        savedStateHandle.remove<String>(PHOTO_SHA_KEY)
    }

    /** The sheet that was open when the process died, from the [SavedStateHandle]. */
    private fun restoredSheet(): SubmitSheetUi? {
        val choreId = savedStateHandle.get<String>(SHEET_CHORE_KEY) ?: return null
        return SubmitSheetUi(
            choreId = ChoreId(choreId),
            title = savedStateHandle.get<String>(SHEET_TITLE_KEY).orEmpty(),
            note = savedStateHandle.get<String>(SHEET_NOTE_KEY).orEmpty(),
            hasPhoto = pendingPhoto() != null,
        )
    }

    private fun confirmSubmit() {
        val sheet = local.value.sheet ?: return
        if (sheet.isSubmitting) return
        submit(sheet.choreId, note = sheet.note.trim().ifEmpty { null }, celebrate = false)
    }

    private fun submit(choreId: ChoreId, note: String?, celebrate: Boolean) {
        val item = currentToday(choreId) ?: return
        if (item.status != OccurrenceStatus.TO_DO || choreId in local.value.submitting) return
        val photo = if (celebrate) null else pendingPhoto()
        local.update { state ->
            state.copy(submitting = state.submitting + choreId, sheet = state.sheet?.copy(isSubmitting = true))
        }
        viewModelScope.launch {
            // On failure the pending photo stays saved for the next try; it is deleted when the sheet is closed.
            val ok = attempt { chores.submit(choreId, item.dueOn, note, photo) }.isSuccess
            if (ok) {
                // The submitted photo now belongs to the submission; any other pending one is not needed any more.
                if (photo != null) forgetPendingPhoto() else discardPendingPhoto()
                persistSheet(null)
            }
            local.update { state ->
                state.copy(
                    submitting = state.submitting - choreId,
                    sheet = if (ok) null else state.sheet?.copy(isSubmitting = false),
                    celebration = if (ok && celebrate) item.title else state.celebration,
                    message = when {
                        !ok -> KidTodayMessage.SubmitFailed
                        celebrate -> state.message
                        else -> KidTodayMessage.Submitted
                    },
                )
            }
        }
    }

    @Suppress("ReturnCount") // Guard clauses: each early return is a precondition that is not met.
    private fun confirmPropose() {
        val sheet = local.value.propose ?: return
        val data = latest ?: return
        if (sheet.isSubmitting || !ChoreProposalRules.canPropose(tier)) return
        val form = ChoreEditorUiState(
            isLoading = false,
            kidId = kidId,
            title = sheet.title,
            moneyText = sheet.moneyText,
            minutesText = sheet.minutesText,
            schedule = ScheduleForm(kind = ScheduleKind.ONCE),
            currency = fiat.fiatCurrency(),
            today = data.today,
        )
        val errors = errorsOf(form)
        val draft = draftOf(form)
        if (errors.isNotEmpty() || draft == null) {
            updatePropose { it.copy(errors = errors) }
            return
        }
        updatePropose { it.copy(errors = emptySet(), isSubmitting = true) }
        val proposal = ChoreProposalRules.propose(
            Chore(
                id = ChoreId("chore-${UUID.randomUUID()}"),
                householdId = data.householdId,
                kidId = kidId,
                title = draft.title,
                reward = ChoreReward(draft.money, draft.minutes),
                schedule = draft.schedule,
                createdAt = clock.now(),
            ),
        )
        viewModelScope.launch {
            val ok = attempt { chores.upsert(proposal) }.isSuccess
            local.update { state ->
                state.copy(
                    propose = if (ok) null else state.propose?.copy(isSubmitting = false),
                    message = if (ok) KidTodayMessage.Proposed else KidTodayMessage.ProposeFailed,
                )
            }
        }
    }

    @Suppress("ReturnCount") // Guard clauses: each early return is a precondition that is not met.
    private fun currentToday(choreId: ChoreId): TodayChoreUi? {
        val data = latest ?: return null
        val chore = data.chores.firstOrNull { it.id == choreId && it.state == ChoreState.ACTIVE } ?: return null
        return todayItem(chore, data, fiat.fiatCurrency())
    }

    private fun closeSheet() {
        if (local.value.sheet?.isSubmitting == true) return
        discardPendingPhoto()
        updateSheet(null)
    }

    private fun updateSheet(transform: (SubmitSheetUi) -> SubmitSheetUi) {
        val updated = local.updateAndGet { state -> state.copy(sheet = state.sheet?.let(transform)) }
        persistSheet(updated.sheet)
    }

    private fun updateSheet(sheet: SubmitSheetUi?) {
        local.update { it.copy(sheet = sheet) }
        persistSheet(sheet)
    }

    /** Mirrors the open sheet's chore, title and note into the [SavedStateHandle]; null clears them. */
    private fun persistSheet(sheet: SubmitSheetUi?) {
        if (sheet == null) {
            savedStateHandle.remove<String>(SHEET_CHORE_KEY)
            savedStateHandle.remove<String>(SHEET_TITLE_KEY)
            savedStateHandle.remove<String>(SHEET_NOTE_KEY)
        } else {
            savedStateHandle[SHEET_CHORE_KEY] = sheet.choreId.value
            savedStateHandle[SHEET_TITLE_KEY] = sheet.title
            savedStateHandle[SHEET_NOTE_KEY] = sheet.note
        }
    }

    private fun updatePropose(transform: (ProposeSheetUi) -> ProposeSheetUi) =
        local.update { state -> state.copy(propose = state.propose?.let(transform)) }

    private fun reward(chore: Chore, currency: Currency.Fiat) = RewardUi.of(chore.reward, currency)

    private data class Local(
        val submitting: Set<ChoreId> = emptySet(),
        val sheet: SubmitSheetUi? = null,
        val propose: ProposeSheetUi? = null,
        val celebration: String? = null,
        val message: KidTodayMessage? = null,
    )

    private class Data(
        val kidName: String,
        val householdId: HouseholdId,
        val chores: List<Chore>,
        val submissions: List<ChoreSubmission>,
        val today: CalendarDate,
    )

    private sealed interface Loaded {
        data object Loading : Loaded

        data class Failed(val reason: KidTodayError) : Loaded

        class Ready(val data: Data) : Loaded
    }

    companion object {
        /** [SavedStateHandle] key of the kid id string. */
        const val KID_ID_KEY: String = "kidId"

        /** [SavedStateHandle] key of the [KidUiTier] name. */
        const val TIER_KEY: String = "tier"

        /** [SavedStateHandle] keys of the open submit sheet: its chore id, title and note text. */
        const val SHEET_CHORE_KEY: String = "submitSheet.choreId"
        const val SHEET_TITLE_KEY: String = "submitSheet.title"
        const val SHEET_NOTE_KEY: String = "submitSheet.note"

        /** [SavedStateHandle] keys of the encrypted photo waiting to be submitted: its file name and SHA-256. */
        const val PHOTO_FILE_KEY: String = "submitSheet.photoFile"
        const val PHOTO_SHA_KEY: String = "submitSheet.photoSha256"

        private const val UPCOMING_DAYS = 7
        private const val AMOUNT_CHARS = 12
    }
}

private fun ChoreSubmission?.toStatus(): OccurrenceStatus = when (this?.status) {
    null -> OccurrenceStatus.TO_DO
    SubmissionStatus.SUBMITTED -> OccurrenceStatus.WAITING
    SubmissionStatus.APPROVED -> OccurrenceStatus.DONE
    SubmissionStatus.REJECTED -> OccurrenceStatus.NOT_ACCEPTED
}

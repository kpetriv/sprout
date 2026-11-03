package dev.kpetriv.sprout.feature.chores

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.crypto.ParentAuthUnavailableException
import dev.kpetriv.sprout.core.crypto.ParentKeyInvalidatedException
import dev.kpetriv.sprout.core.database.ChoreRepository
import dev.kpetriv.sprout.core.database.DecisionResult
import dev.kpetriv.sprout.core.database.FiatCurrencyProvider
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.database.PhotoStore
import dev.kpetriv.sprout.core.database.SubmissionDecision
import dev.kpetriv.sprout.core.database.SubmissionFilter
import dev.kpetriv.sprout.core.database.usecase.ApprovalResult
import dev.kpetriv.sprout.core.domainhousehold.Chore
import dev.kpetriv.sprout.core.domainledger.ParentAuthorization
import dev.kpetriv.sprout.core.domainledger.ParentAuthorizationRequester
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.ChoreState
import dev.kpetriv.sprout.core.model.ChoreSubmission
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.MAX_NOTE_LENGTH
import dev.kpetriv.sprout.core.model.PhotoRef
import dev.kpetriv.sprout.core.model.SubmissionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.inject.Inject
import kotlin.time.Instant

/** A submission's photo in the inbox. */
@Immutable
sealed interface PhotoUi {
    data object Loading : PhotoUi

    data class Loaded(val image: ImageBitmap) : PhotoUi

    /** Missing, tampered with, or not an image ([dev.kpetriv.sprout.core.database.PhotoIntegrityException]). */
    data object Unavailable : PhotoUi

    /**
     * The submission came from the kid's own phone and its photo is not uploaded (yet): the reference synced, the
     * photo is still only on that phone (D-M3-9; M4 uploads it as a blob once the kid's thread key is there).
     */
    data object OnKidDevice : PhotoUi
}

data class SubmissionUi(
    val id: SubmissionId,
    val kidName: String,
    val choreTitle: String,
    val dueOn: CalendarDate,
    val submittedAt: Instant,
    val note: String?,
    val reward: RewardUi,
    val photo: PhotoUi?,
    /** Some rewards are already paid (an approval stopped halfway): the approve button reads "Finish approval". */
    val partlyPaid: Boolean = false,
)

data class ProposalUi(
    val choreId: ChoreId,
    val kidName: String,
    val title: String,
    val description: String?,
    val reward: RewardUi,
    val schedule: ChoreSchedule,
)

/** The reject sheet for [submissionId], with the reason typed so far (at most [MAX_NOTE_LENGTH] characters). */
data class RejectUi(val submissionId: SubmissionId, val choreTitle: String, val reason: String = "")

sealed interface ApprovalsMessage {
    /**
     * [paidNow] rewards were paid now; [alreadyPaid] were in the ledger before (nothing signed for them). [unsigned]
     * when this device paid without a fingerprint signature (D-M2-1); the message then carries the warning.
     */
    data class Approved(
        val kidName: String,
        val choreTitle: String,
        val paidNow: Int,
        val alreadyPaid: Int,
        val unsigned: Boolean = false,
    ) : ApprovalsMessage

    data class Rejected(val choreTitle: String) : ApprovalsMessage

    /** The ledger refused a reward and nothing of the chore is paid. Still in the inbox. */
    data class RewardRefused(val choreTitle: String) : ApprovalsMessage

    /**
     * The approval stopped halfway: [paid] of [total] rewards are in the ledger. Still in the inbox, where approving
     * again ("Finish approval") pays only the rest.
     */
    data class PartlyPaid(val choreTitle: String, val paid: Int, val total: Int) : ApprovalsMessage

    /**
     * The parent key was invalidated (fingerprints changed on this device): nothing more was paid until it is renewed
     * in settings. Still in the inbox.
     */
    data class KeyInvalidated(val choreTitle: String) : ApprovalsMessage

    /** Rejecting was refused because a reward of the chore is already paid; finish the approval instead. */
    data class AlreadyPaid(val choreTitle: String) : ApprovalsMessage

    /**
     * This device has no usable fingerprint but the household requires fingerprint-signed payments (D-M2-1): nothing
     * was paid. Still in the inbox.
     */
    data class SignatureRequired(val choreTitle: String) : ApprovalsMessage

    /** The parent cancelled (or the prompt failed); nothing more was signed. Still in the inbox. */
    data class AuthorizationCancelled(val choreTitle: String) : ApprovalsMessage

    data class NoAccount(val kidName: String) : ApprovalsMessage

    data object NotFound : ApprovalsMessage

    data class ProposalAccepted(val title: String) : ApprovalsMessage

    data class ProposalDeclined(val title: String) : ApprovalsMessage

    data object Failed : ApprovalsMessage
}

sealed interface ApprovalsUiState {
    data object Loading : ApprovalsUiState

    data object Error : ApprovalsUiState

    /**
     * [submissions] newest first; [busy] is the submission or proposal being decided (one at a time, D-M2-2:
     * one authorisation prompt per reward event).
     */
    data class Ready(
        val submissions: List<SubmissionUi>,
        val proposals: List<ProposalUi>,
        val busy: String? = null,
        val rejecting: RejectUi? = null,
        val message: ApprovalsMessage? = null,
    ) : ApprovalsUiState {
        val isEmpty: Boolean get() = submissions.isEmpty() && proposals.isEmpty()
    }
}

sealed interface ApprovalsEvent {
    data class Approve(val id: SubmissionId) : ApprovalsEvent

    data class OpenReject(val id: SubmissionId) : ApprovalsEvent

    data class RejectReasonChanged(val text: String) : ApprovalsEvent

    data object ConfirmReject : ApprovalsEvent

    data object DismissReject : ApprovalsEvent

    data class AcceptProposal(val id: ChoreId) : ApprovalsEvent

    data class DeclineProposal(val id: ChoreId) : ApprovalsEvent

    data object Retry : ApprovalsEvent

    data object MessageShown : ApprovalsEvent
}

/**
 * The parent's approvals inbox: pending submissions of every kid, newest first, and Sapling kids' chore proposals.
 *
 * Approving goes through [SubmissionApprover] (`ApproveSubmissionUseCase`), which asks for one parent authorisation
 * per reward currency still unpaid; each one is a [ParentAuthorizationRequester.request] with a reason naming the
 * kid, the amount and the chore. Rewards already in the ledger are reported, not paid again. Photos are decrypted
 * through [PhotoStore] once per photo (taken on this phone) or, for a photo the kid's phone uploaded
 * ([PhotoRef.blobId], M4 §2), fetched through [RemotePhotoLoader] and checked against the reference's SHA-256; then
 * decoded with [PhotoDecoder]. A photo that is neither here nor uploaded shows [PhotoUi.OnKidDevice].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
@Suppress("LongParameterList") // Repositories, photo store, approval and authorisation are separate collaborators.
class ApprovalsViewModel @Inject internal constructor(
    private val chores: ChoreRepository,
    private val kids: KidRepository,
    private val photoStore: PhotoStore,
    private val localPhotos: LocalPhotoCheck,
    private val approver: SubmissionApprover,
    private val requester: ParentAuthorizationRequester,
    private val reasons: ApprovalReasonText,
    private val decoder: PhotoDecoder,
    private val fiat: FiatCurrencyProvider,
    private val remotePhotos: RemotePhotoLoader,
) : ViewModel() {
    private val local = MutableStateFlow(Local())
    private val photos = MutableStateFlow<Map<PhotoRef, PhotoUi>>(emptyMap())
    private val retries = MutableStateFlow(0)

    /** Pending submissions, chores and kids as last read. */
    private val loaded: StateFlow<Loaded> = retries.flatMapLatest {
        combine(
            chores.observeSubmissions(SubmissionFilter.PendingForParent),
            chores.observeChores(includeArchived = true),
            kids.observeKids(),
        ) { submissions, choreList, kidList ->
            Snapshot(submissions, choreList.associateBy { it.id }, kidList.associateBy { it.id })
        }.onEach { loadPhotos(it.submissions) }
            .map<Snapshot, Loaded> { Loaded.Ready(it) }
            .catch { emit(Loaded.Failed) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), Loaded.Loading)

    val state: StateFlow<ApprovalsUiState> = combine(loaded, photos, local) { data, photoMap, localState ->
        when (data) {
            Loaded.Loading -> ApprovalsUiState.Loading
            Loaded.Failed -> ApprovalsUiState.Error
            is Loaded.Ready -> ready(data.snapshot, photoMap, localState)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ApprovalsUiState.Loading)

    /** The data the event handlers act on: what the screen shows. */
    private val snapshot: Snapshot get() = (loaded.value as? Loaded.Ready)?.snapshot ?: Snapshot.EMPTY

    fun onEvent(event: ApprovalsEvent) {
        when (event) {
            is ApprovalsEvent.Approve -> approve(event.id)

            is ApprovalsEvent.OpenReject -> openReject(event.id)

            is ApprovalsEvent.RejectReasonChanged -> local.update { state ->
                state.copy(rejecting = state.rejecting?.copy(reason = event.text.take(MAX_NOTE_LENGTH)))
            }

            ApprovalsEvent.ConfirmReject -> confirmReject()

            ApprovalsEvent.DismissReject -> local.update { it.copy(rejecting = null) }

            is ApprovalsEvent.AcceptProposal -> decideProposal(event.id, accept = true)

            is ApprovalsEvent.DeclineProposal -> decideProposal(event.id, accept = false)

            ApprovalsEvent.Retry -> retries.update { it + 1 }

            ApprovalsEvent.MessageShown -> local.update { it.copy(message = null) }
        }
    }

    private fun ready(data: Snapshot, photoMap: Map<PhotoRef, PhotoUi>, localState: Local): ApprovalsUiState.Ready {
        val currency = fiat.fiatCurrency()
        val submissions = data.submissions.sortedByDescending { it.submittedAt }.mapNotNull { submission ->
            val chore = data.chores[submission.choreId] ?: return@mapNotNull null
            SubmissionUi(
                id = submission.id,
                kidName = data.kidName(data.kids[submission.kidId]),
                choreTitle = chore.title,
                dueOn = submission.dueOn,
                submittedAt = submission.submittedAt,
                note = submission.note?.takeIf { it.isNotBlank() },
                reward = RewardUi.of(chore.reward, currency),
                photo = submission.photoRef?.let { photoMap[it] ?: PhotoUi.Loading },
            )
        }
        val proposals = data.chores.values.filter { it.state == ChoreState.PROPOSED }
            .sortedBy { it.createdAt }
            .map { chore ->
                ProposalUi(
                    choreId = chore.id,
                    kidName = data.kidName(data.kids[chore.kidId]),
                    title = chore.title,
                    description = chore.description,
                    reward = RewardUi.of(chore.reward, currency),
                    schedule = chore.schedule,
                )
            }
        return ApprovalsUiState.Ready(
            submissions.map { it.copy(partlyPaid = it.id in localState.partlyPaid) },
            proposals,
            localState.busy,
            localState.rejecting,
            localState.message,
        )
    }

    private fun loadPhotos(submissions: List<ChoreSubmission>) {
        val missing = submissions.mapNotNull { it.photoRef }.filter { it !in photos.value }
        if (missing.isEmpty()) return
        photos.update { map -> map + missing.associateWith { PhotoUi.Loading } }
        missing.forEach { ref ->
            viewModelScope.launch {
                val local = attempt { localPhotos.isStoredHere(ref) }.getOrNull() != false
                val blobId = ref.blobId
                if (!local && blobId == null) {
                    photos.update { map -> map + (ref to PhotoUi.OnKidDevice) }
                    return@launch
                }
                val bytes = if (local) {
                    attempt { photoStore.load(ref) }.getOrNull()
                } else {
                    remotePhotos.load(requireNotNull(blobId)).getOrNull()?.takeIf { it.sha256Hex() == ref.sha256 }
                }
                val image = bytes?.let { attempt { decoder.decode(it) }.getOrNull() }
                val photo = when {
                    image != null -> PhotoUi.Loaded(image)

                    // An upload that cannot be fetched or opened yet (offline, key still on its way).
                    !local -> PhotoUi.OnKidDevice

                    else -> PhotoUi.Unavailable
                }
                photos.update { map -> map + (ref to photo) }
            }
        }
    }

    @Suppress("ReturnCount") // Guard clauses: each early return is a precondition that is not met.
    private fun approve(id: SubmissionId) {
        if (local.value.busy != null) return
        val submission = snapshot.submissions.firstOrNull { it.id == id } ?: return
        val chore = snapshot.chores[submission.choreId] ?: return
        val kidName = snapshot.kidName(snapshot.kids[submission.kidId])
        local.update { it.copy(busy = id.value) }
        viewModelScope.launch {
            val outcome = attempt {
                approver.approve(id) { intent ->
                    val reason = reasons.reason(kidName, chore.title, intent.amount, intent.currency)
                    authorize(reason)
                }
            }
            val message = outcome.fold(
                onSuccess = { messageFor(it, kidName, chore) },
                onFailure = { error ->
                    if (error is AuthorizationAborted) {
                        ApprovalsMessage.AuthorizationCancelled(chore.title)
                    } else {
                        ApprovalsMessage.Failed
                    }
                },
            )
            val partly = outcome.getOrNull() is ApprovalResult.Partial
            local.update { state ->
                state.copy(
                    busy = null,
                    message = message,
                    partlyPaid = if (partly) state.partlyPaid + id else state.partlyPaid - id,
                )
            }
        }
    }

    /**
     * One prompt; its failure is wrapped so it can be told apart from a storage failure.
     * `ParentAuthUnavailableException` and `ParentKeyInvalidatedException` pass through untouched: the use case falls
     * back per D-M2-1, or reports the invalidated key.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever the prompt throws means "not authorised".
    private suspend fun authorize(reason: String): ParentAuthorization = try {
        requester.request(reason)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ParentAuthUnavailableException) {
        throw e
    } catch (e: ParentKeyInvalidatedException) {
        throw e
    } catch (e: Exception) {
        throw AuthorizationAborted(e)
    }

    private fun messageFor(result: ApprovalResult, kidName: String, chore: Chore): ApprovalsMessage = when (result) {
        is ApprovalResult.Approved -> ApprovalsMessage.Approved(
            kidName = kidName,
            choreTitle = chore.title,
            paidNow = result.rewards.count { !it.alreadyPaid },
            alreadyPaid = result.rewards.count { it.alreadyPaid },
            unsigned = result.unsigned,
        )

        ApprovalResult.NoAccount -> ApprovalsMessage.NoAccount(kidName)

        ApprovalResult.NotFound -> ApprovalsMessage.NotFound

        is ApprovalResult.RewardRefused -> ApprovalsMessage.RewardRefused(chore.title)

        is ApprovalResult.SignatureRequired -> ApprovalsMessage.SignatureRequired(chore.title)

        is ApprovalResult.KeyInvalidated -> ApprovalsMessage.KeyInvalidated(chore.title)

        is ApprovalResult.Partial -> when (result.stoppedBy) {
            is ApprovalResult.KeyInvalidated -> ApprovalsMessage.KeyInvalidated(chore.title)
            else -> ApprovalsMessage.PartlyPaid(chore.title, result.paid.size, result.paid.size + result.unpaid.size)
        }
    }

    @Suppress("ReturnCount") // Guard clauses: each early return is a precondition that is not met.
    private fun openReject(id: SubmissionId) {
        if (local.value.busy != null) return
        val submission = snapshot.submissions.firstOrNull { it.id == id } ?: return
        val title = snapshot.chores[submission.choreId]?.title ?: return
        local.update { it.copy(rejecting = RejectUi(id, title)) }
    }

    private fun confirmReject() {
        val sheet = local.value.rejecting ?: return
        if (local.value.busy != null) return
        local.update { it.copy(busy = sheet.submissionId.value) }
        viewModelScope.launch {
            val reason = sheet.reason.trim().ifEmpty { null }
            val decided = attempt { chores.decide(sheet.submissionId, SubmissionDecision.REJECT, reason) }.getOrNull()
            val message = when (decided) {
                null -> ApprovalsMessage.Failed
                is DecisionResult.Decided -> ApprovalsMessage.Rejected(sheet.choreTitle)
                is DecisionResult.AlreadyPaid -> ApprovalsMessage.AlreadyPaid(sheet.choreTitle)
            }
            local.update {
                it.copy(
                    busy = null,
                    rejecting = if (decided == null) it.rejecting else null,
                    message = message,
                    partlyPaid = if (decided is DecisionResult.AlreadyPaid) {
                        it.partlyPaid + sheet.submissionId
                    } else {
                        it.partlyPaid
                    },
                )
            }
        }
    }

    private fun decideProposal(id: ChoreId, accept: Boolean) {
        if (local.value.busy != null) return
        val chore = snapshot.chores[id]?.takeIf { it.state == ChoreState.PROPOSED } ?: return
        local.update { it.copy(busy = id.value) }
        viewModelScope.launch {
            val target = if (accept) ChoreState.ACTIVE else ChoreState.ARCHIVED
            val ok = attempt { chores.setState(id, target) }.getOrDefault(false)
            val message = when {
                !ok -> ApprovalsMessage.Failed
                accept -> ApprovalsMessage.ProposalAccepted(chore.title)
                else -> ApprovalsMessage.ProposalDeclined(chore.title)
            }
            local.update { it.copy(busy = null, message = message) }
        }
    }

    private class AuthorizationAborted(cause: Throwable) : Exception(cause)

    private data class Local(
        val busy: String? = null,
        val rejecting: RejectUi? = null,
        val message: ApprovalsMessage? = null,
        /** Submissions known to be partly paid (from an approval that stopped halfway, or a refused rejection). */
        val partlyPaid: Set<SubmissionId> = emptySet(),
    )

    private data class Snapshot(
        val submissions: List<ChoreSubmission>,
        val chores: Map<ChoreId, Chore>,
        val kids: Map<KidId, Kid>,
    ) {
        fun kidName(kid: Kid?): String = kid?.firstName ?: UNKNOWN_KID

        companion object {
            val EMPTY = Snapshot(emptyList(), emptyMap(), emptyMap())
        }
    }

    private sealed interface Loaded {
        data object Loading : Loaded

        data object Failed : Loaded

        data class Ready(val snapshot: Snapshot) : Loaded
    }

    private companion object {
        /** Shown for a kid profile that is gone (archived); the inbox still lets the parent decide. */
        const val UNKNOWN_KID = "?"
    }
}

/** Lower-case hex SHA-256 of these bytes, to check a downloaded photo against its [PhotoRef.sha256]. */
private fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

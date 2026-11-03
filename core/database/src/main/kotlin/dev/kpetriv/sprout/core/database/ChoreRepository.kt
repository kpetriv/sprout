package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.ChoreEntity
import dev.kpetriv.sprout.core.database.entity.ChoreSubmissionEntity
import dev.kpetriv.sprout.core.database.entity.DocumentJson
import dev.kpetriv.sprout.core.domainhousehold.Chore
import dev.kpetriv.sprout.core.domainhousehold.ChoreSubmissions
import dev.kpetriv.sprout.core.domainledger.EventAuthor
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.PeriodClaim
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreState
import dev.kpetriv.sprout.core.model.ChoreSubmission
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.PhotoRef
import dev.kpetriv.sprout.core.model.SubmissionId
import dev.kpetriv.sprout.core.model.SubmissionStatus
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.put
import javax.inject.Inject

/** Which submissions [ChoreRepository.observeSubmissions] returns. */
sealed interface SubmissionFilter {
    /** Every submission of [kidId], newest due date first. */
    data class ForKid(val kidId: KidId) : SubmissionFilter

    /** The parent's approvals inbox: every kid's submissions awaiting review, oldest first. */
    data object PendingForParent : SubmissionFilter
}

/** A parent's decision on a submission. */
enum class SubmissionDecision {
    APPROVE,
    REJECT,
}

/** Outcome of [ChoreRepository.decide]. */
sealed interface DecisionResult {
    /** The submission as stored after the call. */
    val submission: ChoreSubmission

    /** The decision is recorded (or was already: approving an approved submission is a no-op). */
    data class Decided(override val submission: ChoreSubmission) : DecisionResult

    /**
     * Rejecting was refused: rewards for this occurrence are already in the ledger ([paidBy], for example after a
     * partly finished approval). Nothing changed; finish the approval instead (approving again pays only what is
     * missing).
     */
    data class AlreadyPaid(override val submission: ChoreSubmission, val paidBy: List<EventId>) : DecisionResult
}

/** Which ledger events already pay a chore occurrence; [choreRewardsFor] in production. */
internal fun interface ChoreRewardLedger {
    suspend fun rewardsFor(kidId: KidId, choreId: String, dueOn: CalendarDate): List<EventId>
}

/**
 * The ledger events that already pay the chore occurrence ([kidId], [choreId], [dueOn]) in any currency: the holders
 * of its [PeriodClaim.Chore] claims. Empty when nothing of it is paid. Call it outside a transaction.
 */
internal suspend fun RoomLedgerRepository.choreRewardsFor(kidId: KidId, choreId: String, dueOn: CalendarDate) =
    projection().claimedPeriods.mapNotNull { (claim, eventId) ->
        eventId.takeIf {
            claim is PeriodClaim.Chore && claim.kidId == kidId && claim.choreId == choreId && claim.dueOn == dueOn
        }
    }

/**
 * Chores and their submissions. Writing a submission's decision does not pay the reward: use
 * `ApproveSubmissionUseCase`, which pays (parent-signed) and then records the approval.
 */
interface ChoreRepository {
    /** Chores of [kidId] (every kid when null), oldest first; archived ones only with [includeArchived]. */
    fun observeChores(kidId: KidId? = null, includeArchived: Boolean = false): Flow<List<Chore>>

    suspend fun get(id: ChoreId): Chore?

    /** Inserts or replaces [chore]. */
    suspend fun upsert(chore: Chore)

    /**
     * Moves the chore to [state] (accept or decline a proposal, archive). Archiving deletes the photos of every
     * submission of the chore (audit L2). False if there is no such chore.
     */
    suspend fun setState(id: ChoreId, state: ChoreState): Boolean

    fun observeSubmissions(filter: SubmissionFilter): Flow<List<ChoreSubmission>>

    suspend fun getSubmission(id: SubmissionId): ChoreSubmission?

    /**
     * The kid marks the occurrence of [choreId] due on [dueOn] done ([ChoreSubmissions.submit]: the chore must be
     * active and due that day). Idempotent per occurrence: the submission id is deterministic, a submission still
     * awaiting review is replaced (latest note and photo), and a decided one is returned unchanged. [photo] must have
     * been saved through `PhotoStore` for the same kid; it is attached to the submission.
     *
     * Throws [IllegalArgumentException] for an unknown chore, an inactive one, a date it is not due on, a note over
     * the length limit, or a photo this device did not store for the chore's kid.
     */
    suspend fun submit(
        choreId: ChoreId,
        dueOn: CalendarDate,
        note: String? = null,
        photo: PhotoRef? = null,
    ): ChoreSubmission

    /**
     * Records a parent's [decision] on submission [id] (with an optional rejection [reason]) and deletes the
     * submission's photos, which are only needed until it is decided (audit L2). Approving an approved submission is a
     * no-op. Rejecting is refused with [DecisionResult.AlreadyPaid] while any reward of the occurrence is in the ledger
     * (R4); rejecting an approved one otherwise throws [IllegalStateException]. Throws [IllegalArgumentException] for
     * an unknown submission.
     */
    suspend fun decide(id: SubmissionId, decision: SubmissionDecision, reason: String? = null): DecisionResult
}

/**
 * [ChoreRepository] on Room: each write, its outbox `DOCUMENT` row and its audit row in one transaction. Photos are
 * deleted through [PhotoStore] after the transaction that made them unnecessary.
 */
class RoomChoreRepository internal constructor(
    private val database: SproutDatabase,
    private val authors: SessionAuthorProvider,
    private val clock: Clock,
    private val rewardLedger: ChoreRewardLedger,
    private val photoStore: PhotoStore,
) : ChoreRepository {
    @Inject
    constructor(
        database: SproutDatabase,
        authors: SessionAuthorProvider,
        clock: Clock,
        ledger: RoomLedgerRepository,
        photoStore: PhotoStore,
    ) : this(database, authors, clock, ChoreRewardLedger(ledger::choreRewardsFor), photoStore)

    private val chores = database.choreDao()
    private val submissions = database.choreSubmissionDao()
    private val photos = database.photoDao()
    private val writes = DocumentWrites(database, clock)

    override fun observeChores(kidId: KidId?, includeArchived: Boolean): Flow<List<Chore>> =
        chores.observe(kidId, includeArchived).map { rows -> rows.map(ChoreEntity::toDomain) }

    override suspend fun get(id: ChoreId): Chore? = chores.get(id)?.toDomain()

    override suspend fun upsert(chore: Chore) {
        val author = authors.currentAuthor()
        writes.transaction {
            val lamport = writes.nextLamport(author.deviceId)
            chores.upsert(ChoreEntity.from(chore, lamport))
            recordChore(author, chore, lamport, AuditAction.CHORE_UPSERTED)
        }
    }

    override suspend fun setState(id: ChoreId, state: ChoreState): Boolean {
        val author = authors.currentAuthor()
        val found = writes.transaction {
            val chore = chores.get(id)?.toDomain() ?: return@transaction false
            if (chore.state != state) {
                val lamport = writes.nextLamport(author.deviceId)
                chores.setState(id, state, lamport)
                recordChore(author, chore.copy(state = state), lamport, AuditAction.CHORE_STATE_CHANGED)
            }
            true
        }
        if (found && state == ChoreState.ARCHIVED) {
            submissions.forChore(id).forEach { deletePhotosOf(it.submissionId) }
        }
        return found
    }

    override fun observeSubmissions(filter: SubmissionFilter): Flow<List<ChoreSubmission>> = when (filter) {
        is SubmissionFilter.ForKid -> submissions.observeForKid(filter.kidId)
        SubmissionFilter.PendingForParent -> submissions.observeWithStatus(SubmissionStatus.SUBMITTED)
    }.map { rows -> rows.map(ChoreSubmissionEntity::toDomain) }

    override suspend fun getSubmission(id: SubmissionId): ChoreSubmission? = submissions.get(id)?.toDomain()

    override suspend fun submit(
        choreId: ChoreId,
        dueOn: CalendarDate,
        note: String?,
        photo: PhotoRef?,
    ): ChoreSubmission {
        val author = authors.currentAuthor()
        return writes.transaction {
            val chore = requireNotNull(chores.get(choreId)?.toDomain()) { "unknown chore" }
            val submitted = ChoreSubmissions.submit(chore, dueOn, clock.now(), note?.takeIf { it.isNotBlank() }, photo)
            val existing = submissions.get(submitted.id)?.toDomain()
            if (existing != null && existing.status != SubmissionStatus.SUBMITTED) return@transaction existing
            if (photo != null) {
                val stored = photos.get(photo.fileName)
                require(stored != null && stored.sha256 == photo.sha256 && stored.kidId == chore.kidId) {
                    "the photo is not stored on this device for this kid"
                }
                photos.attach(photo.fileName, submitted.id)
            }
            writeSubmission(author, submitted, AuditAction.SUBMISSION_SUBMITTED)
        }
    }

    override suspend fun decide(id: SubmissionId, decision: SubmissionDecision, reason: String?): DecisionResult {
        val author = authors.currentAuthor()
        if (decision == SubmissionDecision.REJECT) {
            val current = requireNotNull(submissions.get(id)?.toDomain()) { "unknown submission" }
            val paidBy = rewardLedger.rewardsFor(current.kidId, current.choreId.value, current.dueOn)
            if (paidBy.isNotEmpty()) return DecisionResult.AlreadyPaid(current, paidBy)
        }
        val decided = writes.transaction {
            val current = requireNotNull(submissions.get(id)?.toDomain()) { "unknown submission" }
            val decided = when (decision) {
                SubmissionDecision.APPROVE -> ChoreSubmissions.approve(current, clock.now(), author.uid)

                SubmissionDecision.REJECT ->
                    ChoreSubmissions.reject(current, clock.now(), author.uid, reason?.takeIf { it.isNotBlank() })
            }
            if (decided == current) current else writeSubmission(author, decided, AuditAction.SUBMISSION_DECIDED)
        }
        deletePhotosOf(id)
        return DecisionResult.Decided(decided)
    }

    /** Deletes every photo attached to [submissionId] (the current one and any it replaced). */
    private suspend fun deletePhotosOf(submissionId: SubmissionId) {
        photos.forSubmission(submissionId).forEach { photoStore.delete(PhotoRef(it.fileName, it.sha256)) }
    }

    private suspend fun writeSubmission(
        author: EventAuthor,
        submission: ChoreSubmission,
        action: AuditAction,
    ): ChoreSubmission {
        val lamport = writes.nextLamport(author.deviceId)
        submissions.upsert(ChoreSubmissionEntity.from(submission, lamport))
        writes.record(
            author,
            DocTypes.CHORE_SUBMISSION,
            submission.id.value,
            lamport,
            DocumentJson.json.encodeToJsonElement(ChoreSubmission.serializer(), submission),
            action,
        ) {
            put("choreId", submission.choreId.value)
            put("kidId", submission.kidId.value)
            put("dueOn", submission.dueOn.toString())
            put("status", submission.status.name)
            put("hasPhoto", submission.photoRef != null)
        }
        return submission
    }

    private suspend fun recordChore(author: EventAuthor, chore: Chore, lamport: Long, action: AuditAction) =
        writes.record(
            author,
            DocTypes.CHORE,
            chore.id.value,
            lamport,
            DocumentJson.json.encodeToJsonElement(Chore.serializer(), chore),
            action,
        ) {
            put("kidId", chore.kidId.value)
            put("state", chore.state.name)
            chore.reward.money?.let { put("rewardMoneyMinor", it.minorUnits) }
            chore.reward.minutes?.let { put("rewardMinutes", it.minorUnits) }
        }
}

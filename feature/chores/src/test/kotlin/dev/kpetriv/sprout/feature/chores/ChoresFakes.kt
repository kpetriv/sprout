package dev.kpetriv.sprout.feature.chores

import dev.kpetriv.sprout.core.database.ChoreRepository
import dev.kpetriv.sprout.core.database.DecisionResult
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.database.PhotoIntegrityException
import dev.kpetriv.sprout.core.database.PhotoStore
import dev.kpetriv.sprout.core.database.SubmissionDecision
import dev.kpetriv.sprout.core.database.SubmissionFilter
import dev.kpetriv.sprout.core.database.usecase.ApprovalResult
import dev.kpetriv.sprout.core.database.usecase.RewardOutcome
import dev.kpetriv.sprout.core.domainhousehold.Chore
import dev.kpetriv.sprout.core.domainhousehold.ChoreReward
import dev.kpetriv.sprout.core.domainhousehold.ChoreRewardIntent
import dev.kpetriv.sprout.core.domainhousehold.ChoreSubmissions
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.ParentAuthorization
import dev.kpetriv.sprout.core.domainledger.ParentAuthorizationRequester
import dev.kpetriv.sprout.core.model.AgeBand
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.ChoreState
import dev.kpetriv.sprout.core.model.ChoreSubmission
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.PhotoRef
import dev.kpetriv.sprout.core.model.SubmissionId
import dev.kpetriv.sprout.core.model.SubmissionStatus
import dev.kpetriv.sprout.core.model.YearMonth
import dev.kpetriv.sprout.core.time.Clock
import dev.kpetriv.sprout.core.time.FakeClock
import dev.kpetriv.sprout.core.time.HouseholdCalendar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.time.ZoneOffset
import kotlin.time.Instant

/** Replaces Dispatchers.Main (used by viewModelScope) with a test dispatcher. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}

object TestData {
    val gbp = Currency.Fiat("GBP")
    val household = HouseholdId("household-1")

    /** Wednesday 4 November 2026, 09:00 UTC. */
    val today = CalendarDate(2026, 11, 4)
    val now: Instant = Instant.parse("2026-11-04T09:00:00Z")
    fun clock() = FakeClock(now)

    /** "Today" in UTC from [clock]. */
    fun calendar(clock: Clock = clock()) = HouseholdCalendar(clock) { ZoneOffset.UTC }

    fun kid(id: String, name: String, tier: KidUiTier = KidUiTier.SPROUT) =
        Kid(KidId(id), household, name, "fox", YearMonth(2016, 4), tier)

    fun chore(
        id: String,
        kid: String,
        title: String,
        money: Long? = 250,
        minutes: Long? = null,
        schedule: ChoreSchedule = ChoreSchedule.Daily.EVERY_DAY,
        state: ChoreState = ChoreState.ACTIVE,
        createdAt: Instant = Instant.parse("2026-11-01T00:00:00Z"),
    ) = Chore(
        id = ChoreId(id),
        householdId = household,
        kidId = KidId(kid),
        title = title,
        reward = ChoreReward(money?.let(::Money), minutes?.let(::Money)),
        schedule = schedule,
        createdAt = createdAt,
        state = state,
    )

    fun submission(chore: Chore, dueOn: CalendarDate = today, submittedAt: Instant = now, note: String? = null) =
        ChoreSubmissions.submit(chore, dueOn, submittedAt, note)
}

class FakeKidRepository(kids: List<Kid> = emptyList()) : KidRepository {
    val kids = MutableStateFlow(kids)

    override fun observeKids(): Flow<List<Kid>> = kids

    override suspend fun getKid(id: KidId): Kid? = kids.value.firstOrNull { it.id == id }

    override suspend fun upsert(kid: Kid) = kids.update { list -> list.filterNot { it.id == kid.id } + kid }

    override suspend fun archive(id: KidId): Boolean = false
}

class FakeChoreRepository(private val clock: FakeClock = TestData.clock()) : ChoreRepository {
    val chores = MutableStateFlow<List<Chore>>(emptyList())
    val submissions = MutableStateFlow<List<ChoreSubmission>>(emptyList())
    val submitCalls = mutableListOf<Triple<ChoreId, CalendarDate, String?>>()
    val submittedPhotos = mutableListOf<PhotoRef?>()
    var failWrites = false

    /** When set, [submit] suspends until it completes (to test taps while a submit is in flight). */
    var submitGate: CompletableDeferred<Unit>? = null

    override fun observeChores(kidId: KidId?, includeArchived: Boolean): Flow<List<Chore>> = chores.map { list ->
        list.filter { (kidId == null || it.kidId == kidId) && (includeArchived || it.state != ChoreState.ARCHIVED) }
    }

    override suspend fun get(id: ChoreId): Chore? = chores.value.firstOrNull { it.id == id }

    override suspend fun upsert(chore: Chore) {
        check(!failWrites) { "write failed" }
        chores.update { list -> list.filterNot { it.id == chore.id } + chore }
    }

    override suspend fun setState(id: ChoreId, state: ChoreState): Boolean {
        check(!failWrites) { "write failed" }
        val chore = get(id) ?: return false
        upsert(chore.copy(state = state))
        return true
    }

    override fun observeSubmissions(filter: SubmissionFilter): Flow<List<ChoreSubmission>> = submissions.map { list ->
        when (filter) {
            is SubmissionFilter.ForKid -> list.filter { it.kidId == filter.kidId }
            SubmissionFilter.PendingForParent -> list.filter { it.status == SubmissionStatus.SUBMITTED }
        }
    }

    override suspend fun getSubmission(id: SubmissionId): ChoreSubmission? = submissions.value.firstOrNull {
        it.id == id
    }

    override suspend fun submit(
        choreId: ChoreId,
        dueOn: CalendarDate,
        note: String?,
        photo: PhotoRef?,
    ): ChoreSubmission {
        submitCalls += Triple(choreId, dueOn, note)
        submittedPhotos += photo
        submitGate?.await()
        check(!failWrites) { "write failed" }
        val chore = requireNotNull(get(choreId))
        val submitted = ChoreSubmissions.submit(chore, dueOn, clock.now(), note, photo)
        submissions.update { list -> list.filterNot { it.id == submitted.id } + submitted }
        return submitted
    }

    /** Submissions whose rewards are partly in the ledger: rejecting them is refused (R4). */
    val partlyPaid = mutableSetOf<SubmissionId>()

    override suspend fun decide(id: SubmissionId, decision: SubmissionDecision, reason: String?): DecisionResult {
        check(!failWrites) { "write failed" }
        val current = requireNotNull(getSubmission(id))
        if (decision == SubmissionDecision.REJECT && id in partlyPaid) {
            return DecisionResult.AlreadyPaid(current, listOf(EventId("00000000-0000-4000-8000-0000000000aa")))
        }
        val decided = when (decision) {
            SubmissionDecision.APPROVE -> ChoreSubmissions.approve(current, clock.now(), null)
            SubmissionDecision.REJECT -> ChoreSubmissions.reject(current, clock.now(), null, reason)
        }
        submissions.update { list -> list.map { if (it.id == id) decided else it } }
        return DecisionResult.Decided(decided)
    }
}

class FakePhotoStore : PhotoStore {
    val stored = mutableMapOf<PhotoRef, ByteArray>()
    val deleted = mutableListOf<PhotoRef>()

    override suspend fun save(kidId: KidId, bytes: ByteArray): PhotoRef {
        val ref = PhotoRef("photo-${stored.size}.photo", "a".repeat(64))
        stored[ref] = bytes
        return ref
    }

    override suspend fun load(ref: PhotoRef): ByteArray = stored[ref] ?: throw PhotoIntegrityException("unknown photo")

    override suspend fun delete(ref: PhotoRef): Boolean {
        deleted += ref
        return stored.remove(ref) != null
    }
}

/** Records every prompt; [cancel] makes the next prompt throw like a dismissed biometric prompt. */
class FakeRequester : ParentAuthorizationRequester {
    val reasons = mutableListOf<String>()
    var cancel = false

    override suspend fun request(reason: String): ParentAuthorization {
        reasons += reason
        if (cancel) error("cancelled")
        return ParentAuthorization { ByteArray(1) }
    }
}

/**
 * Mimics `ApproveSubmissionUseCase`: one intent per reward currency; intents in [alreadyPaid] are reported without
 * calling `authorize`, the rest call it once each, then the submission is approved.
 */
internal class FakeApprover(private val chores: FakeChoreRepository) : SubmissionApprover {
    var alreadyPaid: Set<Currency> = emptySet()
    var result: ApprovalResult? = null

    @Suppress("ReturnCount") // Mirrors the use case's early returns.
    override suspend fun approve(
        submissionId: SubmissionId,
        authorize: suspend (ChoreRewardIntent) -> ParentAuthorization,
    ): ApprovalResult {
        result?.let { return it }
        val submission = chores.getSubmission(submissionId) ?: return ApprovalResult.NotFound
        val chore = chores.get(submission.choreId) ?: return ApprovalResult.NotFound
        val approving = ChoreSubmissions.approve(submission, TestData.now, null)
        val outcomes = ChoreSubmissions.rewardIntents(chore, approving, TestData.gbp).mapIndexed { index, intent ->
            val paid = intent.currency in alreadyPaid
            if (!paid) authorize(intent)
            RewardOutcome(intent, EventId("00000000-0000-4000-8000-00000000000$index"), alreadyPaid = paid)
        }
        return ApprovalResult.Approved(chores.decide(submissionId, SubmissionDecision.APPROVE).submission, outcomes)
    }
}

/** "Pay Leo £2.50 for Make bed" without Android resources. */
internal val TestReasons = ApprovalReasonText { kid, chore, amount, currency ->
    "Pay $kid ${dev.kpetriv.sprout.core.ui.MoneyFormatter(java.util.Locale.UK).format(amount, currency)} for $chore"
}

/** An [androidx.compose.ui.graphics.ImageBitmap] that needs no Android graphics, for plain JVM tests. */
class FakeImageBitmap : androidx.compose.ui.graphics.ImageBitmap {
    override val width: Int = 1
    override val height: Int = 1
    override val config: androidx.compose.ui.graphics.ImageBitmapConfig =
        androidx.compose.ui.graphics.ImageBitmapConfig.Argb8888
    override val hasAlpha: Boolean = false
    override val colorSpace: androidx.compose.ui.graphics.colorspace.ColorSpace =
        androidx.compose.ui.graphics.colorspace.ColorSpaces.Srgb

    override fun prepareToDraw() = Unit

    override fun readPixels(
        buffer: IntArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int,
        bufferOffset: Int,
        stride: Int,
    ) = Unit
}

class FakeChoreSuggestions : ChoreSuggestions {
    var result: List<ChoreSuggestionUi> = ChoresPreviewData.suggestions
    var failure: Throwable? = null

    /** How long each call takes (virtual time). */
    var delayMillis: Long = 0
    val calls = mutableListOf<Triple<KidId, AgeBand, List<String>>>()

    override suspend fun suggest(kidId: KidId, band: AgeBand, existing: List<String>): List<ChoreSuggestionUi> {
        calls += Triple(kidId, band, existing)
        if (delayMillis > 0) delay(delayMillis)
        failure?.let { throw it }
        return result
    }
}

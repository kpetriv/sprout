package dev.kpetriv.sprout.feature.chores

import androidx.lifecycle.SavedStateHandle
import dev.kpetriv.sprout.core.database.usecase.ApprovalResult
import dev.kpetriv.sprout.core.database.usecase.RewardOutcome
import dev.kpetriv.sprout.core.domainhousehold.ChoreSubmissions
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.ChoreState
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.SubmissionStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Keeps a WhileSubscribed state flow hot for the rest of the test. */
fun <T> TestScope.subscribe(state: StateFlow<T>): StateFlow<T> {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect { } }
    return state
}

class ChoreListViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val kids = FakeKidRepository(listOf(TestData.kid("kid-leo", "Leo"), TestData.kid("kid-mia", "Mia")))
    private val chores = FakeChoreRepository()

    private fun viewModel() = ChoreListViewModel(chores, kids) { TestData.gbp }

    @Test
    fun `groups chores by kid in the kids' order, active before proposed`() = runTest {
        chores.chores.value = listOf(
            TestData.chore("c1", "kid-mia", "Tidy toys"),
            TestData.chore("c2", "kid-leo", "Wash car", state = ChoreState.PROPOSED),
            TestData.chore("c3", "kid-leo", "Make bed"),
            TestData.chore("c4", "kid-gone", "Orphan"),
        )
        val state = assertIs<ChoreListUiState.Ready>(subscribe(viewModel().state).value)

        assertEquals(listOf("Leo", "Mia"), state.groups.map { it.kidName })
        assertEquals(listOf("Make bed", "Wash car"), state.groups[0].chores.map { it.title })
        assertEquals(listOf(ChoreState.ACTIVE, ChoreState.PROPOSED), state.groups[0].chores.map { it.state })
        assertEquals(listOf("Tidy toys"), state.groups[1].chores.map { it.title })
    }

    @Test
    fun `archive hides the chore until archived ones are shown`() = runTest {
        chores.chores.value = listOf(TestData.chore("c1", "kid-leo", "Make bed"))
        val vm = viewModel()
        val state = subscribe(vm.state)

        vm.onEvent(ChoreListEvent.Archive(ChoreId("c1")))
        val afterArchive = assertIs<ChoreListUiState.Ready>(state.value)
        assertTrue(afterArchive.groups[0].chores.isEmpty())
        assertEquals(ChoreListMessage.Archived, afterArchive.message)

        vm.onEvent(ChoreListEvent.ToggleArchived)
        val shown = assertIs<ChoreListUiState.Ready>(state.value)
        assertEquals(ChoreState.ARCHIVED, shown.groups[0].chores.single().state)
    }

    @Test
    fun `counts pending approvals`() = runTest {
        val chore = TestData.chore("c1", "kid-leo", "Make bed")
        chores.chores.value = listOf(chore)
        chores.submissions.value = listOf(TestData.submission(chore))
        val state = assertIs<ChoreListUiState.Ready>(subscribe(viewModel().state).value)
        assertEquals(1, state.pendingApprovals)
    }
}

class ChoreEditorViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val kids = FakeKidRepository(listOf(TestData.kid("kid-leo", "Leo"), TestData.kid("kid-mia", "Mia")))
    private val chores = FakeChoreRepository()

    private fun viewModel(choreId: String? = null, kidId: String? = null) = ChoreEditorViewModel(
        savedStateHandle = SavedStateHandle(
            buildMap {
                choreId?.let { put(ChoreEditorViewModel.CHORE_ID_KEY, it) }
                kidId?.let { put(ChoreEditorViewModel.KID_ID_KEY, it) }
            },
        ),
        chores = chores,
        kids = kids,
        fiat = { TestData.gbp },
        clock = TestData.clock(),
        calendar = TestData.calendar(),
    )

    @Test
    fun `no errors before the first save, then every problem`() = runTest {
        val vm = viewModel()
        assertTrue(vm.state.value.errors.isEmpty())
        vm.onEvent(ChoreEditorEvent.MoneyChanged("2.5.0"))
        vm.onEvent(ChoreEditorEvent.ScheduleKindChanged(ScheduleKind.DAILY))
        DayOfWeek.entries.forEach { vm.onEvent(ChoreEditorEvent.DailyDayToggled(it)) }
        assertTrue(vm.state.value.errors.isEmpty())

        vm.onEvent(ChoreEditorEvent.Save)

        assertEquals(
            setOf(EditorError.NO_KID, EditorError.TITLE_BLANK, EditorError.MONEY_INVALID, EditorError.NO_DAYS),
            vm.state.value.errors,
        )
        assertTrue(chores.chores.value.isEmpty())
    }

    @Test
    fun `errors follow edits after a failed save`() = runTest {
        val vm = viewModel(kidId = "kid-leo")
        vm.onEvent(ChoreEditorEvent.Save)
        assertTrue(EditorError.NO_REWARD in vm.state.value.errors)

        vm.onEvent(ChoreEditorEvent.MinutesChanged("0"))
        assertEquals(setOf(EditorError.TITLE_BLANK, EditorError.REWARD_NOT_POSITIVE), vm.state.value.errors)

        vm.onEvent(ChoreEditorEvent.ScheduleKindChanged(ScheduleKind.ONCE))
        vm.onEvent(ChoreEditorEvent.OnceDateChanged(CalendarDate(2026, 11, 3)))
        assertTrue(EditorError.DUE_DATE_IN_PAST in vm.state.value.errors)
    }

    @Test
    fun `saves a new chore with parsed amounts and the schedule`() = runTest {
        val vm = viewModel(kidId = "kid-leo")
        vm.onEvent(ChoreEditorEvent.TitleChanged("  Make bed "))
        vm.onEvent(ChoreEditorEvent.MoneyChanged("2.50"))
        vm.onEvent(ChoreEditorEvent.MinutesChanged("15"))
        vm.onEvent(ChoreEditorEvent.ScheduleKindChanged(ScheduleKind.MONTHLY))
        vm.onEvent(ChoreEditorEvent.MonthlyDayChanged(40))
        vm.onEvent(ChoreEditorEvent.Save)

        val saved = chores.chores.value.single()
        assertEquals("Make bed", saved.title)
        assertEquals(KidId("kid-leo"), saved.kidId)
        assertEquals(Money(250), saved.reward.money)
        assertEquals(Money(15), saved.reward.minutes)
        assertEquals(ChoreSchedule.Monthly(28), saved.schedule)
        assertEquals(ChoreState.ACTIVE, saved.state)
        assertTrue(vm.state.value.saved)
    }

    @Test
    fun `editing keeps id, kid and state`() = runTest {
        val existing = TestData.chore("c1", "kid-mia", "Tidy toys", state = ChoreState.PROPOSED)
        chores.chores.value = listOf(existing)
        val vm = viewModel(choreId = "c1")
        assertEquals("2.50", vm.state.value.moneyText)
        vm.onEvent(ChoreEditorEvent.KidChanged(KidId("kid-leo")))
        vm.onEvent(ChoreEditorEvent.TitleChanged("Tidy all toys"))
        vm.onEvent(ChoreEditorEvent.Save)

        val saved = chores.chores.value.single()
        assertEquals(existing.copy(title = "Tidy all toys"), saved)
    }

    @Test
    fun `a failed save is reported`() = runTest {
        chores.failWrites = true
        val vm = viewModel(kidId = "kid-leo")
        vm.onEvent(ChoreEditorEvent.TitleChanged("Make bed"))
        vm.onEvent(ChoreEditorEvent.MoneyChanged("1"))
        vm.onEvent(ChoreEditorEvent.Save)
        assertTrue(vm.state.value.saveFailed)
        assertEquals(false, vm.state.value.saved)
    }

    @Test
    fun `an unknown chore is not found`() = runTest {
        assertTrue(viewModel(choreId = "missing").state.value.notFound)
    }
}

class ApprovalsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val kids = FakeKidRepository(listOf(TestData.kid("kid-leo", "Leo")))
    private val chores = FakeChoreRepository()
    private val photos = FakePhotoStore()
    private val approver = FakeApprover(chores)
    private val requester = FakeRequester()
    private val decoded = FakeImageBitmap()
    private val uploaded = mutableMapOf<String, ByteArray>()
    private val fetched = mutableListOf<String>()

    private fun viewModel() = ApprovalsViewModel(
        chores = chores,
        kids = kids,
        photoStore = photos,
        localPhotos = { ref -> ref in photos.stored },
        approver = approver,
        requester = requester,
        reasons = TestReasons,
        decoder = { decoded },
        fiat = { TestData.gbp },
        remotePhotos = { blobId ->
            fetched += blobId
            uploaded[blobId]?.let { Result.success(it) } ?: Result.failure(IllegalStateException("not found"))
        },
    )

    private val bed = TestData.chore("bed", "kid-leo", "Make bed", money = 250, minutes = 15)

    @Test
    fun `lists pending submissions newest first with kid name and decoded photo`() = runTest {
        val dishes = TestData.chore("dishes", "kid-leo", "Dishes")
        val photo = photos.save(KidId("kid-leo"), byteArrayOf(1, 2, 3))
        chores.chores.value = listOf(bed, dishes)
        chores.submissions.value = listOf(
            TestData.submission(bed, submittedAt = TestData.now).copy(photoRef = photo),
            TestData.submission(dishes, submittedAt = TestData.now.plus(kotlin.time.Duration.parse("1h"))),
        )
        val state = assertIs<ApprovalsUiState.Ready>(subscribe(viewModel().state).value)

        assertEquals(listOf("Dishes", "Make bed"), state.submissions.map { it.choreTitle })
        assertEquals("Leo", state.submissions[1].kidName)
        assertEquals(PhotoUi.Loaded(decoded), state.submissions[1].photo)
        assertNull(state.submissions[0].photo)
    }

    @Test
    fun `a photo taken on the kid's own phone is labelled as such instead of loaded (D-M3-9)`() = runTest {
        chores.chores.value = listOf(bed)
        val remote = dev.kpetriv.sprout.core.model.PhotoRef("synced.photo", "b".repeat(64))
        chores.submissions.value = listOf(TestData.submission(bed).copy(photoRef = remote))
        val state = assertIs<ApprovalsUiState.Ready>(subscribe(viewModel().state).value)
        assertEquals(PhotoUi.OnKidDevice, state.submissions.single().photo)
    }

    @Test
    fun `a photo the kid's phone uploaded is fetched, checked against its hash and shown (M4)`() = runTest {
        chores.chores.value = listOf(bed)
        val bytes = byteArrayOf(9, 8, 7)
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        uploaded["blob-1"] = bytes
        val remote = dev.kpetriv.sprout.core.model.PhotoRef("synced.photo", sha, blobId = "blob-1")
        chores.submissions.value = listOf(TestData.submission(bed).copy(photoRef = remote))
        val state = assertIs<ApprovalsUiState.Ready>(subscribe(viewModel().state).value)
        assertEquals(PhotoUi.Loaded(decoded), state.submissions.single().photo)
        assertEquals(listOf("blob-1"), fetched)
    }

    @Test
    fun `an uploaded photo that does not match its hash or cannot be fetched keeps the fallback`() = runTest {
        val dishes = TestData.chore("dishes", "kid-leo", "Dishes")
        chores.chores.value = listOf(bed, dishes)
        uploaded["tampered"] = byteArrayOf(1)
        chores.submissions.value = listOf(
            TestData.submission(bed).copy(
                photoRef = dev.kpetriv.sprout.core.model.PhotoRef("a.photo", "c".repeat(64), blobId = "tampered"),
            ),
            TestData.submission(dishes).copy(
                photoRef = dev.kpetriv.sprout.core.model.PhotoRef("b.photo", "d".repeat(64), blobId = "missing"),
            ),
        )
        val state = assertIs<ApprovalsUiState.Ready>(subscribe(viewModel().state).value)
        assertEquals(listOf(PhotoUi.OnKidDevice, PhotoUi.OnKidDevice), state.submissions.map { it.photo })
    }

    @Test
    fun `approve asks for one authorisation per currency`() = runTest {
        chores.chores.value = listOf(bed)
        val submission = TestData.submission(bed)
        chores.submissions.value = listOf(submission)
        val vm = viewModel()
        val state = subscribe(vm.state)

        vm.onEvent(ApprovalsEvent.Approve(submission.id))

        assertEquals(listOf("Pay Leo £2.50 for Make bed", "Pay Leo 15 min for Make bed"), requester.reasons)
        val ready = assertIs<ApprovalsUiState.Ready>(state.value)
        assertEquals(ApprovalsMessage.Approved("Leo", "Make bed", paidNow = 2, alreadyPaid = 0), ready.message)
        assertTrue(ready.submissions.isEmpty())
    }

    @Test
    fun `already paid rewards are reported and not authorised again`() = runTest {
        chores.chores.value = listOf(bed)
        val submission = TestData.submission(bed)
        chores.submissions.value = listOf(submission)
        approver.alreadyPaid = setOf(TestData.gbp)
        val vm = viewModel()
        val state = subscribe(vm.state)

        vm.onEvent(ApprovalsEvent.Approve(submission.id))

        assertEquals(listOf("Pay Leo 15 min for Make bed"), requester.reasons)
        val ready = assertIs<ApprovalsUiState.Ready>(state.value)
        assertEquals(ApprovalsMessage.Approved("Leo", "Make bed", paidNow = 1, alreadyPaid = 1), ready.message)
    }

    @Test
    fun `a cancelled prompt leaves the submission waiting`() = runTest {
        chores.chores.value = listOf(bed)
        val submission = TestData.submission(bed)
        chores.submissions.value = listOf(submission)
        requester.cancel = true
        val vm = viewModel()
        val state = subscribe(vm.state)

        vm.onEvent(ApprovalsEvent.Approve(submission.id))

        assertEquals(1, requester.reasons.size)
        val ready = assertIs<ApprovalsUiState.Ready>(state.value)
        assertEquals(ApprovalsMessage.AuthorizationCancelled("Make bed"), ready.message)
        assertEquals(1, ready.submissions.size)
        assertNull(ready.busy)
    }

    @Test
    fun `reject records the trimmed reason`() = runTest {
        chores.chores.value = listOf(bed)
        val submission = TestData.submission(bed)
        chores.submissions.value = listOf(submission)
        val vm = viewModel()
        val state = subscribe(vm.state)

        vm.onEvent(ApprovalsEvent.OpenReject(submission.id))
        vm.onEvent(ApprovalsEvent.RejectReasonChanged("  Still messy "))
        vm.onEvent(ApprovalsEvent.ConfirmReject)

        val decided = chores.submissions.value.single()
        assertEquals("Still messy", decided.rejectionReason)
        val ready = assertIs<ApprovalsUiState.Ready>(state.value)
        assertNull(ready.rejecting)
        assertEquals(ApprovalsMessage.Rejected("Make bed"), ready.message)
        assertTrue(requester.reasons.isEmpty())
    }

    @Test
    fun `an approval stopped halfway offers to finish it`() = runTest {
        chores.chores.value = listOf(bed)
        val submission = TestData.submission(bed)
        chores.submissions.value = listOf(submission)
        val approving = ChoreSubmissions.approve(submission, TestData.now, null)
        val (money, minutes) = ChoreSubmissions.rewardIntents(bed, approving, TestData.gbp)
        val paid = RewardOutcome(money, EventId("00000000-0000-4000-8000-000000000001"), alreadyPaid = false)
        approver.result = ApprovalResult.Partial(
            listOf(paid),
            listOf(minutes),
            ApprovalResult.RewardRefused(minutes, AppendResult.Rejected("invariant violated")),
        )
        val vm = viewModel()
        val state = subscribe(vm.state)

        vm.onEvent(ApprovalsEvent.Approve(submission.id))

        val ready = assertIs<ApprovalsUiState.Ready>(state.value)
        assertEquals(ApprovalsMessage.PartlyPaid("Make bed", paid = 1, total = 2), ready.message)
        assertTrue(ready.submissions.single().partlyPaid)

        // Once the approval is finished the flag goes away with the submission.
        approver.result = null
        vm.onEvent(ApprovalsEvent.Approve(submission.id))
        assertTrue(assertIs<ApprovalsUiState.Ready>(state.value).submissions.isEmpty())
    }

    @Test
    fun `an invalidated parent key is reported, not shown as a cancelled prompt`() = runTest {
        chores.chores.value = listOf(bed)
        val submission = TestData.submission(bed)
        chores.submissions.value = listOf(submission)
        val approving = ChoreSubmissions.approve(submission, TestData.now, null)
        approver.result = ApprovalResult.KeyInvalidated(ChoreSubmissions.rewardIntents(bed, approving, TestData.gbp)[0])
        val vm = viewModel()
        val state = subscribe(vm.state)

        vm.onEvent(ApprovalsEvent.Approve(submission.id))

        val ready = assertIs<ApprovalsUiState.Ready>(state.value)
        assertEquals(ApprovalsMessage.KeyInvalidated("Make bed"), ready.message)
        assertEquals(1, ready.submissions.size)
    }

    @Test
    fun `rejecting a partly paid submission is refused`() = runTest {
        chores.chores.value = listOf(bed)
        val submission = TestData.submission(bed)
        chores.submissions.value = listOf(submission)
        chores.partlyPaid += submission.id
        val vm = viewModel()
        val state = subscribe(vm.state)

        vm.onEvent(ApprovalsEvent.OpenReject(submission.id))
        vm.onEvent(ApprovalsEvent.ConfirmReject)

        val ready = assertIs<ApprovalsUiState.Ready>(state.value)
        assertEquals(ApprovalsMessage.AlreadyPaid("Make bed"), ready.message)
        assertNull(ready.rejecting)
        assertTrue(ready.submissions.single().partlyPaid)
        assertEquals(SubmissionStatus.SUBMITTED, chores.submissions.value.single().status)
    }

    @Test
    fun `proposals can be accepted or declined`() = runTest {
        val idea = TestData.chore("idea", "kid-leo", "Walk the dog", state = ChoreState.PROPOSED)
        val other = TestData.chore("other", "kid-leo", "Wash car", state = ChoreState.PROPOSED)
        chores.chores.value = listOf(idea, other)
        val vm = viewModel()
        val state = subscribe(vm.state)
        assertEquals(2, assertIs<ApprovalsUiState.Ready>(state.value).proposals.size)

        vm.onEvent(ApprovalsEvent.AcceptProposal(idea.id))
        vm.onEvent(ApprovalsEvent.DeclineProposal(other.id))

        assertEquals(ChoreState.ACTIVE, chores.get(idea.id)?.state)
        assertEquals(ChoreState.ARCHIVED, chores.get(other.id)?.state)
        assertTrue(assertIs<ApprovalsUiState.Ready>(state.value).proposals.isEmpty())
    }
}

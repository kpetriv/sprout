package dev.kpetriv.sprout.feature.chores

import androidx.lifecycle.SavedStateHandle
import dev.kpetriv.sprout.core.domainhousehold.ChoreScheduler
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.ChoreState
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.time.Clock
import dev.kpetriv.sprout.core.time.HouseholdCalendar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class KidTodayViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val kids = FakeKidRepository(listOf(TestData.kid("kid-leo", "Leo")))
    private val chores = FakeChoreRepository()
    private val photos = FakePhotoStore()

    private fun viewModel(
        tier: KidUiTier = KidUiTier.SPROUT,
        kidId: String = "kid-leo",
        handle: SavedStateHandle = SavedStateHandle(
            mapOf(KidTodayViewModel.KID_ID_KEY to kidId, KidTodayViewModel.TIER_KEY to tier.name),
        ),
        calendar: HouseholdCalendar = TestData.calendar(),
    ) = KidTodayViewModel(
        savedStateHandle = handle,
        chores = chores,
        kids = kids,
        photoStore = photos,
        fiat = { TestData.gbp },
        clock = TestData.clock(),
        calendar = calendar,
    )

    /** What the system restores after process death: the saved values only, in a fresh handle. */
    private fun SavedStateHandle.restored() = SavedStateHandle(keys().associateWith { get<Any?>(it) })

    private val bed = TestData.chore("bed", "kid-leo", "Make bed")

    @Test
    fun `today lists chores due today, to-do first, and the next seven days`() = runTest {
        // 4 November 2026 is a Wednesday.
        val saturday = TestData.chore("car", "kid-leo", "Wash car", schedule = ChoreSchedule.Weekly(DayOfWeek.SATURDAY))
        val anyTime = TestData.chore("toys", "kid-leo", "Tidy toys", schedule = ChoreSchedule.Once(null))
        val proposed = TestData.chore("idea", "kid-leo", "Walk dog", state = ChoreState.PROPOSED)
        chores.chores.value = listOf(bed, saturday, anyTime, proposed)
        chores.submissions.value = listOf(TestData.submission(bed))

        val state = assertIs<KidTodayUiState.Ready>(subscribe(viewModel().state).value)

        assertEquals(listOf("Tidy toys", "Make bed"), state.today.map { it.title })
        assertEquals(listOf(OccurrenceStatus.TO_DO, OccurrenceStatus.WAITING), state.today.map { it.status })
        assertEquals(TestData.today.plusDays(3), state.upcoming.single { it.title == "Wash car" }.dueOn)
        assertEquals(7, state.upcoming.count { it.title == "Make bed" })
        assertTrue(state.proposals.isEmpty())
    }

    @Test
    fun `an any-time chore is one occurrence whatever the day and goes once approved`() = runTest {
        val anyTime = TestData.chore("toys", "kid-leo", "Tidy toys", schedule = ChoreSchedule.Once(null))
        chores.chores.value = listOf(anyTime)
        val vm = viewModel()
        val state = subscribe(vm.state)
        val item = assertIs<KidTodayUiState.Ready>(state.value).today.single()
        assertEquals(ChoreScheduler.anyTimeDueOn(anyTime), item.dueOn)

        vm.onEvent(KidTodayEvent.OpenSubmit(anyTime.id))
        vm.onEvent(KidTodayEvent.ConfirmSubmit)
        assertEquals(ChoreScheduler.anyTimeDueOn(anyTime), chores.submitCalls.single().second)
        assertEquals(OccurrenceStatus.WAITING, assertIs<KidTodayUiState.Ready>(state.value).today.single().status)

        chores.decide(chores.submissions.value.single().id, dev.kpetriv.sprout.core.database.SubmissionDecision.APPROVE)
        assertTrue(assertIs<KidTodayUiState.Ready>(state.value).today.isEmpty())
    }

    @Test
    fun `an unknown kid is an error`() = runTest {
        val state = subscribe(viewModel(kidId = "kid-nobody").state).value
        assertEquals(KidTodayUiState.Error(KidTodayError.KID_NOT_FOUND), state)
    }

    @Test
    fun `seedling shows at most three cards and done submits once with confetti`() = runTest {
        chores.chores.value = listOf(
            bed,
            TestData.chore("a", "kid-leo", "Brush teeth"),
            TestData.chore("b", "kid-leo", "Feed the cat"),
            TestData.chore("c", "kid-leo", "Tidy toys"),
        )
        val vm = viewModel(KidUiTier.SEEDLING)
        val state = subscribe(vm.state)
        assertEquals(3, assertIs<KidTodayUiState.Ready>(state.value).seedlingCards.size)

        val gate = CompletableDeferred<Unit>()
        chores.submitGate = gate
        vm.onEvent(KidTodayEvent.Done(bed.id))
        vm.onEvent(KidTodayEvent.Done(bed.id))
        gate.complete(Unit)
        vm.onEvent(KidTodayEvent.Done(bed.id))

        assertEquals(1, chores.submitCalls.size)
        val ready = assertIs<KidTodayUiState.Ready>(state.value)
        assertEquals("Make bed", ready.celebration)
        assertEquals(OccurrenceStatus.WAITING, ready.today.single { it.choreId == bed.id }.status)
        vm.onEvent(KidTodayEvent.CelebrationShown)
        assertNull(assertIs<KidTodayUiState.Ready>(state.value).celebration)
    }

    @Test
    fun `sprout submits once with note and an encrypted photo`() = runTest {
        chores.chores.value = listOf(bed)
        val vm = viewModel()
        val state = subscribe(vm.state)

        vm.onEvent(KidTodayEvent.OpenSubmit(bed.id))
        vm.onEvent(KidTodayEvent.NoteChanged("x".repeat(250)))
        vm.onEvent(KidTodayEvent.PhotoCaptured(byteArrayOf(9, 9)))
        assertEquals(200, assertNotNull(assertIs<KidTodayUiState.Ready>(state.value).sheet).note.length)
        vm.onEvent(KidTodayEvent.ConfirmSubmit)
        vm.onEvent(KidTodayEvent.OpenSubmit(bed.id))

        assertEquals(1, chores.submitCalls.size)
        val ref = assertNotNull(chores.submittedPhotos.single())
        assertTrue(photos.stored.getValue(ref).contentEquals(byteArrayOf(9, 9)))
        val ready = assertIs<KidTodayUiState.Ready>(state.value)
        assertNull(ready.sheet)
        assertEquals(KidTodayMessage.Submitted, ready.message)
    }

    @Test
    fun `a failed submit keeps the encrypted photo for the next try and closing the sheet deletes it`() = runTest {
        chores.chores.value = listOf(bed)
        chores.failWrites = true
        val vm = viewModel()
        val state = subscribe(vm.state)

        vm.onEvent(KidTodayEvent.OpenSubmit(bed.id))
        vm.onEvent(KidTodayEvent.PhotoCaptured(byteArrayOf(1)))
        vm.onEvent(KidTodayEvent.ConfirmSubmit)

        assertTrue(photos.deleted.isEmpty())
        val ready = assertIs<KidTodayUiState.Ready>(state.value)
        assertEquals(KidTodayMessage.SubmitFailed, ready.message)
        assertEquals(false, assertNotNull(ready.sheet).isSubmitting)
        assertTrue(assertNotNull(ready.sheet).hasPhoto)

        vm.onEvent(KidTodayEvent.DismissSubmit)
        assertEquals(1, photos.deleted.size)
        assertTrue(photos.stored.isEmpty())
    }

    @Test
    fun `a captured photo is encrypted at once and replacing or removing it deletes the old one`() = runTest {
        chores.chores.value = listOf(bed)
        val vm = viewModel()
        val state = subscribe(vm.state)

        vm.onEvent(KidTodayEvent.OpenSubmit(bed.id))
        vm.onEvent(KidTodayEvent.PhotoCaptured(byteArrayOf(1)))
        assertEquals(1, photos.stored.size)
        vm.onEvent(KidTodayEvent.PhotoCaptured(byteArrayOf(2)))
        assertEquals(listOf(byteArrayOf(2).toList()), photos.stored.values.map { it.toList() })
        vm.onEvent(KidTodayEvent.RemovePhoto)

        assertTrue(photos.stored.isEmpty())
        assertEquals(2, photos.deleted.size)
        assertEquals(false, assertNotNull(assertIs<KidTodayUiState.Ready>(state.value).sheet).hasPhoto)
    }

    @Test
    fun `the submit sheet survives process death with its note and pending photo`() = runTest {
        chores.chores.value = listOf(bed)
        val handle = SavedStateHandle(
            mapOf(KidTodayViewModel.KID_ID_KEY to "kid-leo", KidTodayViewModel.TIER_KEY to KidUiTier.SPROUT.name),
        )
        val first = viewModel(handle = handle)
        subscribe(first.state)
        first.onEvent(KidTodayEvent.OpenSubmit(bed.id))
        first.onEvent(KidTodayEvent.NoteChanged("Done before school"))
        first.onEvent(KidTodayEvent.PhotoCaptured(byteArrayOf(7)))
        val restoredHandle = handle.restored()

        val restored = viewModel(handle = restoredHandle)
        val state = subscribe(restored.state)
        val sheet = assertNotNull(assertIs<KidTodayUiState.Ready>(state.value).sheet)
        assertEquals(SubmitSheetUi(bed.id, "Make bed", note = "Done before school", hasPhoto = true), sheet)

        restored.onEvent(KidTodayEvent.ConfirmSubmit)
        val (_, _, note) = chores.submitCalls.single()
        assertEquals("Done before school", note)
        val ref = assertNotNull(chores.submittedPhotos.single())
        assertTrue(photos.stored.getValue(ref).contentEquals(byteArrayOf(7)))
        assertNull(assertIs<KidTodayUiState.Ready>(state.value).sheet)
        assertNull(restoredHandle.get<String>(KidTodayViewModel.SHEET_CHORE_KEY))
        assertNull(restoredHandle.get<String>(KidTodayViewModel.PHOTO_FILE_KEY))
    }

    @Test
    fun `today moves on at the household's midnight while the screen is open`() = runTest {
        chores.chores.value = listOf(bed)
        // 4 November 2026 09:00 UTC on a clock that moves with virtual time.
        val start = TestData.now.toEpochMilliseconds()
        val moving = object : Clock {
            override fun now() = Instant.fromEpochMilliseconds(start + testScheduler.currentTime)
        }
        val vm = viewModel(calendar = TestData.calendar(moving))
        val state = subscribe(vm.state)
        assertEquals(TestData.today, assertIs<KidTodayUiState.Ready>(state.value).date)

        advanceTimeBy(15.hours + 1.minutes)

        val next = assertIs<KidTodayUiState.Ready>(state.value)
        assertEquals(TestData.today.plusDays(1), next.date)
        assertEquals(TestData.today.plusDays(1), next.today.single().dueOn)
    }

    @Test
    fun `approved chores show done`() = runTest {
        chores.chores.value = listOf(bed)
        val submitted = TestData.submission(bed)
        chores.submissions.value = listOf(submitted)
        chores.decide(submitted.id, dev.kpetriv.sprout.core.database.SubmissionDecision.APPROVE)

        val state = assertIs<KidTodayUiState.Ready>(subscribe(viewModel().state).value)
        assertEquals(OccurrenceStatus.DONE, state.today.single().status)
    }

    @Test
    fun `only sapling can propose`() = runTest {
        for (tier in listOf(KidUiTier.SEEDLING, KidUiTier.SPROUT)) {
            val vm = viewModel(tier)
            val state = subscribe(vm.state)
            vm.onEvent(KidTodayEvent.OpenPropose)
            vm.onEvent(KidTodayEvent.ProposeTitleChanged("Walk the dog"))
            vm.onEvent(KidTodayEvent.ProposeMoneyChanged("3"))
            vm.onEvent(KidTodayEvent.ConfirmPropose)
            val ready = assertIs<KidTodayUiState.Ready>(state.value)
            assertNull(ready.propose)
            assertEquals(false, ready.canPropose)
        }
        assertTrue(chores.chores.value.isEmpty())
    }

    @Test
    fun `sapling proposes a chore for a parent to accept`() = runTest {
        val vm = viewModel(KidUiTier.SAPLING)
        val state = subscribe(vm.state)

        vm.onEvent(KidTodayEvent.OpenPropose)
        vm.onEvent(KidTodayEvent.ConfirmPropose)
        val errors = assertNotNull(assertIs<KidTodayUiState.Ready>(state.value).propose).errors
        assertEquals(setOf(EditorError.TITLE_BLANK, EditorError.NO_REWARD), errors)

        vm.onEvent(KidTodayEvent.ProposeTitleChanged("Walk the dog"))
        vm.onEvent(KidTodayEvent.ProposeMoneyChanged("3"))
        vm.onEvent(KidTodayEvent.ConfirmPropose)

        val proposal = chores.chores.value.single()
        assertEquals(ChoreState.PROPOSED, proposal.state)
        assertEquals(Money(300), proposal.reward.money)
        assertEquals(TestData.household, proposal.householdId)
        val ready = assertIs<KidTodayUiState.Ready>(state.value)
        assertEquals(listOf("Walk the dog"), ready.proposals.map { it.title })
        assertEquals(KidTodayMessage.Proposed, ready.message)
        assertTrue(ready.today.isEmpty())
    }
}

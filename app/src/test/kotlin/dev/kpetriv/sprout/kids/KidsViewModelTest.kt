package dev.kpetriv.sprout.kids

import app.cash.turbine.test
import dev.kpetriv.sprout.FakeKidRepository
import dev.kpetriv.sprout.FakeStartupStatus
import dev.kpetriv.sprout.MainDispatcherRule
import dev.kpetriv.sprout.core.model.AgeBand
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.YearMonth
import dev.kpetriv.sprout.core.time.FakeClock
import dev.kpetriv.sprout.core.time.HouseholdCalendar
import dev.kpetriv.sprout.kid
import dev.kpetriv.sprout.startup.StartupFailure
import dev.kpetriv.sprout.startup.StartupState
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant

class KidsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = FakeClock(Instant.parse("2026-11-02T00:00:00Z"))
    private val startup = FakeStartupStatus()

    private fun viewModel(repository: FakeKidRepository) =
        KidsViewModel(repository, startup, HouseholdCalendar(clock) { ZoneOffset.UTC })

    @Test
    fun `maps kids to rows with age band and tier`() = runTest {
        val mia = kid("Mia", YearMonth(2020, 11), KidUiTier.SEEDLING)
        val zoe = kid("Zoe", YearMonth(2013, 12), KidUiTier.SAPLING)
        val repository = FakeKidRepository(listOf(mia, zoe))

        viewModel(repository).state.test {
            assertEquals(
                KidsUiState.Ready(
                    listOf(
                        KidRow(mia.id, "Mia", AgeBand.AGES_5_TO_7, KidUiTier.SEEDLING),
                        // Turns 13 next month, so still 12.
                        KidRow(zoe.id, "Zoe", AgeBand.AGES_12_PLUS, KidUiTier.SAPLING),
                    ),
                ),
                awaitItem(),
            )
        }
    }

    @Test
    fun `a profile whose age cannot be computed shows unknown without ending the list`() = runTest {
        val leo = kid("Leo", YearMonth(2017, 11), KidUiTier.SPROUT)
        // Born "next year": the device clock or the profile is wrong.
        val future = kid("Ava", YearMonth(2027, 5), KidUiTier.SEEDLING)
        val repository = FakeKidRepository(listOf(future, leo))

        viewModel(repository).state.test {
            assertEquals(
                KidsUiState.Ready(
                    listOf(
                        KidRow(future.id, "Ava", null, KidUiTier.SEEDLING),
                        KidRow(leo.id, "Leo", AgeBand.AGES_8_TO_11, KidUiTier.SPROUT),
                    ),
                ),
                awaitItem(),
            )
            // The flow is still live.
            repository.archive(future.id)
            assertEquals(
                KidsUiState.Ready(listOf(KidRow(leo.id, "Leo", AgeBand.AGES_8_TO_11, KidUiTier.SPROUT))),
                awaitItem(),
            )
        }
    }

    @Test
    fun `re-emits when the kid list changes`() = runTest {
        val repository = FakeKidRepository(emptyList())
        viewModel(repository).state.test {
            assertEquals(KidsUiState.Ready(emptyList()), awaitItem())
            val leo = kid("Leo", YearMonth(2017, 11), KidUiTier.SPROUT)
            repository.upsert(leo)
            assertEquals(
                KidsUiState.Ready(listOf(KidRow(leo.id, "Leo", AgeBand.AGES_8_TO_11, KidUiTier.SPROUT))),
                awaitItem(),
            )
        }
    }

    @Test
    fun `a failing repository gives the error state and retry re-subscribes`() = runTest {
        val repository = FakeKidRepository(emptyList(), failReads = true)
        val viewModel = viewModel(repository)
        viewModel.state.test {
            assertEquals(KidsUiState.Error(), awaitItem())
            repository.failReads = false
            viewModel.onEvent(KidsEvent.Retry)
            assertEquals(KidsUiState.Ready(emptyList()), awaitItem())
        }
        assertEquals(1, startup.retries)
    }

    @Test
    fun `startup progress and failure are shown and retried`() = runTest {
        startup.state.value = StartupState.Running
        val repository = FakeKidRepository(emptyList())
        val viewModel = viewModel(repository)
        viewModel.state.test {
            assertEquals(KidsUiState.Loading, awaitItem())
            startup.state.value = StartupState.Failed(StartupFailure.KEYS)
            assertEquals(KidsUiState.Ready(emptyList(), StartupFailure.KEYS), awaitItem())

            viewModel.onEvent(KidsEvent.Retry)
            assertEquals(1, startup.retries)
            startup.state.value = StartupState.Ready
            assertEquals(KidsUiState.Ready(emptyList()), expectMostRecentItem())
        }
    }

    @Test
    fun `row toString never contains the name`() {
        val mia = kid("Mia", YearMonth(2020, 1), KidUiTier.SEEDLING)
        val row = KidRow(mia.id, "Mia", AgeBand.AGES_5_TO_7, KidUiTier.SEEDLING)
        assertFalse("Mia" in row.toString())
    }
}

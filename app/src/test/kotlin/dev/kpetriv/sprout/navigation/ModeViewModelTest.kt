package dev.kpetriv.sprout.navigation

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.kpetriv.sprout.FakeKidRepository
import dev.kpetriv.sprout.MainDispatcherRule
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.YearMonth
import dev.kpetriv.sprout.kid
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val leo = kid("Leo", YearMonth(2017, 1), KidUiTier.SPROUT)
    private var locks = 0
    private val handle = SavedStateHandle()

    private fun viewModel() = ModeViewModel(handle, FakeKidRepository(listOf(leo))) { locks += 1 }

    @Test
    fun `starts in parent mode`() {
        assertEquals(AppMode.Parent, viewModel().mode.value)
    }

    @Test
    fun `entering kid mode locks the parent area, and exiting returns to parent mode`() = runTest {
        val viewModel = viewModel()
        viewModel.mode.test {
            assertEquals(AppMode.Parent, awaitItem())
            viewModel.enterKidMode(leo.id)
            assertEquals(AppMode.Kid(leo.id), awaitItem())
            assertEquals(1, locks)
            viewModel.exitKidMode()
            assertEquals(AppMode.Parent, awaitItem())
        }
        assertEquals(1, locks, "leaving kid mode never locks or unlocks by itself")
    }

    @Test
    fun `kid mode survives process death through the saved state`() {
        viewModel().enterKidMode(leo.id)
        val saved = handle.get<String>(ModeViewModel.KID_ID_KEY)
        assertEquals(leo.id.value, saved)
        val restored = ModeViewModel(
            SavedStateHandle(mapOf(ModeViewModel.KID_ID_KEY to saved)),
            FakeKidRepository(emptyList()),
        ) {}
        assertEquals(AppMode.Kid(leo.id), restored.mode.value)
    }

    @Test
    fun `the tier comes from the kid repository`() = runTest {
        val viewModel = viewModel()
        assertEquals(KidUiTier.SPROUT, viewModel.tierOf(leo.id))
        assertNull(viewModel.tierOf(KidId("nobody")))
    }
}

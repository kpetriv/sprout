package dev.kpetriv.sprout.navigation

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.kpetriv.sprout.FakeKidRepository
import dev.kpetriv.sprout.FakeSessionStore
import dev.kpetriv.sprout.FakeShellPreferences
import dev.kpetriv.sprout.FakeStartupStatus
import dev.kpetriv.sprout.MainDispatcherRule
import dev.kpetriv.sprout.REAL_HOUSEHOLD
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.YearMonth
import dev.kpetriv.sprout.kid
import dev.kpetriv.sprout.kidSession
import dev.kpetriv.sprout.parentSession
import dev.kpetriv.sprout.startup.StartupState
import dev.kpetriv.sprout.sync.GateState
import dev.kpetriv.sprout.sync.Removal
import dev.kpetriv.sprout.sync.RemovalReason
import dev.kpetriv.sprout.trialSession
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val sessions = FakeSessionStore(parentSession())
    private val signals = object : ShellSignals {
        override val removal = MutableStateFlow<Removal?>(null)
        override val gate = MutableStateFlow<GateState?>(null)
        override val parentSetupPending = MutableStateFlow<String?>(null)

        override fun digestOf(householdId: HouseholdId): String = preferences.digestOf(householdId)
    }
    private val preferences = FakeShellPreferences()
    private val startup = FakeStartupStatus()

    private fun viewModel(savedState: SavedStateHandle = handle) =
        ModeViewModel(savedState, FakeKidRepository(listOf(leo)), { locks += 1 }, sessions, signals, startup)

    @Test
    fun `a parent session starts in parent mode`() {
        assertEquals(AppMode.Parent, viewModel().mode.value)
    }

    @Test
    fun `entering kid mode locks the parent area, and exiting returns to parent mode`() = runTest {
        val viewModel = viewModel()
        viewModel.mode.test {
            assertEquals(AppMode.Parent, awaitItem())
            viewModel.onEvent(ModeEvent.EnterKidMode(leo.id))
            assertEquals(AppMode.Kid(leo.id), awaitItem())
            assertEquals(1, locks)
            viewModel.onEvent(ModeEvent.ExitKidMode)
            assertEquals(AppMode.Parent, awaitItem())
        }
        assertEquals(1, locks, "leaving kid mode never locks or unlocks by itself")
    }

    @Test
    fun `kid mode survives process death through the saved state`() {
        viewModel().onEvent(ModeEvent.EnterKidMode(leo.id))
        val saved = handle.get<String>(ModeViewModel.KID_ID_KEY)
        assertEquals(leo.id.value, saved)
        val restored = viewModel(SavedStateHandle(mapOf(ModeViewModel.KID_ID_KEY to saved)))
        assertEquals(AppMode.Kid(leo.id), restored.mode.value)
    }

    @Test
    fun `the mode follows the session`() = runTest {
        val viewModel = viewModel()
        viewModel.mode.test {
            assertEquals(AppMode.Parent, awaitItem())
            sessions.session.value = null
            assertEquals(AppMode.Onboarding(canLeave = false), awaitItem())
            sessions.session.value = kidSession(leo.id)
            assertEquals(AppMode.Kid(leo.id, linkedDevice = true), awaitItem())
            sessions.session.value = parentSession(RegistrationStatus.PENDING)
            assertEquals(AppMode.Onboarding(canLeave = false), awaitItem(), "a phone waiting for approval waits")
            sessions.session.value = trialSession()
            assertEquals(AppMode.Parent, awaitItem(), "the trial household is a parent household")
            signals.removal.value = Removal(RemovalReason.DEVICE_REVOKED)
            assertEquals(AppMode.Removed(Removal(RemovalReason.DEVICE_REVOKED)), awaitItem())
        }
    }

    @Test
    fun `a kid's own phone never shows the parent area, even with a stale kid-mode kid`() {
        sessions.session.value = kidSession(leo.id)
        val restored = viewModel(SavedStateHandle(mapOf(ModeViewModel.KID_ID_KEY to "someone-else")))
        assertEquals(AppMode.Kid(leo.id, linkedDevice = true), restored.mode.value)
    }

    @Test
    fun `set up sync from the trial opens onboarding, which stays until it ends even once a session is saved`() =
        runTest {
            sessions.session.value = trialSession()
            val viewModel = viewModel()
            viewModel.mode.test {
                assertEquals(AppMode.Parent, awaitItem())
                viewModel.onEvent(ModeEvent.BeginOnboarding)
                assertEquals(AppMode.Onboarding(canLeave = true), awaitItem())
                sessions.session.value = parentSession()
                assertEquals(AppMode.Onboarding(canLeave = false), awaitItem())
                viewModel.onEvent(ModeEvent.EndOnboarding)
                assertEquals(AppMode.Parent, awaitItem())
            }
        }

    @Test
    fun `the tier comes from the kid repository`() = runTest {
        val viewModel = viewModel()
        assertEquals(KidUiTier.SPROUT, viewModel.tierOf(leo.id))
        assertNull(viewModel.tierOf(KidId("nobody")))
    }

    @Test
    fun `nothing is decided while startup still restores the session`() = runTest {
        startup.state.value = StartupState.Running
        sessions.session.value = null
        val viewModel = viewModel()
        viewModel.mode.test {
            assertEquals(AppMode.Loading, awaitItem(), "no onboarding flash before the trial session is seeded")
            sessions.session.value = trialSession()
            startup.state.value = StartupState.Ready
            assertEquals(AppMode.Parent, awaitItem())
        }
    }

    @Test
    fun `the derivation without a loaded session is loading`() {
        assertEquals(
            AppMode.Loading,
            appModeOf(
                SessionView.Loading,
                onboarding = false,
                kidModeKid = null,
                shell = ShellState(),
                digestOf = { it.value },
            ),
        )
    }

    @Test
    fun `a removal found during startup shows only once startup has finished`() = runTest {
        startup.state.value = StartupState.Running
        signals.removal.value = Removal(RemovalReason.HOUSEHOLD_GONE)
        val viewModel = viewModel()
        viewModel.mode.test {
            assertEquals(AppMode.Loading, awaitItem())
            startup.state.value = StartupState.Ready
            assertEquals(AppMode.Removed(Removal(RemovalReason.HOUSEHOLD_GONE)), awaitItem())
        }
    }

    @Test
    fun `the sign-in gate hides a real household, never the trial, and a removal wins over it`() = runTest {
        val viewModel = viewModel()
        viewModel.mode.test {
            assertEquals(AppMode.Parent, awaitItem())
            signals.gate.value = GateState.Checking
            assertEquals(AppMode.SignInAgain(GateState.Checking), awaitItem())
            sessions.session.value = trialSession()
            assertEquals(AppMode.Parent, awaitItem())
            sessions.session.value = parentSession()
            assertEquals(AppMode.SignInAgain(GateState.Checking), awaitItem())
            signals.removal.value = Removal(RemovalReason.DEVICE_REVOKED, wiped = true)
            assertEquals(AppMode.Removed(Removal(RemovalReason.DEVICE_REVOKED, wiped = true)), awaitItem())
        }
    }

    @Test
    fun `a parent setup left unfinished by a process death returns to onboarding for that household only`() = runTest {
        signals.parentSetupPending.value = preferences.digestOf(REAL_HOUSEHOLD)
        val viewModel = viewModel()
        viewModel.mode.test {
            assertEquals(AppMode.Onboarding(canLeave = false), awaitItem())
            signals.parentSetupPending.value = preferences.digestOf(HouseholdId("another"))
            assertEquals(AppMode.Parent, awaitItem())
        }
    }
}

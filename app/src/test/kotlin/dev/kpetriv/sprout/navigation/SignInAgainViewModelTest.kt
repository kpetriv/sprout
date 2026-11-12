package dev.kpetriv.sprout.navigation

import android.content.Context
import dev.kpetriv.sprout.FakeSessionStore
import dev.kpetriv.sprout.MainDispatcherRule
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.syncfirebase.AuthState
import dev.kpetriv.sprout.core.syncfirebase.DeviceStatusValue
import dev.kpetriv.sprout.parentSession
import dev.kpetriv.sprout.sync.CredentialGate
import dev.kpetriv.sprout.sync.EraseConfirmation
import dev.kpetriv.sprout.sync.ErasePinCheck
import dev.kpetriv.sprout.sync.GateAuth
import dev.kpetriv.sprout.sync.GateDeviceStatus
import dev.kpetriv.sprout.sync.GateState
import dev.kpetriv.sprout.sync.RemovalReason
import dev.kpetriv.sprout.testPause
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Erase this phone's copy instead" asks first: what is lost, and the parent PIN when there is one (audit L5). */
@OptIn(ExperimentalCoroutinesApi::class)
class SignInAgainViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val removals = mutableListOf<RemovalReason>()
    private var status = CompletableDeferred(Result.success(GateDeviceStatus(DeviceStatusValue.ACTIVE, false)))

    private val confirmation = object : EraseConfirmation {
        var unsynced = 3
        var hasPin = true
        var check = ErasePinCheck.CONFIRMED
        var keeps = false
        var hasKey = true
        val checkedPins = mutableListOf<String>()
        var lastPin: CharArray? = null

        override suspend fun unsyncedChanges(): Int = unsynced

        override suspend fun hasParentPin(): Boolean = hasPin

        override suspend fun keepsChatKey(): Boolean = keeps

        override suspend fun hasChatKey(): Boolean = hasKey

        override suspend fun checkPin(pin: CharArray): ErasePinCheck {
            checkedPins += String(pin)
            lastPin = pin
            return check
        }
    }

    private val auth = object : GateAuth {
        override val usesEmailPassword: Boolean = true

        override suspend fun current(): AuthState = AuthState.SignedOut

        override suspend fun refresh(): Result<HouseholdId?> = error("not used")

        override suspend fun signInWithGoogle(activityContext: Context): Result<Unit> = error("not used")

        override suspend fun signInWithEmailPassword(email: String, password: String): Result<Unit> = error("not used")

        override suspend fun reauthenticateWithGoogle(activityContext: Context): Result<Unit> = error("not used")

        override suspend fun reauthenticateWithEmailPassword(email: String, password: String): Result<Unit> =
            error("not used")

        override suspend fun signOut() = Unit
    }

    private fun TestScope.gated(): CredentialGate = CredentialGate(
        scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        sessions = FakeSessionStore(parentSession()),
        auth = auth,
        deviceStatus = { status.await() },
        removals = { removals += it },
        syncNow = { },
        pause = testPause(),
    ).also { it.credentialsLost() }

    @Test
    fun `erase asks first, with the unsynced changes and the PIN, and erases only after the right PIN`() = runTest {
        val viewModel = SignInAgainViewModel(gated(), confirmation)

        viewModel.onEvent(SignInAgainEvent.EraseInstead)
        assertEquals(EraseConfirmState(unsyncedChanges = 3, needsPin = true), viewModel.state.value.erase)
        assertEquals(emptyList(), removals, "nothing is erased by opening the confirmation")

        confirmation.check = ErasePinCheck.WRONG
        viewModel.onEvent(SignInAgainEvent.ConfirmErase("111111".toCharArray()))
        assertEquals(
            EraseConfirmState(unsyncedChanges = 3, needsPin = true, pinFailure = ErasePinCheck.WRONG),
            viewModel.state.value.erase,
        )
        assertEquals(emptyList(), removals)
        assertTrue(confirmation.lastPin!!.all { it == '\u0000' }, "the PIN is zeroed once checked")

        confirmation.check = ErasePinCheck.CONFIRMED
        viewModel.onEvent(SignInAgainEvent.ConfirmErase("482913".toCharArray()))
        assertNull(viewModel.state.value.erase)
        assertEquals(listOf(RemovalReason.SIGNED_OUT), removals)
        assertEquals(listOf("111111", "482913"), confirmation.checkedPins)
    }

    @Test
    fun `without a PIN the confirmation alone erases, and cancelling erases nothing`() = runTest {
        confirmation.hasPin = false
        confirmation.unsynced = 0
        val viewModel = SignInAgainViewModel(gated(), confirmation)

        viewModel.onEvent(SignInAgainEvent.EraseInstead)
        assertEquals(EraseConfirmState(unsyncedChanges = 0, needsPin = false), viewModel.state.value.erase)
        viewModel.onEvent(SignInAgainEvent.CancelErase)
        assertNull(viewModel.state.value.erase)
        assertEquals(emptyList(), removals)

        viewModel.onEvent(SignInAgainEvent.EraseInstead)
        viewModel.onEvent(SignInAgainEvent.ConfirmErase())
        assertEquals(listOf(RemovalReason.SIGNED_OUT), removals)
        assertEquals(emptyList(), confirmation.checkedPins)
    }

    @Test
    fun `a PIN is required when there is one, and a locked-out PIN says so`() = runTest {
        val viewModel = SignInAgainViewModel(gated(), confirmation)
        viewModel.onEvent(SignInAgainEvent.EraseInstead)
        viewModel.onEvent(SignInAgainEvent.ConfirmErase())
        assertEquals(emptyList(), removals, "no PIN, no erase")

        confirmation.check = ErasePinCheck.LOCKED_OUT
        viewModel.onEvent(SignInAgainEvent.ConfirmErase("482913".toCharArray()))
        assertEquals(ErasePinCheck.LOCKED_OUT, viewModel.state.value.erase?.pinFailure)
        assertEquals(emptyList(), removals)
    }

    @Test
    fun `not offered while the status check is in flight`() = runTest {
        status = CompletableDeferred()
        val gate = gated()
        assertEquals(GateState.Checking, gate.state.value)
        val viewModel = SignInAgainViewModel(gate, confirmation)

        viewModel.onEvent(SignInAgainEvent.EraseInstead)
        assertNull(viewModel.state.value.erase)
        viewModel.onEvent(SignInAgainEvent.ConfirmErase("482913".toCharArray()))
        assertEquals(emptyList(), removals)

        status.complete(Result.success(GateDeviceStatus(DeviceStatusValue.ACTIVE, false)))
        viewModel.onEvent(SignInAgainEvent.EraseInstead)
        assertEquals(EraseConfirmState(unsyncedChanges = 3, needsPin = true), viewModel.state.value.erase)
    }

    @Test
    fun `the confirmation keeps the chat key only after the registry answered, on a parent's phone`() = runTest {
        confirmation.keeps = true
        val answered = SignInAgainViewModel(gated(), confirmation)
        answered.onEvent(SignInAgainEvent.EraseInstead)
        assertEquals(true, answered.state.value.erase?.keepsChatKey, "a sign-out: the key stays (D-REL-28)")

        status = CompletableDeferred(Result.failure(IllegalStateException("offline")))
        val gate = gated()
        assertEquals(GateState.Unreachable, gate.state.value)
        val unreachable = SignInAgainViewModel(gate, confirmation)
        unreachable.onEvent(SignInAgainEvent.EraseInstead)
        assertEquals(false, unreachable.state.value.erase?.keepsChatKey, "unverified: chat is set up again")

        confirmation.keeps = false
        status = CompletableDeferred(Result.success(GateDeviceStatus(DeviceStatusValue.ACTIVE, false)))
        val kid = SignInAgainViewModel(gated(), confirmation)
        kid.onEvent(SignInAgainEvent.EraseInstead)
        assertEquals(false, kid.state.value.erase?.keepsChatKey, "a phone whose erase keeps nothing")
    }

    @Test
    fun `a parent's phone without a chat key is not told the erase keeps one`() = runTest {
        confirmation.keeps = true
        confirmation.hasKey = false
        val viewModel = SignInAgainViewModel(gated(), confirmation)
        viewModel.onEvent(SignInAgainEvent.EraseInstead)
        assertEquals(false, viewModel.state.value.erase?.keepsChatKey, "no key to keep: the text must not promise one")
    }

    @Test
    fun `an unreadable outbox reads as unknown and an unreadable PIN store asks for the PIN`() = runTest {
        val failing = object : EraseConfirmation by confirmation {
            override suspend fun unsyncedChanges(): Int = error("disk")

            override suspend fun hasParentPin(): Boolean = error("disk")
        }
        val viewModel = SignInAgainViewModel(gated(), failing)
        viewModel.onEvent(SignInAgainEvent.EraseInstead)
        assertEquals(EraseConfirmState(unsyncedChanges = null, needsPin = true), viewModel.state.value.erase)
    }
}

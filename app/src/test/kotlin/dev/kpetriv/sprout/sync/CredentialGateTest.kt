package dev.kpetriv.sprout.sync

import android.content.Context
import android.content.ContextWrapper
import dev.kpetriv.sprout.FakeSessionStore
import dev.kpetriv.sprout.PARENT
import dev.kpetriv.sprout.REAL_HOUSEHOLD
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.syncfirebase.AuthError
import dev.kpetriv.sprout.core.syncfirebase.AuthException
import dev.kpetriv.sprout.core.syncfirebase.AuthState
import dev.kpetriv.sprout.core.syncfirebase.Claims
import dev.kpetriv.sprout.core.syncfirebase.DeviceStatusValue
import dev.kpetriv.sprout.parentSession
import dev.kpetriv.sprout.testPause
import dev.kpetriv.sprout.trialSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CredentialGateTest {
    private val sessions = FakeSessionStore(parentSession())
    private val removals = mutableListOf<RemovalReason>()
    private var syncs = 0
    private var status: Result<GateDeviceStatus> = Result.success(GateDeviceStatus(DeviceStatusValue.ACTIVE, false))
    private val context: Context = ContextWrapper(null)

    /** What happened, in order, across the pause, the auth calls and the syncs. */
    private val events = mutableListOf<String>()
    private val pause = testPause { events += "exclusive" }

    private val auth = object : GateAuth {
        override val usesEmailPassword: Boolean = true
        var state: AuthState = AuthState.SignedOut
        var refresh: Result<HouseholdId?> = Result.success(REAL_HOUSEHOLD)
        var signIn: Result<Unit> = Result.success(Unit)
        var signInAs: UserId = PARENT
        var signOuts = 0

        override suspend fun current(): AuthState = state

        override suspend fun refresh(): Result<HouseholdId?> {
            events += "refresh"
            return refresh
        }

        override suspend fun signInWithGoogle(activityContext: Context): Result<Unit> = signInWithEmailPassword("", "")

        override suspend fun signInWithEmailPassword(email: String, password: String): Result<Unit> {
            events += if (pause.isPaused) "sign in (paused)" else "sign in"
            if (signIn.isSuccess) state = AuthState.SignedIn(signInAs, false, Claims.NONE)
            return signIn
        }

        override suspend fun reauthenticateWithGoogle(activityContext: Context): Result<Unit> = error("not used")

        override suspend fun reauthenticateWithEmailPassword(email: String, password: String): Result<Unit> =
            error("not used")

        override suspend fun signOut() {
            events += if (pause.isPaused) "sign out (paused)" else "sign out"
            signOuts += 1
            state = AuthState.SignedOut
        }
    }

    private fun TestScope.gate() = CredentialGate(
        scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        sessions = sessions,
        auth = auth,
        deviceStatus = { status },
        removals = { removals += it },
        syncNow = {
            events += if (pause.isPaused) "sync (paused)" else "sync"
            syncs += 1
        },
        pause = pause,
        widgets = { widgetRefreshes += 1 },
    )

    private var widgetRefreshes = 0

    @Test
    fun `raising and lowering the gate redraw the widget, moves behind it do not`() = runTest {
        status = Result.failure(IllegalStateException("offline"))
        val gate = gate()
        gate.credentialsLost()
        assertEquals(GateState.Unreachable, gate.state.value)
        assertEquals(1, widgetRefreshes, "blank while the gate is up (audit 2 N2)")

        status = Result.success(GateDeviceStatus(DeviceStatusValue.ACTIVE, false))
        gate.retry()
        assertEquals(GateState.SignInRequired(emailPassword = true), gate.state.value)
        auth.signIn = Result.failure(AuthException(AuthError.Network))
        gate.signInWithEmailPassword("parent@example.test", "secret1")
        gate.dismissFailure()
        assertEquals(1, widgetRefreshes, "Unreachable, Checking, sign-in required, busy and a failure are all gated")

        auth.signIn = Result.success(Unit)
        gate.signInWithEmailPassword("parent@example.test", "secret1")
        assertNull(gate.state.value)
        assertEquals(2, widgetRefreshes, "the kid's data comes back once the gate is down")
    }

    @Test
    fun `an ACTIVE device asks for a sign-in with the same account, then syncs before opening`() = runTest {
        val gate = gate()
        gate.credentialsLost()
        assertEquals(GateState.SignInRequired(emailPassword = true), gate.state.value)

        gate.signInWithEmailPassword("parent@example.test", "secret1")

        assertNull(gate.state.value)
        assertEquals(1, syncs)
        assertEquals(emptyList(), removals)
    }

    @Test
    fun `another account is signed out again and the gate stays`() = runTest {
        auth.signInAs = UserId("someone-else")
        val gate = gate()
        gate.credentialsLost()
        gate.signInWithEmailPassword("other@example.test", "secret1")
        assertEquals(
            GateState.SignInRequired(emailPassword = true, failure = GateFailure.WRONG_ACCOUNT),
            gate.state.value,
        )
        assertEquals(1, auth.signOuts)
        assertEquals(0, syncs)
        assertEquals(
            listOf("exclusive", "sign in (paused)", "sign out (paused)"),
            events,
            "the other account is signed out before sync can resume, and its claims are never read",
        )
        assertFalse(pause.isPaused, "sync resumes afterwards")
    }

    @Test
    fun `the sign-in runs under the sync pause, and the one sync runs after it ends`() = runTest {
        val gate = gate()
        gate.credentialsLost()
        gate.signInWithEmailPassword("parent@example.test", "secret1")
        assertEquals(listOf("exclusive", "sign in (paused)", "refresh", "sync"), events)
        assertFalse(pause.isPaused)
    }

    @Test
    fun `a failed sign-in that left another account signed in signs it out too`() = runTest {
        auth.signInAs = UserId("someone-else")
        val gate = gate()
        gate.credentialsLost()
        auth.state = AuthState.SignedIn(UserId("someone-else"), false, Claims.NONE)
        auth.signIn = Result.failure(AuthException(AuthError.Network))
        gate.signInWithEmailPassword("other@example.test", "secret1")
        assertEquals(GateFailure.WRONG_ACCOUNT, (gate.state.value as GateState.SignInRequired).failure)
        assertEquals(1, auth.signOuts)
    }

    @Test
    fun `erasing instead is not possible while the status check or a sign-in runs`() = runTest {
        status = Result.failure(IllegalStateException("offline"))
        val gate = gate()
        assertFalse(gate.canEraseInstead(), "not gated")
        gate.eraseInstead()
        gate.credentialsLost()
        assertEquals(GateState.Unreachable, gate.state.value)
        assertTrue(gate.canEraseInstead())
        gate.eraseInstead()
        assertEquals(listOf(RemovalReason.ERASED_UNVERIFIED), removals, "unverified: the chat key goes too (D-REL-28)")
    }

    @Test
    fun `erasing instead is refused while the registry is being asked`() = runTest {
        val answer = kotlinx.coroutines.CompletableDeferred<Result<GateDeviceStatus>>()
        val gate = CredentialGate(
            scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            sessions = sessions,
            auth = auth,
            deviceStatus = { answer.await() },
            removals = { removals += it },
            syncNow = { syncs += 1 },
            pause = pause,
        )
        gate.credentialsLost()
        assertEquals(GateState.Checking, gate.state.value)
        assertFalse(gate.canEraseInstead())
        gate.eraseInstead()
        assertEquals(emptyList(), removals)
        answer.complete(Result.success(GateDeviceStatus(DeviceStatusValue.ACTIVE, false)))
        assertTrue(gate.canEraseInstead())
        gate.eraseInstead()
        assertEquals(listOf(RemovalReason.SIGNED_OUT), removals, "the registry still lists it: a sign-out")
    }

    @Test
    fun `a refused sign-in says so and keeps the gate`() = runTest {
        auth.signIn = Result.failure(AuthException(AuthError.InvalidCredential))
        val gate = gate()
        gate.credentialsLost()
        gate.signInWithEmailPassword("parent@example.test", "wrong!")
        assertEquals(GateFailure.INVALID_CREDENTIAL, (gate.state.value as GateState.SignInRequired).failure)
    }

    @Test
    fun `a REVOKED device or a deleted household wipes`() = runTest {
        status = Result.success(GateDeviceStatus(DeviceStatusValue.REVOKED, false))
        gate().credentialsLost()
        status = Result.success(GateDeviceStatus(DeviceStatusValue.UNKNOWN, householdGone = true))
        gate().credentialsLost()
        assertEquals(listOf(RemovalReason.DEVICE_REVOKED, RemovalReason.HOUSEHOLD_GONE), removals)
    }

    @Test
    fun `an account found gone at start is gated and asks the registry, and wipes only when it says so`() = runTest {
        val gate = gate()
        SessionReconciler(sessions, { AuthState.UserGone }, { removals += it }, gate).reconcile()
        assertEquals(GateState.SignInRequired(emailPassword = true), gate.state.value, "ACTIVE: no wipe")
        assertEquals(emptyList(), removals)

        status = Result.success(GateDeviceStatus(DeviceStatusValue.REVOKED, false))
        val revoked = gate()
        SessionReconciler(sessions, { AuthState.UserGone }, { removals += it }, revoked).reconcile()
        assertEquals(listOf(RemovalReason.DEVICE_REVOKED), removals)
    }

    @Test
    fun `an unknown device or no network stays gated with retry`() = runTest {
        status = Result.failure(IllegalStateException("offline"))
        val gate = gate()
        gate.credentialsLost()
        assertEquals(GateState.Unreachable, gate.state.value)
        status = Result.success(GateDeviceStatus(DeviceStatusValue.UNKNOWN, false))
        gate.retry()
        assertEquals(GateState.Unreachable, gate.state.value)
        status = Result.success(GateDeviceStatus(DeviceStatusValue.ACTIVE, false))
        gate.retry()
        assertEquals(GateState.SignInRequired(emailPassword = true), gate.state.value)
        assertEquals(emptyList(), removals)
    }

    @Test
    fun `an account moved to another household wipes after the sign-in`() = runTest {
        auth.refresh = Result.success(HouseholdId("another-home"))
        val gate = gate()
        gate.credentialsLost()
        gate.signInWithEmailPassword("parent@example.test", "secret1")
        assertEquals(listOf(RemovalReason.HOUSEHOLD_GONE), removals)
    }

    @Test
    fun `verify gates only on a credential error, not on a network error`() = runTest {
        auth.state = AuthState.SignedIn(PARENT, false, Claims.NONE)
        auth.refresh = Result.failure(AuthException(AuthError.Network))
        val gate = gate()
        gate.verify()
        assertNull(gate.state.value)
        auth.refresh = Result.failure(AuthException(AuthError.UserDisabled))
        gate.verify()
        assertEquals(GateState.SignInRequired(emailPassword = true), gate.state.value)
    }

    @Test
    fun `the trial session is never gated, and there is nothing to erase instead`() = runTest {
        sessions.session.value = trialSession()
        val gate = gate()
        gate.credentialsLost()
        assertNull(gate.state.value)
        gate.eraseInstead()
        assertEquals(emptyList(), removals)
        // The context is only handed to Credential Manager; the fake never uses it.
        gate.signInWithGoogle(context)
    }
}

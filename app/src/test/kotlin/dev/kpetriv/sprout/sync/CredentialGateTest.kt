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
import dev.kpetriv.sprout.trialSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class CredentialGateTest {
    private val sessions = FakeSessionStore(parentSession())
    private val removals = mutableListOf<RemovalReason>()
    private var syncs = 0
    private var status: Result<GateDeviceStatus> = Result.success(GateDeviceStatus(DeviceStatusValue.ACTIVE, false))
    private val context: Context = ContextWrapper(null)

    private val auth = object : GateAuth {
        override val usesEmailPassword: Boolean = true
        var state: AuthState = AuthState.SignedOut
        var refresh: Result<HouseholdId?> = Result.success(REAL_HOUSEHOLD)
        var signIn: Result<Unit> = Result.success(Unit)
        var signInAs: UserId = PARENT
        var signOuts = 0

        override suspend fun current(): AuthState = state

        override suspend fun refresh(): Result<HouseholdId?> = refresh

        override suspend fun signInWithGoogle(activityContext: Context): Result<Unit> = signInWithEmailPassword("", "")

        override suspend fun signInWithEmailPassword(email: String, password: String): Result<Unit> {
            if (signIn.isSuccess) state = AuthState.SignedIn(signInAs, false, Claims.NONE)
            return signIn
        }

        override suspend fun signOut() {
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
        syncNow = { syncs += 1 },
    )

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
    fun `the trial session is never gated, and erasing instead is a sign-out removal`() = runTest {
        sessions.session.value = trialSession()
        val gate = gate()
        gate.credentialsLost()
        assertNull(gate.state.value)
        gate.eraseInstead()
        assertEquals(listOf(RemovalReason.SIGNED_OUT), removals)
        // The context is only handed to Credential Manager; the fake never uses it.
        gate.signInWithGoogle(context)
    }
}

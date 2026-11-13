package dev.kpetriv.sprout.recovery

import android.content.Context
import android.content.ContextWrapper
import dev.kpetriv.sprout.FakeSessionStore
import dev.kpetriv.sprout.PARENT
import dev.kpetriv.sprout.REAL_HOUSEHOLD
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.sync.RecoveryDeviceStatus
import dev.kpetriv.sprout.core.sync.RecoveryRefusal
import dev.kpetriv.sprout.core.sync.RecoveryRefusedException
import dev.kpetriv.sprout.core.sync.SyncError
import dev.kpetriv.sprout.core.sync.SyncException
import dev.kpetriv.sprout.core.syncfirebase.AuthError
import dev.kpetriv.sprout.core.syncfirebase.AuthException
import dev.kpetriv.sprout.core.syncfirebase.AuthState
import dev.kpetriv.sprout.core.syncfirebase.CallableInvoker
import dev.kpetriv.sprout.core.syncfirebase.Claims
import dev.kpetriv.sprout.core.syncfirebase.DeviceStatusValue
import dev.kpetriv.sprout.core.syncfirebase.FunctionsError
import dev.kpetriv.sprout.core.syncfirebase.FunctionsException
import dev.kpetriv.sprout.core.syncfirebase.HouseholdFunctions
import dev.kpetriv.sprout.feature.onboarding.OnboardingException
import dev.kpetriv.sprout.feature.onboarding.OnboardingFailure
import dev.kpetriv.sprout.feature.onboarding.RecoveryException
import dev.kpetriv.sprout.feature.onboarding.RecoveryProblem
import dev.kpetriv.sprout.parentSession
import dev.kpetriv.sprout.sync.CredentialGate
import dev.kpetriv.sprout.sync.GateAuth
import dev.kpetriv.sprout.sync.GateDeviceStatus
import dev.kpetriv.sprout.testPause
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The recovery adapters' error mapping and the second sign-in over the app's "Sign in again" gate (ADR-0013). */
@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryAdaptersTest {
    private fun problemOf(failure: Throwable): Throwable? =
        Result.failure<Unit>(failure).mapCallFailure().mapForOnboarding().exceptionOrNull()

    /** [exception] as the callable answers it, through `HouseholdFunctions` and both mappings. */
    private suspend fun throughTheCallable(exception: FunctionsException): Throwable? {
        val functions = HouseholdFunctions(
            object : CallableInvoker {
                override suspend fun call(name: String, data: Map<String, Any?>, timeoutMs: Long): Any? =
                    throw exception
            },
        )
        val answer = functions.recoverParentDevice(ByteArray(32), DeviceId("a".repeat(64)), byteArrayOf(1))
        return answer.mapCallFailure().mapForOnboarding().exceptionOrNull()
    }

    @Test
    fun `every reason of the server becomes the problem onboarding acts on, and the rest a failure code`() = runTest {
        val reasons = mapOf(
            FunctionsException(FunctionsError.FailedPrecondition, mapOf("reason" to "recent-sign-in"))
                to RecoveryProblem.RecentSignIn,
            FunctionsException(FunctionsError.PermissionDenied, mapOf("reason" to "wrong-pin", "attemptsLeft" to 3L))
                to RecoveryProblem.WrongPin(3),
            FunctionsException(FunctionsError.FailedPrecondition, mapOf("reason" to "pin-unavailable"))
                to RecoveryProblem.PinUnavailable,
            FunctionsException(FunctionsError.FailedPrecondition, mapOf("reason" to "wait", "waitEndsAt" to 5L))
                to RecoveryProblem.Wait(Instant.fromEpochMilliseconds(5)),
            FunctionsException(FunctionsError.FailedPrecondition, mapOf("reason" to "other-parent"))
                to RecoveryProblem.OtherParent,
            FunctionsException(
                FunctionsError.FailedPrecondition,
                mapOf("reason" to "not-pending", "status" to "ACTIVE"),
            )
                to RecoveryProblem.NotPending(active = true),
            FunctionsException(
                FunctionsError.FailedPrecondition,
                mapOf("reason" to "not-pending", "status" to "REVOKED"),
            )
                to RecoveryProblem.NotPending(active = false),
        )
        for ((exception, problem) in reasons) {
            val mapped = throughTheCallable(exception)
            assertEquals(problem, assertIs<RecoveryException>(mapped).problem, exception.details.toString())
        }
        val unknownStatus = RecoveryRefusedException(RecoveryRefusal.NotPending(RecoveryDeviceStatus.OTHER))
        assertEquals(OnboardingFailure.UNKNOWN, assertIs<OnboardingException>(problemOf(unknownStatus)).failure)

        val failures = mapOf(
            FunctionsException(FunctionsError.Unavailable) to OnboardingFailure.NETWORK,
            FunctionsException(FunctionsError.ResourceExhausted) to OnboardingFailure.TOO_MANY_ATTEMPTS,
            FunctionsException(FunctionsError.PermissionDenied) to OnboardingFailure.UNKNOWN,
            FunctionsException(FunctionsError.FailedPrecondition, mapOf("reason" to "something-new")) to
                OnboardingFailure.UNKNOWN,
            FunctionsException(FunctionsError.Unauthenticated) to OnboardingFailure.UNKNOWN,
        )
        for ((exception, failure) in failures) {
            val mapped = throughTheCallable(exception)
            assertEquals(failure, assertIs<OnboardingException>(mapped).failure, exception.error.code)
        }
        // The callables' view of a plain failure: a SyncException with the code, never the server's text.
        val sync = Result.failure<Unit>(FunctionsException(FunctionsError.Unavailable)).mapCallFailure()
        assertEquals(SyncError.Transient(), assertIs<SyncException>(sync.exceptionOrNull()).error)
    }

    // ---- the second sign-in over CredentialGate ----

    private val sessions = FakeSessionStore(parentSession())
    private val context: Context = ContextWrapper(null)

    private val auth = object : GateAuth {
        override val usesEmailPassword: Boolean = false
        var state: AuthState = AuthState.SignedOut
        var signIn: Result<Unit> = Result.success(Unit)
        var signInAs: UserId = PARENT

        override suspend fun current(): AuthState = state

        override suspend fun refresh(): Result<HouseholdId?> = Result.success(REAL_HOUSEHOLD)

        override suspend fun signInWithGoogle(activityContext: Context): Result<Unit> {
            if (signIn.isSuccess) state = AuthState.SignedIn(signInAs, false, Claims.NONE)
            return signIn
        }

        override suspend fun signInWithEmailPassword(email: String, password: String): Result<Unit> = error("unused")

        override suspend fun reauthenticateWithGoogle(activityContext: Context): Result<Unit> = error("unused")

        override suspend fun reauthenticateWithEmailPassword(email: String, password: String): Result<Unit> =
            error("unused")

        override suspend fun signOut() {
            state = AuthState.SignedOut
        }
    }

    private var syncs = 0

    private fun TestScope.gate() = CredentialGate(
        scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        sessions = sessions,
        auth = auth,
        deviceStatus = { Result.success(GateDeviceStatus(DeviceStatusValue.ACTIVE, false)) },
        removals = { sessions.session.value = null },
        syncNow = { syncs += 1 },
        pause = testPause(),
    )

    @Test
    fun `the same account signing in lowers the gate, syncs once and ends the wait`() = runTest {
        val gate = gate()
        val signIn =
            async(UnconfinedTestDispatcher(testScheduler)) { CredentialGateSignIn(gate, sessions).signInAgain() }
        runCurrent()
        assertFalse(signIn.isCompleted, "the gate is up")
        gate.signInWithGoogle(context)
        runCurrent()
        assertTrue(signIn.await())
        assertEquals(1, syncs)
    }

    @Test
    fun `another account or a cancelled sheet keeps the gate up and the wait going`() = runTest {
        val gate = gate()
        val signIn =
            async(UnconfinedTestDispatcher(testScheduler)) { CredentialGateSignIn(gate, sessions).signInAgain() }
        auth.signInAs = UserId("someone-else")
        gate.signInWithGoogle(context)
        runCurrent()
        assertFalse(signIn.isCompleted, "another account is signed out again")
        auth.signIn = Result.failure(AuthException(AuthError.Cancelled))
        gate.signInWithGoogle(context)
        runCurrent()
        assertFalse(signIn.isCompleted, "a cancelled sheet changes nothing")
        assertEquals(0, syncs)

        auth.signInAs = PARENT
        auth.signIn = Result.success(Unit)
        gate.signInWithGoogle(context)
        runCurrent()
        assertTrue(signIn.await())
    }

    @Test
    fun `erasing the phone from the gate ends the wait without a session`() = runTest {
        val gate = gate()
        val signIn =
            async(UnconfinedTestDispatcher(testScheduler)) { CredentialGateSignIn(gate, sessions).signInAgain() }
        runCurrent()
        gate.eraseInstead()
        runCurrent()
        assertFalse(signIn.await())
    }
}

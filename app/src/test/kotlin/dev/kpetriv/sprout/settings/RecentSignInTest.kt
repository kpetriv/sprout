package dev.kpetriv.sprout.settings

import android.content.Context
import android.content.ContextWrapper
import dev.kpetriv.sprout.FakeSessionStore
import dev.kpetriv.sprout.PARENT
import dev.kpetriv.sprout.REAL_HOUSEHOLD
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.syncfirebase.AccountInfo
import dev.kpetriv.sprout.core.syncfirebase.AuthError
import dev.kpetriv.sprout.core.syncfirebase.AuthException
import dev.kpetriv.sprout.core.syncfirebase.AuthState
import dev.kpetriv.sprout.core.syncfirebase.Claims
import dev.kpetriv.sprout.feature.settings.AdminException
import dev.kpetriv.sprout.feature.settings.AdminFailure
import dev.kpetriv.sprout.parentSession
import dev.kpetriv.sprout.sync.GateAuth
import dev.kpetriv.sprout.testPause
import dev.kpetriv.sprout.trialSession
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The delete confirmation's "sign in again" re-authenticates the session's own account and never replaces it. */
class RecentSignInTest {
    private val context: Context = ContextWrapper(null)
    private val sessions = FakeSessionStore(parentSession())
    private val events = mutableListOf<String>()
    private val pause = testPause { events += "exclusive" }

    private inner class FakeAuth(override val usesEmailPassword: Boolean) : GateAuth {
        var state: AuthState = AuthState.SignedIn(PARENT, false, Claims(REAL_HOUSEHOLD, null, null))
        var reauth: Result<Unit> = Result.success(Unit)

        override suspend fun current(): AuthState = state

        override suspend fun refresh(): Result<HouseholdId?> = error("not used")

        override suspend fun signInWithGoogle(activityContext: Context): Result<Unit> {
            events += "signInWithGoogle"
            return Result.success(Unit)
        }

        override suspend fun signInWithEmailPassword(email: String, password: String): Result<Unit> {
            events += "signInWithEmailPassword"
            return Result.success(Unit)
        }

        override suspend fun reauthenticateWithGoogle(activityContext: Context): Result<Unit> {
            events += if (pause.isPaused) "reauth google (paused)" else "reauth google"
            return reauth
        }

        override suspend fun reauthenticateWithEmailPassword(email: String, password: String): Result<Unit> {
            events += if (pause.isPaused) "reauth password (paused)" else "reauth password"
            return reauth
        }

        override suspend fun signOut() {
            events += "signOut"
        }
    }

    private fun recentSignIn(auth: GateAuth) =
        RecentSignIn(sessions, auth, { AccountInfo("parent@example.test") }, pause)

    private fun Result<Unit>.failure(): AdminFailure? = (exceptionOrNull() as? AdminException)?.failure

    @Test
    fun `release re-authenticates the signed-in Google account under the sync pause`() = runTest {
        val result = recentSignIn(FakeAuth(usesEmailPassword = false)).signInAgain(context, password = null)
        assertTrue(result.isSuccess)
        assertEquals(listOf("exclusive", "reauth google (paused)"), events, "never a sign-in that replaces the user")
    }

    @Test
    fun `debug re-authenticates with the current account's address and the typed password`() = runTest {
        val auth = FakeAuth(usesEmailPassword = true)
        assertTrue(recentSignIn(auth).signInAgain(context, password = "secret1").isSuccess)
        assertEquals(listOf("exclusive", "reauth password (paused)"), events)
        assertEquals(AdminFailure.SIGN_IN_FAILED, recentSignIn(auth).signInAgain(context, password = "").failure())
    }

    @Test
    fun `another account is wrong account, and the session's user stays signed in`() = runTest {
        val auth = FakeAuth(usesEmailPassword = false)
        auth.reauth = Result.failure(AuthException(AuthError.UserMismatch))
        assertEquals(AdminFailure.WRONG_ACCOUNT, recentSignIn(auth).signInAgain(context, null).failure())
        assertTrue("signOut" !in events)
        assertEquals(PARENT, (auth.state as AuthState.SignedIn).uid)
    }

    @Test
    fun `network and other failures keep their meaning`() = runTest {
        val auth = FakeAuth(usesEmailPassword = false)
        auth.reauth = Result.failure(AuthException(AuthError.Network))
        assertEquals(AdminFailure.NETWORK, recentSignIn(auth).signInAgain(context, null).failure())
        auth.reauth = Result.failure(AuthException(AuthError.Cancelled))
        assertEquals(AdminFailure.SIGN_IN_FAILED, recentSignIn(auth).signInAgain(context, null).failure())
    }

    @Test
    fun `nothing is re-authenticated when the signed-in user is not the session's, or in the trial`() = runTest {
        val auth = FakeAuth(usesEmailPassword = false)
        auth.state = AuthState.SignedIn(UserId("someone-else"), false, Claims.NONE)
        assertEquals(AdminFailure.WRONG_ACCOUNT, recentSignIn(auth).signInAgain(context, null).failure())
        sessions.session.value = trialSession()
        assertEquals(AdminFailure.NOT_ALLOWED, recentSignIn(auth).signInAgain(context, null).failure())
        assertEquals(emptyList(), events)
    }
}

package dev.kpetriv.sprout.sync

import dev.kpetriv.sprout.FakeSessionStore
import dev.kpetriv.sprout.PARENT
import dev.kpetriv.sprout.REAL_HOUSEHOLD
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.syncfirebase.AuthState
import dev.kpetriv.sprout.core.syncfirebase.Claims
import dev.kpetriv.sprout.parentSession
import dev.kpetriv.sprout.trialSession
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionReconcilerTest {
    private fun signedIn(uid: UserId = PARENT, hid: HouseholdId? = REAL_HOUSEHOLD) =
        AuthState.SignedIn(uid, isAnonymous = false, claims = Claims(hid, Role.PARENT.takeIf { hid != null }, null))

    private var gone = 0

    private suspend fun reconcile(store: FakeSessionStore, state: AuthState) =
        SessionReconciler(store, { state }, { gone += 1 }).reconcile()

    @Test
    fun `a matching Firebase user keeps the session`() = runTest {
        val store = FakeSessionStore(parentSession())
        assertEquals(parentSession(), reconcile(store, signedIn()))
        assertEquals(0, store.clears)
    }

    @Test
    fun `a signed-out Firebase user clears the session`() = runTest {
        val store = FakeSessionStore(parentSession())
        assertNull(reconcile(store, AuthState.SignedOut))
        assertEquals(1, store.clears)
    }

    @Test
    fun `claims naming another household, or another user, clear the session`() = runTest {
        val store = FakeSessionStore(parentSession())
        assertNull(reconcile(store, signedIn(hid = HouseholdId("other"))))
        val second = FakeSessionStore(parentSession())
        assertNull(reconcile(second, signedIn(uid = UserId("someone-else"))))
    }

    @Test
    fun `missing claims (an expired token offline) keep the session`() = runTest {
        val store = FakeSessionStore(parentSession())
        assertEquals(parentSession(), reconcile(store, signedIn(hid = null)))
    }

    @Test
    fun `an account that is gone keeps the session and starts the wipe`() = runTest {
        val store = FakeSessionStore(parentSession())
        assertEquals(parentSession(), reconcile(store, AuthState.UserGone))
        assertEquals(1, gone)
        assertEquals(0, store.clears)
    }

    @Test
    fun `the trial session and no session are left alone`() = runTest {
        assertEquals(trialSession(), reconcile(FakeSessionStore(trialSession()), AuthState.SignedOut))
        assertNull(reconcile(FakeSessionStore(), AuthState.SignedOut))
    }
}

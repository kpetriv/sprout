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

    private val removals = mutableListOf<RemovalReason>()
    private var lost = 0

    private val credentials = object : CredentialCheck {
        override fun verify() = Unit

        override fun credentialsLost() {
            lost += 1
        }
    }

    private suspend fun reconcile(store: FakeSessionStore, state: AuthState) =
        SessionReconciler(store, { state }, { removals += it }, credentials).reconcile()

    @Test
    fun `a matching Firebase user keeps the session`() = runTest {
        val store = FakeSessionStore(parentSession())
        assertEquals(parentSession(), reconcile(store, signedIn()))
        assertEquals(emptyList(), removals)
        assertEquals(0, lost)
    }

    @Test
    fun `a signed-out Firebase user gates the session instead of opening it`() = runTest {
        val store = FakeSessionStore(parentSession())
        assertEquals(parentSession(), reconcile(store, AuthState.SignedOut))
        assertEquals(1, lost)
        assertEquals(emptyList(), removals, "the gate asks the registry first")
        assertEquals(0, store.clears)
    }

    @Test
    fun `claims naming another household, or another user, wipe this phone's copy`() = runTest {
        reconcile(FakeSessionStore(parentSession()), signedIn(hid = HouseholdId("other")))
        reconcile(FakeSessionStore(parentSession()), signedIn(uid = UserId("someone-else")))
        assertEquals(listOf(RemovalReason.SIGNED_OUT, RemovalReason.SIGNED_OUT), removals)
    }

    @Test
    fun `missing claims (an expired token offline) keep the session`() = runTest {
        val store = FakeSessionStore(parentSession())
        assertEquals(parentSession(), reconcile(store, signedIn(hid = null)))
        assertEquals(emptyList(), removals)
    }

    @Test
    fun `an account that is gone asks the registry through the gate before anything is wiped`() = runTest {
        val store = FakeSessionStore(parentSession())
        assertEquals(parentSession(), reconcile(store, AuthState.UserGone))
        assertEquals(1, lost, "the gate runs the token-less deviceStatus and wipes only on REVOKED or gone")
        assertEquals(emptyList(), removals)
        assertEquals(0, store.clears)
    }

    @Test
    fun `the trial session and no session are left alone`() = runTest {
        assertEquals(trialSession(), reconcile(FakeSessionStore(trialSession()), AuthState.SignedOut))
        assertNull(reconcile(FakeSessionStore(), AuthState.SignedOut))
        assertEquals(emptyList(), removals)
        assertEquals(0, lost)
    }
}

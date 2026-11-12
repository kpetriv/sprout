package dev.kpetriv.sprout.settings

import android.app.Application
import dev.kpetriv.sprout.FakeSessionStore
import dev.kpetriv.sprout.REAL_HOUSEHOLD
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.syncfirebase.AccountInfo
import dev.kpetriv.sprout.core.syncfirebase.AccountInfoReader
import dev.kpetriv.sprout.kidSession
import dev.kpetriv.sprout.parentSession
import dev.kpetriv.sprout.sync.HouseholdRemovals
import dev.kpetriv.sprout.sync.RemovalReason
import dev.kpetriv.sprout.trialSession
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings → Account reads the parent's e-mail on demand from the auth session, and only for a real parent; signing
 * out is a removal that erases this phone's copy (D-M3-47).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class) // Not the Hilt app: its startup opens the encrypted database.
class SessionAccountControlTest {
    private val removals = mutableListOf<RemovalReason>()
    private val auth = HouseholdRemovals { removals += it }

    private class FakeAccounts(var info: AccountInfo?) : AccountInfoReader {
        var reads = 0

        override suspend fun accountInfo(): AccountInfo? = info.also { reads++ }
    }

    @Test
    fun `a signed-in parent sees the account's e-mail, read on demand`() = runTest {
        val accounts = FakeAccounts(AccountInfo("parent@example.com"))
        val control = SessionAccountControl(FakeSessionStore(parentSession()), auth, accounts)

        val info = control.observe().first()

        assertEquals("parent@example.com", info.email)
        assertEquals(REAL_HOUSEHOLD, info.householdId)
        assertFalse(info.trial)
        assertEquals(1, accounts.reads)
        assertFalse("parent@example.com" in info.toString(), "the settings model hides it too")
    }

    @Test
    fun `a kid device and the trial household show no e-mail and never read one`() = runTest {
        val accounts = FakeAccounts(AccountInfo("someone@example.com"))

        val kid = SessionAccountControl(FakeSessionStore(kidSession()), auth, accounts).observe().first()
        val trial = SessionAccountControl(FakeSessionStore(trialSession()), auth, accounts).observe().first()

        assertNull(kid.email)
        assertNull(trial.email)
        assertEquals(true, trial.trial)
        assertEquals(0, accounts.reads)
    }

    @Test
    fun `an account without an address shows none`() = runTest {
        val control = SessionAccountControl(FakeSessionStore(parentSession()), auth, FakeAccounts(AccountInfo(null)))

        assertNull(control.observe().first().email)
    }

    @Test
    fun `signing out a real household erases this phone's copy, the trial has nothing to sign out of`() = runTest {
        SessionAccountControl(FakeSessionStore(parentSession()), auth, FakeAccounts(null)).signOut()
        assertEquals(listOf(RemovalReason.SIGNED_OUT), removals)

        SessionAccountControl(FakeSessionStore(trialSession()), auth, FakeAccounts(null)).signOut()
        SessionAccountControl(FakeSessionStore(), auth, FakeAccounts(null)).signOut()
        assertEquals(1, removals.size)
    }

    private fun flushing(session: Session, removal: HouseholdRemovals, flush: suspend () -> Unit) =
        SessionAccountControl(FakeSessionStore(session), removal, FakeAccounts(null), flushOutbox = flush)

    @Test
    fun `a sign-out tries one bounded sync first, and goes on whether it worked, failed or hung`() = runTest {
        val order = mutableListOf<String>()
        val removal = HouseholdRemovals { order += "remove:$it" }
        flushing(parentSession(), removal) { order += "flush" }.signOut()
        assertEquals(listOf("flush", "remove:SIGNED_OUT"), order, "the outbox goes out before the erase (D-REL-40)")

        order.clear()
        flushing(parentSession(), removal) { error("offline") }.signOut()
        flushing(parentSession(), removal) { awaitCancellation() }.signOut()
        assertEquals(listOf("remove:SIGNED_OUT", "remove:SIGNED_OUT"), order)
        assertTrue(testScheduler.currentTime <= SessionAccountControl.FLUSH_TIMEOUT_MS)

        order.clear()
        flushing(trialSession(), removal) { order += "flush" }.signOut()
        assertTrue(order.isEmpty(), "the trial has nothing to flush or sign out of")
    }
}

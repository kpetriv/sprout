package dev.kpetriv.sprout.household

import dev.kpetriv.sprout.FakeSessionStore
import dev.kpetriv.sprout.REAL_HOUSEHOLD
import dev.kpetriv.sprout.THIS_DEVICE
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.sync.FakeBackend
import dev.kpetriv.sprout.core.sync.FakeSyncTransport
import dev.kpetriv.sprout.core.sync.HouseholdRoot
import dev.kpetriv.sprout.core.sync.SyncReport
import dev.kpetriv.sprout.kidSession
import dev.kpetriv.sprout.parentSession
import dev.kpetriv.sprout.settings.InMemoryPolicies
import dev.kpetriv.sprout.trialSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [HouseholdPolicyRecovery] (D-REL-14, D-REL-22, D-REL-25): who may recover, the bounded check (audit L3), the one-use
 * currency that never outlives a failed or cancelled check (audit N1), the restore without a key (audit M1), and the
 * server read over the fake backend (audit N2).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HouseholdPolicyRecoveryTest {
    private val chf = Currency.Fiat("CHF")
    private val policies = InMemoryPolicies(Currency.Fiat("GBP"))
    private val sessions = FakeSessionStore(parentSession())
    private var syncs = 0
    private var syncNow: suspend () -> SyncReport? = { SyncReport() }
    private var root: suspend () -> HouseholdRoot = { HouseholdRoot("CHF", policyStored = false) }

    private val recovery = HouseholdPolicyRecovery(
        sessions = sessions,
        policies = policies,
        syncNow = {
            syncs++
            syncNow()
        },
        householdRoot = { root() },
    )

    @Test
    fun `a kid session never recovers the policy and never syncs for it`() = runTest {
        sessions.session.value = kidSession()

        assertEquals(PolicyReadiness.NotSynced, recovery.readiness())

        assertEquals(0, syncs, "no sync, no server read")
        assertNull(recovery.takeRecovered(REAL_HOUSEHOLD))
        assertFalse(recovery.restoreWithoutKey())
        assertNull(policies.policy.value)
    }

    @Test
    fun `the check gives up after its timeout and answers not synced`() = runTest {
        // Audit L3: one sync and two reads run before the PIN and the prompt, under the provisioner's lock.
        syncNow = {
            delay(60.seconds)
            SyncReport()
        }

        assertEquals(PolicyReadiness.NotSynced, recovery.readiness())

        assertEquals(READINESS_TIMEOUT.inWholeMilliseconds, testScheduler.currentTime)
        assertNull(recovery.takeRecovered(REAL_HOUSEHOLD))
    }

    @Test
    fun `a server read that hangs is bounded too`() = runTest {
        root = { awaitCancellation() }

        assertEquals(PolicyReadiness.NotSynced, recovery.readiness())
        assertEquals(20_000L, testScheduler.currentTime)
    }

    @Test
    fun `a failed check forgets the currency an earlier check found`() = runTest {
        // Audit N1: the one-use cache is reset at the top of every check.
        assertEquals(PolicyReadiness.Recoverable(chf), recovery.readiness())
        root = { error("offline") }

        assertEquals(PolicyReadiness.NotSynced, recovery.readiness())

        assertNull(recovery.takeRecovered(REAL_HOUSEHOLD), "no stale currency")
        assertFalse(recovery.restoreWithoutKey())
        assertNull(policies.policy.value)
    }

    @Test
    fun `a cancelled check forgets the currency an earlier check found`() = runTest {
        assertEquals(PolicyReadiness.Recoverable(chf), recovery.readiness())
        val hanging = CompletableDeferred<SyncReport>()
        syncNow = { hanging.await() }

        val check = async { recovery.readiness() }
        runCurrent()
        check.cancel()
        runCurrent()

        assertNull(recovery.takeRecovered(REAL_HOUSEHOLD))
    }

    @Test
    fun `the restore writes the policy with the server's currency and signatures off, once`() = runTest {
        // D-REL-22: a phone without a strong biometric, after Recoverable and the PIN.
        assertEquals(PolicyReadiness.Recoverable(chf), recovery.readiness())

        assertTrue(recovery.restoreWithoutKey())

        val policy = policies.policy.value
        assertEquals(chf, policy?.fiatCurrency, "the server's currency, never the locale's")
        assertEquals(false, policy?.requireParentSignature)
        assertEquals(1, policies.ensured)
        assertNull(recovery.takeRecovered(REAL_HOUSEHOLD), "the currency is used once")
        assertTrue(recovery.restoreWithoutKey(), "a retry finds the policy")
        assertEquals(1, policies.ensured)
    }

    @Test
    fun `the restore rechecks the session and a held policy`() = runTest {
        assertEquals(PolicyReadiness.Recoverable(chf), recovery.readiness())
        policies.held = true
        assertFalse(recovery.restoreWithoutKey(), "a pulled policy arrived for its author meanwhile")
        policies.held = false

        assertEquals(PolicyReadiness.Recoverable(chf), recovery.readiness())
        sessions.session.value = parentSession(status = RegistrationStatus.PENDING)
        assertFalse(recovery.restoreWithoutKey())

        sessions.session.value = trialSession()
        assertFalse(recovery.restoreWithoutKey(), "the trial creates its own policy")
        assertNull(policies.policy.value)
    }

    @Test
    fun `nothing is restored without a check that found the policy recoverable`() = runTest {
        assertFalse(recovery.restoreWithoutKey())
        assertNull(policies.policy.value)
    }

    @Test
    fun `over the fake backend a missing household root or a denied read is never recoverable`() = runTest {
        val backend = FakeBackend(REAL_HOUSEHOLD).apply { fiatCurrency = "CHF" }
        val transport = FakeSyncTransport(backend, THIS_DEVICE, UserId("parent-uid"), Role.PARENT)
        root = { transport.householdRoot() }
        assertEquals(PolicyReadiness.Recoverable(chf), recovery.readiness())

        backend.householdRootStored = false
        assertEquals(PolicyReadiness.NotSynced, recovery.readiness(), "no root document")
        backend.householdRootStored = true

        for (path in FakeBackend.RootPath.entries) {
            backend.householdRootDenied = path
            assertEquals(PolicyReadiness.NotSynced, recovery.readiness(), "denied on $path")
            assertNull(recovery.takeRecovered(REAL_HOUSEHOLD))
        }
        backend.householdRootDenied = null

        backend.fiatCurrency = null
        assertEquals(PolicyReadiness.NotSynced, recovery.readiness(), "a root without a currency")
    }
}

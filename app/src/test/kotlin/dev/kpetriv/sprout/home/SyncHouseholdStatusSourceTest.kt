package dev.kpetriv.sprout.home

import android.app.Application
import dev.kpetriv.sprout.FakeShellPreferences
import dev.kpetriv.sprout.core.crypto.FakeParentAuthorizer
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.database.HouseholdPolicyRepository
import dev.kpetriv.sprout.core.database.TestHousehold
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.sync.SyncError
import dev.kpetriv.sprout.core.sync.SyncReport
import dev.kpetriv.sprout.core.time.FakeClock
import dev.kpetriv.sprout.feature.lock.ParentKeyChangeProgress
import dev.kpetriv.sprout.feature.parenthome.HouseholdStatus
import dev.kpetriv.sprout.sync.CredentialCheck
import dev.kpetriv.sprout.sync.ForegroundState
import dev.kpetriv.sprout.sync.SyncController
import dev.kpetriv.sprout.sync.SyncReportProcessor
import dev.kpetriv.sprout.sync.SyncRunner
import dev.kpetriv.sprout.sync.SyncScheduler
import dev.kpetriv.sprout.testPause
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The dashboard's signing cards over a real Room household (D-M3-39, D-M3-46): hidden while a parent key change runs,
 * so the enrolment right after the PIN setup never flashes "signing is off" or "key not registered" (D-REL-12).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class) // Not the Hilt app: its startup opens the encrypted database.
class SyncHouseholdStatusSourceTest {
    private val household = TestHousehold(
        RuntimeEnvironment.getApplication(),
        sessionFor = TestHousehold.parentSession(HouseholdId("home-1"), "parent-uid"),
    )
    private val enrolling = MutableStateFlow(false)
    private val authorizer = FakeParentAuthorizer()

    /**
     * The server registration's pending flag as the test drives it (null: Room's own flow). Lets a test order the
     * progress drop and Room's late re-query of the audit row deterministically (D-REL-16).
     */
    private val pending = MutableStateFlow<Boolean?>(null)
    private val policies = object : HouseholdPolicyRepository by household.policies {
        override fun observeServerRegistrationPending(): Flow<Boolean> = pending.flatMapLatest { driven ->
            driven?.let(::flowOf) ?: household.policies.observeServerRegistrationPending()
        }
    }
    private val processor = SyncReportProcessor(
        auditor = { },
        sessions = household.sessions,
        planner = { },
        preferences = FakeShellPreferences(),
        removals = { },
        credentials = object : CredentialCheck {
            override fun verify() = Unit

            override fun credentialsLost() = Unit
        },
        clock = FakeClock(Instant.fromEpochSeconds(1_000)),
    )
    private val source = SyncHouseholdStatusSource(
        sessions = household.sessions,
        sync = idleSync(),
        ledger = household.ledger,
        outbox = household.outbox,
        database = household.database,
        kids = household.kids,
        policies = policies,
        keyChanges = ParentKeyChangeProgress { enrolling },
        authorizer = authorizer,
    )

    @After
    fun close() = household.close()

    private suspend fun status(): HouseholdStatus = source.observe().first()

    @Test
    fun `signing off shows only once no key change runs`() = runTest {
        household.policies.ensure(household.gbp)

        enrolling.value = true
        assertFalse(status().signingOff, "the enrolment's fingerprint prompt is over the dashboard")
        assertFalse(status().signingKeyNotRegistered)

        enrolling.value = false
        assertTrue(status().signingOff, "the enrolment ended without a key: cancelled, failed or unavailable")
    }

    @Test
    fun `key not registered shows only once the enrolment's server registration ended`() = runTest {
        household.policies.ensure(household.gbp)
        household.registerParentKey() // locally; the server call has not answered yet

        enrolling.value = true
        assertFalse(status().signingKeyNotRegistered, "the registerParentKey call is still running")
        assertFalse(status().signingOff)

        enrolling.value = false
        assertTrue(status().signingKeyNotRegistered, "the server registration failed")
        assertFalse(status().signingOff)
    }

    @Test
    fun `a parent phone without the household policy after a completed sync says so`() = runTest {
        assertFalse(status().policyMissing, "no sync has completed yet: nothing to say")

        processor.handle(SyncReport(error = SyncError.Transient()))
        assertFalse(status().policyMissing, "the sync failed: the policy may simply not have come yet")

        processor.handle(SyncReport())
        assertTrue(status().policyMissing, "D-REL-14: signatures are off and enrolment waits or recovers")
        assertFalse(status().signingOff)

        household.policies.ensure(household.gbp)
        assertFalse(status().policyMissing)
        assertTrue(status().signingOff)
    }

    @Test
    fun `without a strong biometric the missing policy card restores with the PIN`() = runTest {
        processor.handle(SyncReport())
        assertTrue(status().policyMissing)
        assertFalse(status().policyRestoreWithPin, "a strong biometric recovers it with fingerprint signing")

        authorizer.availability = ParentAuthAvailability.NOT_ENROLLED
        assertTrue(status().policyMissing)
        assertTrue(status().policyRestoreWithPin, "D-REL-22: Restore household settings")

        household.policies.ensure(household.gbp)
        assertFalse(status().policyMissing)
        assertFalse(status().policyRestoreWithPin)
    }

    @Test
    fun `signing off also shows when the policy does not require parent signatures`() = runTest {
        // D-REL-26: a key on this phone, but a policy written without signatures (before they became one-way).
        household.policies.ensure(household.gbp)
        household.registerParentKey()
        assertFalse(status().signingOff)

        val dao = household.database.householdPolicyDao()
        dao.upsert(checkNotNull(dao.get()).copy(requireParentSignature = false))

        assertTrue(status().signingOff)
    }

    @Test
    fun `the end of an enrolment never flashes the key-not-registered card`() = runTest {
        // D-REL-16 on virtual time, in the order that flashed: the progress drops first, Room's re-query of the audit
        // row lands 100 ms later. Without the hold the drop alone shows the card; every emission is recorded.
        household.registerParentKey() // locally; the server call is running
        pending.value = true
        enrolling.value = true
        val seen = CopyOnWriteArrayList<HouseholdStatus>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.observe().collect { seen += it } }
        // Room's first values arrive on its own threads.
        realTime { withTimeout(5_000) { while (seen.isEmpty()) delay(10) } }
        runCurrent()
        assertFalse(seen.last().signingKeyNotRegistered, "hidden while the enrolment runs")
        val before = seen.size

        enrolling.value = false
        runCurrent()
        advanceTimeBy(100)
        pending.value = false
        advanceTimeBy(1_000)
        runCurrent()

        val after = seen.drop(before)
        assertTrue(after.none { it.signingKeyNotRegistered }, "no frame shows the card: $after")
        assertTrue(after.none { it.signingOff })
        assertFalse(seen.last().signingKeyNotRegistered)
    }

    @Test
    fun `a registration that really failed shows the card once the hold has passed`() = runTest {
        household.registerParentKey()
        pending.value = true
        enrolling.value = true
        val seen = CopyOnWriteArrayList<HouseholdStatus>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.observe().collect { seen += it } }
        realTime { withTimeout(5_000) { while (seen.isEmpty()) delay(10) } }
        runCurrent()

        enrolling.value = false
        advanceTimeBy(400)
        runCurrent()
        assertFalse(seen.last().signingKeyNotRegistered, "still within the hold")
        advanceTimeBy(200)
        runCurrent()
        assertTrue(seen.last().signingKeyNotRegistered, "the registration failed: the card shows")
    }

    /** Runs [block] off the test scheduler, so its delays are real ones. */
    private suspend fun realTime(block: suspend () -> Unit) = withContext(Dispatchers.Default) { block() }

    private fun idleSync(): SyncController {
        val preferences = FakeShellPreferences()
        return SyncController(
            scope = CoroutineScope(Job()),
            sessions = household.sessions,
            runner = object : SyncRunner {
                override fun run(scope: CoroutineScope, trigger: Flow<Unit>): Job = Job()

                override suspend fun syncOnce(): SyncReport = SyncReport()

                override suspend fun <T> exclusive(block: suspend () -> T): T = block()
            },
            trigger = { emptyFlow() },
            reports = processor,
            scheduler = object : SyncScheduler {
                override fun schedulePeriodic() = Unit

                override fun requestExpedited() = Unit
            },
            preferences = preferences,
            foreground = object : ForegroundState {
                override fun isForeground(): Boolean = true

                override fun changes(): Flow<Boolean> = emptyFlow()
            },
            removals = { },
            pause = testPause(),
        )
    }
}

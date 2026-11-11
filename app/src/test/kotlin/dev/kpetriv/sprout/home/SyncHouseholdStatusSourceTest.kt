package dev.kpetriv.sprout.home

import android.app.Application
import dev.kpetriv.sprout.FakeShellPreferences
import dev.kpetriv.sprout.core.database.TestHousehold
import dev.kpetriv.sprout.core.domainledger.JvmSha256Hasher
import dev.kpetriv.sprout.core.domainledger.toHex
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class) // Not the Hilt app: its startup opens the encrypted database.
class SyncHouseholdStatusSourceTest {
    private val household = TestHousehold(
        RuntimeEnvironment.getApplication(),
        sessionFor = TestHousehold.parentSession(HouseholdId("home-1"), "parent-uid"),
    )
    private val enrolling = MutableStateFlow(false)
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
        policies = household.policies,
        keyChanges = ParentKeyChangeProgress { enrolling },
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
    fun `the end of an enrolment never flashes the key-not-registered card`() = runTest {
        // Room-backed and on real time (D-REL-16): the progress drops the moment the last write commits, while Room
        // re-runs the audit query on its own thread afterwards. Every emission is recorded, not a snapshot.
        val policy = household.registerParentKey() // locally; the server call is running
        val fingerprint = JvmSha256Hasher.sha256(checkNotNull(policy.parentPublicKey)).toHex()
        enrolling.value = true
        val seen = CopyOnWriteArrayList<HouseholdStatus>()
        val collector = launch(Dispatchers.Default) { source.observe().collect { seen += it } }
        realTime { withTimeout(5_000) { while (seen.isEmpty()) delay(10) } }
        assertFalse(seen.last().signingKeyNotRegistered, "hidden while the enrolment runs")
        val before = seen.size

        // The registration answered: recordServerRegisteredKey's audit row is the last write, then the progress
        // counter drops at once.
        assertTrue(household.policies.recordServerRegisteredKey(fingerprint, validFromLamport = 1))
        enrolling.value = false
        realTime { delay(1_500) }
        collector.cancelAndJoin()

        val after = seen.drop(before)
        assertTrue(after.none { it.signingKeyNotRegistered }, "no frame shows the card: $after")
        assertTrue(after.none { it.signingOff })
        assertFalse(seen.last().signingKeyNotRegistered)
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

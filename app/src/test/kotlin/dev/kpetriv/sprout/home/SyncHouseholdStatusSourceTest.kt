package dev.kpetriv.sprout.home

import android.app.Application
import dev.kpetriv.sprout.FakeShellPreferences
import dev.kpetriv.sprout.core.database.TestHousehold
import dev.kpetriv.sprout.core.model.HouseholdId
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
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

    private fun idleSync(): SyncController {
        val preferences = FakeShellPreferences()
        val processor = SyncReportProcessor(
            auditor = { },
            sessions = household.sessions,
            planner = { },
            preferences = preferences,
            removals = { },
            credentials = object : CredentialCheck {
                override fun verify() = Unit

                override fun credentialsLost() = Unit
            },
            clock = FakeClock(Instant.fromEpochSeconds(1_000)),
        )
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

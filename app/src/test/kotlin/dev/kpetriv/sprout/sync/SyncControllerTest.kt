package dev.kpetriv.sprout.sync

import dev.kpetriv.sprout.FakeSessionStore
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.sync.PushItemId
import dev.kpetriv.sprout.core.sync.PushRejection
import dev.kpetriv.sprout.core.sync.SyncError
import dev.kpetriv.sprout.core.sync.SyncReport
import dev.kpetriv.sprout.core.time.FakeClock
import dev.kpetriv.sprout.kidSession
import dev.kpetriv.sprout.parentSession
import dev.kpetriv.sprout.trialSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SyncControllerTest {
    private class FakeRunner : SyncRunner {
        override val reports = MutableStateFlow<SyncReport?>(null)
        var running = 0
        var started = 0
        var triggers = 0

        override fun run(scope: CoroutineScope, trigger: Flow<Unit>): Job = scope.launch {
            started += 1
            running += 1
            try {
                trigger.collect { triggers += 1 }
            } finally {
                running -= 1
            }
        }

        override suspend fun syncOnce(): SyncReport = SyncReport().also { reports.value = it }
    }

    private val sessions = FakeSessionStore()
    private val runner = FakeRunner()
    private val trigger = MutableSharedFlow<Unit>()
    private val audited = mutableListOf<PushRejection>()
    private var plans = 0
    private var periodic = 0
    private var expedited = 0
    private var foreground = true
    private val foregroundChanges = MutableSharedFlow<Boolean>()
    private var linked = false
    private val clock = FakeClock(Instant.fromEpochSeconds(1_000))

    private fun TestScope.controller() = SyncController(
        scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        sessions = sessions,
        runner = runner,
        trigger = { trigger },
        auditor = { audited += it },
        planner = { plans += 1 },
        scheduler = object : SyncScheduler {
            override fun schedulePeriodic() {
                periodic += 1
            }

            override fun requestExpedited() {
                expedited += 1
            }
        },
        preferences = object : ShellPreferences {
            override fun wasLinked(): Boolean = linked

            override fun markLinked() {
                linked = true
            }
        },
        foreground = object : ForegroundState {
            override fun isForeground(): Boolean = foreground

            override fun changes(): Flow<Boolean> = foregroundChanges
        },
        clock = clock,
    ).also { it.start() }

    @Test
    fun `nothing syncs without a session or for the trial household`() = runTest {
        controller()
        assertEquals(0, runner.started)
        sessions.session.value = trialSession()
        assertEquals(0, runner.started)
        assertEquals(0, periodic)
    }

    @Test
    fun `a real session runs the loop and the periodic worker, and a cleared session stops it`() = runTest {
        sessions.session.value = parentSession(RegistrationStatus.PENDING)
        controller()
        assertEquals(1, runner.running)
        assertEquals(1, periodic)
        assertEquals(true, linked, "a device that synced once never seeds the demo trial again")
        sessions.session.value = parentSession(RegistrationStatus.ACTIVE)
        assertEquals(1, runner.started, "an approval of the same device keeps the loop")
        sessions.session.value = null
        assertEquals(0, runner.running)
    }

    @Test
    fun `outbox triggers also enqueue an expedited worker while the app is in the background`() = runTest {
        sessions.session.value = parentSession()
        controller()
        trigger.emit(Unit)
        assertEquals(1, runner.triggers)
        assertEquals(0, expedited)
        foreground = false
        trigger.emit(Unit)
        assertEquals(1, expedited)
    }

    @Test
    fun `reports are audited and plan once after the first good sync`() = runTest {
        sessions.session.value = parentSession()
        val controller = controller()
        val refused = PushRejection(4, PushItemId("event/e1:abc"), "permission-denied", blocking = true)
        runner.reports.value = SyncReport(rejections = listOf(refused))
        assertEquals(listOf(refused), audited)
        assertEquals(clock.now(), controller.status.value.lastSyncedAt)
        assertEquals(1, plans)
        runner.reports.value = SyncReport(error = SyncError.Transient())
        assertEquals(true, controller.status.value.lastFailed)
        runner.reports.value = SyncReport()
        assertEquals(1, plans, "planning runs once per session")
    }

    @Test
    fun `an account that is gone asks for the wipe`() = runTest {
        val controller = controller()
        controller.householdGone()
        assertEquals(RemovalReason.HOUSEHOLD_GONE, controller.removal.value)
    }

    @Test
    fun `a failed first sync does not plan, and a kid's phone never plans`() = runTest {
        sessions.session.value = kidSession()
        controller()
        runner.reports.value = SyncReport()
        assertEquals(0, plans)
    }

    @Test
    fun `coming to the foreground asks for a sync`() = runTest {
        sessions.session.value = parentSession()
        controller()
        foregroundChanges.emit(false)
        assertEquals(0, runner.triggers)
        foregroundChanges.emit(true)
        assertEquals(1, runner.triggers)
    }

    @Test
    fun `household gone or a revoked session asks for the wipe`() = runTest {
        sessions.session.value = parentSession()
        val controller = controller()
        assertNull(controller.removal.value)
        runner.reports.value = SyncReport(householdGone = true, error = SyncError.HouseholdGone)
        assertEquals(RemovalReason.HOUSEHOLD_GONE, controller.removal.value)
    }

    @Test
    fun `a session mirrored as REVOKED stops sync and asks for the wipe`() = runTest {
        sessions.session.value = parentSession()
        val controller = controller()
        sessions.session.value = parentSession(RegistrationStatus.REVOKED)
        assertEquals(RemovalReason.DEVICE_REVOKED, controller.removal.value)
        assertEquals(0, runner.running)
    }

    @Test
    fun `syncNow returns the report`() = runTest {
        sessions.session.value = parentSession()
        val controller = controller()
        assertEquals(SyncReport(), controller.syncNow())
    }
}

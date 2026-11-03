package dev.kpetriv.sprout.sync

import dev.kpetriv.sprout.FakeSessionStore
import dev.kpetriv.sprout.FakeShellPreferences
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.sync.SyncReport
import dev.kpetriv.sprout.core.time.FakeClock
import dev.kpetriv.sprout.parentSession
import dev.kpetriv.sprout.trialSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SyncControllerTest {
    private class FakeRunner : SyncRunner {
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

        override suspend fun syncOnce(): SyncReport = SyncReport()
    }

    private val sessions = FakeSessionStore()
    private val runner = FakeRunner()
    private var trigger: Flow<Unit> = MutableSharedFlow()
    private val triggerEmitter get() = trigger as MutableSharedFlow<Unit>
    private val removals = mutableListOf<RemovalReason>()
    private var periodic = 0
    private var expedited = 0
    private var foreground = true
    private val foregroundChanges = MutableSharedFlow<Boolean>()
    private val preferences = FakeShellPreferences()
    private val clock = FakeClock(Instant.fromEpochSeconds(1_000))
    private val processor = SyncReportProcessor(
        auditor = { },
        sessions = sessions,
        planner = { },
        preferences = preferences,
        removals = { removals += it },
        credentials = object : CredentialCheck {
            override fun verify() = Unit

            override fun credentialsLost() = Unit
        },
        clock = clock,
    )

    private fun TestScope.controller() = SyncController(
        scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        sessions = sessions,
        runner = runner,
        trigger = { trigger },
        reports = processor,
        scheduler = object : SyncScheduler {
            override fun schedulePeriodic() {
                periodic += 1
            }

            override fun requestExpedited() {
                expedited += 1
            }
        },
        preferences = preferences,
        foreground = object : ForegroundState {
            override fun isForeground(): Boolean = foreground

            override fun changes(): Flow<Boolean> = foregroundChanges
        },
        removals = { removals += it },
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
        assertEquals(true, preferences.linked, "a device that synced once never seeds the demo trial again")
        assertEquals(
            ShellPreferences.digestOf(parentSession().householdId, parentSession().uid),
            preferences.linkedDigest,
            "the account the local rows belong to is recorded",
        )
        sessions.session.value = parentSession(RegistrationStatus.ACTIVE)
        assertEquals(1, runner.started, "an approval of the same device keeps the loop")
        sessions.session.value = null
        assertEquals(0, runner.running)
    }

    @Test
    fun `outbox triggers also enqueue an expedited worker while the app is in the background`() = runTest {
        sessions.session.value = parentSession()
        controller()
        triggerEmitter.emit(Unit)
        assertEquals(1, runner.triggers)
        assertEquals(0, expedited)
        foreground = false
        triggerEmitter.emit(Unit)
        assertEquals(1, expedited)
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
    fun `a session mirrored as REVOKED stops sync and reports the removal`() = runTest {
        sessions.session.value = parentSession()
        controller()
        sessions.session.value = parentSession(RegistrationStatus.REVOKED)
        assertEquals(listOf(RemovalReason.DEVICE_REVOKED), removals)
        assertEquals(0, runner.running)
    }

    @Test
    fun `a throwing trigger restarts the loop with a doubling delay`() = runTest {
        var subscriptions = 0
        trigger = kotlinx.coroutines.flow.flow {
            subscriptions += 1
            error("trigger failed")
        }
        sessions.session.value = parentSession()
        controller()
        assertEquals(1, subscriptions)

        advanceTimeBy(SyncController.BASE_RESTART_DELAY_MS + 1)
        assertEquals(2, subscriptions, "restarted after 1 s")
        advanceTimeBy(SyncController.BASE_RESTART_DELAY_MS + 1)
        assertEquals(2, subscriptions, "the next restart waits 2 s")
        advanceTimeBy(SyncController.BASE_RESTART_DELAY_MS)
        assertEquals(3, subscriptions)
        assertEquals(SyncController.MAX_RESTART_DELAY_MS, SyncController.restartDelayMs(30), "capped at 5 minutes")
    }

    @Test
    fun `stop ends the loop for good and syncNow does nothing after it`() = runTest {
        sessions.session.value = parentSession()
        val controller = controller()
        assertEquals(1, runner.running)
        controller.stop()
        assertEquals(0, runner.running)
        assertNull(controller.syncNow())
        sessions.session.value = parentSession(RegistrationStatus.PENDING)
        assertEquals(1, runner.started)
    }

    @Test
    fun `syncNow returns the report`() = runTest {
        sessions.session.value = parentSession()
        val controller = controller()
        assertEquals(SyncReport(), controller.syncNow())
    }
}

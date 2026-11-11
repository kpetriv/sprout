package dev.kpetriv.sprout.sync

import dev.kpetriv.sprout.FakeSessionStore
import dev.kpetriv.sprout.FakeShellPreferences
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.sync.SyncError
import dev.kpetriv.sprout.core.sync.SyncReport
import dev.kpetriv.sprout.core.time.FakeClock
import dev.kpetriv.sprout.kidSession
import dev.kpetriv.sprout.parentSession
import dev.kpetriv.sprout.testPause
import dev.kpetriv.sprout.trialSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RemovalHandlerTest {
    private val preferences = FakeShellPreferences().apply {
        linkedDigest = "digest"
        parentSetup = "setup"
    }
    private val steps = mutableListOf<String>()
    private var foreground = true
    private var erased = true
    private val keptIdentity = mutableListOf<Boolean>()
    private var session: Session? = parentSession()

    private fun TestScope.handler() = RemovalHandler(
        scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        preferences = preferences,
        stopSync = { steps += "stop sync" },
        work = { steps += "cancel work" },
        eraser = { keepDeviceIdentity ->
            steps += "erase"
            keptIdentity += keepDeviceIdentity
            erased
        },
        inForeground = { foreground },
        exitProcess = { steps += "exit" },
        blankWidgets = { steps += "blank widgets" },
        session = { session },
    )

    @Test
    fun `a removal is stored at once, then sync stops, the work is cancelled and the data erased`() = runTest {
        val handler = handler()
        handler.remove(RemovalReason.DEVICE_REVOKED)

        assertEquals(
            listOf("stop sync", "cancel work", "erase", "blank widgets"),
            steps,
            "in the foreground the shell shows it",
        )
        assertEquals(
            Removal(RemovalReason.DEVICE_REVOKED, wiped = true, keepDeviceIdentity = false),
            preferences.storedRemoval,
        )
        assertEquals(
            Removal(RemovalReason.DEVICE_REVOKED, wiped = true, keepDeviceIdentity = false),
            handler.state.value,
        )
        assertNull(preferences.linkedDigest, "no rows are left to belong to anyone")
        assertNull(preferences.parentSetup)
        assertEquals(1, preferences.wipes, "the first pull is forgotten and the digest key replaced")
    }

    @Test
    fun `a wipe that leaves something behind keeps the removal unwiped and runs again at the next start`() = runTest {
        erased = false
        preferences.firstPulls += dev.kpetriv.sprout.REAL_HOUSEHOLD
        val handler = handler()
        handler.remove(RemovalReason.HOUSEHOLD_GONE)

        assertEquals(
            Removal(RemovalReason.HOUSEHOLD_GONE, wiped = false, keepDeviceIdentity = false),
            preferences.storedRemoval,
        )
        assertEquals(
            Removal(RemovalReason.HOUSEHOLD_GONE, wiped = false, keepDeviceIdentity = false),
            handler.state.value,
        )
        assertTrue(handler.failed.value)
        assertEquals(0, preferences.wipes, "nothing is marked wiped")
        handler.acknowledge()
        assertEquals(
            Removal(RemovalReason.HOUSEHOLD_GONE, wiped = false, keepDeviceIdentity = false),
            preferences.storedRemoval,
            "kept",
        )

        // The next start: the wipe runs again, and this time it erases everything.
        steps.clear()
        erased = true
        val next = handler()
        assertTrue(next.resume())
        assertEquals(listOf("stop sync", "cancel work", "erase", "blank widgets"), steps)
        assertEquals(
            Removal(RemovalReason.HOUSEHOLD_GONE, wiped = true, keepDeviceIdentity = false),
            preferences.storedRemoval,
        )
        assertFalse(next.failed.value)
    }

    @Test
    fun `a failed wipe in the background still ends the process, so the next start retries`() = runTest {
        erased = false
        foreground = false
        handler().remove(RemovalReason.DEVICE_REVOKED)
        assertEquals(listOf("stop sync", "cancel work", "erase", "blank widgets", "exit"), steps)
        assertEquals(false, preferences.storedRemoval?.wiped)
    }

    @Test
    fun `a background removal draws the widget blank once, before the process exits`() = runTest {
        foreground = false
        var blanks = 0
        val handler = RemovalHandler(
            scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            preferences = preferences,
            stopSync = { },
            // The work cancel's debounced refresh never runs in a process that exits (audit 2 N1).
            work = { },
            eraser = { true },
            inForeground = { foreground },
            exitProcess = { steps += "exit after $blanks blank drawing(s)" },
            blankWidgets = { blanks += 1 },
        )

        handler.remove(RemovalReason.DEVICE_REVOKED)

        assertEquals(1, blanks)
        assertEquals(listOf("exit after 1 blank drawing(s)"), steps)
    }

    @Test
    fun `a widget that cannot be drawn never stops the wipe from ending the process`() = runTest {
        foreground = false
        val handler = RemovalHandler(
            scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            preferences = preferences,
            stopSync = { },
            work = { },
            eraser = { true },
            inForeground = { foreground },
            exitProcess = { steps += "exit" },
            blankWidgets = { error("no widget host") },
        )

        handler.remove(RemovalReason.HOUSEHOLD_GONE)

        assertEquals(listOf("exit"), steps)
        assertEquals(true, preferences.storedRemoval?.wiped)
    }

    @Test
    fun `only the sign-out of a parent session, ACTIVE or PENDING, keeps this phone's chat key (D-REL-23)`() = runTest {
        val sessions = listOf(
            parentSession() to true,
            parentSession(RegistrationStatus.PENDING) to true,
            parentSession(RegistrationStatus.REVOKED) to false,
            kidSession() to false,
            trialSession() to false,
            null to false,
        )
        for (reason in RemovalReason.entries) {
            for ((current, keeps) in sessions) {
                preferences.storedRemoval = null
                keptIdentity.clear()
                session = current
                handler().remove(reason)
                val expected = reason == RemovalReason.SIGNED_OUT && keeps
                assertEquals(listOf(expected), keptIdentity, "${reason.name} $current")
                assertEquals(expected, preferences.storedRemoval?.keepDeviceIdentity, "stored with the removal")
            }
        }
    }

    @Test
    fun `a resumed wipe uses the decision stored with the removal, not the session it finds then`() = runTest {
        // The process died after the session was erased: the stored answer still keeps the key.
        session = null
        preferences.storedRemoval = Removal(RemovalReason.SIGNED_OUT, keepDeviceIdentity = true)
        assertTrue(handler().resume())
        assertEquals(listOf(true), keptIdentity)

        keptIdentity.clear()
        session = parentSession()
        preferences.storedRemoval = Removal(RemovalReason.SIGNED_OUT, keepDeviceIdentity = false)
        assertTrue(handler().resume())
        assertEquals(listOf(false), keptIdentity)
    }

    @Test
    fun `a decision that cannot be stored keeps nothing`() = runTest {
        preferences.failSetRemoval = { it.keepDeviceIdentity != null }
        handler().remove(RemovalReason.SIGNED_OUT)
        assertEquals(listOf(false), keptIdentity)
    }

    @Test
    fun `the first reason wins and the wipe runs once`() = runTest {
        val handler = handler()
        handler.remove(RemovalReason.SIGNED_OUT)
        handler.remove(RemovalReason.HOUSEHOLD_GONE)
        assertEquals(RemovalReason.SIGNED_OUT, handler.state.value?.reason)
        assertEquals(1, steps.count { it == "erase" })
    }

    @Test
    fun `a wipe interrupted by a process death resumes at the next start`() = runTest {
        preferences.storedRemoval = Removal(RemovalReason.HOUSEHOLD_GONE)
        val handler = handler()
        assertTrue(handler.isRemoving)
        assertTrue(handler.resume())
        assertEquals(listOf("stop sync", "cancel work", "erase", "blank widgets"), steps)
        assertEquals(true, preferences.storedRemoval?.wiped)

        steps.clear()
        assertTrue(handler().resume(), "still shown until acknowledged")
        assertEquals(emptyList(), steps, "a finished wipe is not run again")
    }

    @Test
    fun `nothing to resume without a stored removal, and acknowledging forgets a finished one`() = runTest {
        val handler = handler()
        assertFalse(handler.resume())
        handler.acknowledge()
        handler.remove(RemovalReason.SIGNED_OUT)
        handler.acknowledge()
        assertNull(preferences.storedRemoval)
    }

    @Test
    fun `a removal found by the background worker wipes without any UI and ends the process`() = runTest {
        foreground = false
        val handler = handler()
        val processor = SyncReportProcessor(
            auditor = { },
            sessions = FakeSessionStore(parentSession()),
            planner = { },
            preferences = preferences,
            removals = handler,
            credentials = object : CredentialCheck {
                override fun verify() = Unit

                override fun credentialsLost() = Unit
            },
            clock = FakeClock(),
        )
        // The engine hands every report to the processor, the worker's included.
        val runner = object : SyncRunner {
            override fun run(scope: CoroutineScope, trigger: Flow<Unit>): Job = error("not used")

            override suspend fun syncOnce(): SyncReport =
                SyncReport(householdGone = true, error = SyncError.HouseholdGone).also { processor.handle(it) }

            override suspend fun <T> exclusive(block: suspend () -> T): T = block()
        }

        val outcome = SessionBackgroundSync(FakeSessionStore(parentSession()), runner, testPause()).syncOnce()

        assertEquals(BackgroundSyncOutcome.DONE, outcome)
        assertEquals(listOf("stop sync", "cancel work", "erase", "blank widgets", "exit"), steps)
        assertEquals(
            Removal(RemovalReason.HOUSEHOLD_GONE, wiped = true, keepDeviceIdentity = false),
            preferences.storedRemoval,
        )
    }
}

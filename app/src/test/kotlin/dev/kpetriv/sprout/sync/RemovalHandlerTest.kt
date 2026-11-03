package dev.kpetriv.sprout.sync

import dev.kpetriv.sprout.FakeSessionStore
import dev.kpetriv.sprout.FakeShellPreferences
import dev.kpetriv.sprout.core.sync.SyncError
import dev.kpetriv.sprout.core.sync.SyncReport
import dev.kpetriv.sprout.core.time.FakeClock
import dev.kpetriv.sprout.parentSession
import dev.kpetriv.sprout.testPause
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

    private fun TestScope.handler() = RemovalHandler(
        scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        preferences = preferences,
        stopSync = { steps += "stop sync" },
        work = { steps += "cancel work" },
        eraser = {
            steps += "erase"
            erased
        },
        inForeground = { foreground },
        exitProcess = { steps += "exit" },
    )

    @Test
    fun `a removal is stored at once, then sync stops, the work is cancelled and the data erased`() = runTest {
        val handler = handler()
        handler.remove(RemovalReason.DEVICE_REVOKED)

        assertEquals(listOf("stop sync", "cancel work", "erase"), steps, "in the foreground the shell shows it")
        assertEquals(Removal(RemovalReason.DEVICE_REVOKED, wiped = true), preferences.storedRemoval)
        assertEquals(Removal(RemovalReason.DEVICE_REVOKED, wiped = true), handler.state.value)
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

        assertEquals(Removal(RemovalReason.HOUSEHOLD_GONE, wiped = false), preferences.storedRemoval)
        assertEquals(Removal(RemovalReason.HOUSEHOLD_GONE, wiped = false), handler.state.value)
        assertTrue(handler.failed.value)
        assertEquals(0, preferences.wipes, "nothing is marked wiped")
        handler.acknowledge()
        assertEquals(Removal(RemovalReason.HOUSEHOLD_GONE, wiped = false), preferences.storedRemoval, "kept")

        // The next start: the wipe runs again, and this time it erases everything.
        steps.clear()
        erased = true
        val next = handler()
        assertTrue(next.resume())
        assertEquals(listOf("stop sync", "cancel work", "erase"), steps)
        assertEquals(Removal(RemovalReason.HOUSEHOLD_GONE, wiped = true), preferences.storedRemoval)
        assertFalse(next.failed.value)
    }

    @Test
    fun `a failed wipe in the background still ends the process, so the next start retries`() = runTest {
        erased = false
        foreground = false
        handler().remove(RemovalReason.DEVICE_REVOKED)
        assertEquals(listOf("stop sync", "cancel work", "erase", "exit"), steps)
        assertEquals(false, preferences.storedRemoval?.wiped)
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
        assertEquals(listOf("stop sync", "cancel work", "erase"), steps)
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
        assertEquals(listOf("stop sync", "cancel work", "erase", "exit"), steps)
        assertEquals(Removal(RemovalReason.HOUSEHOLD_GONE, wiped = true), preferences.storedRemoval)
    }
}

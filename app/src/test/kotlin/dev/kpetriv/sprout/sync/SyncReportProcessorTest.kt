package dev.kpetriv.sprout.sync

import dev.kpetriv.sprout.FakeSessionStore
import dev.kpetriv.sprout.FakeShellPreferences
import dev.kpetriv.sprout.REAL_HOUSEHOLD
import dev.kpetriv.sprout.core.database.ChatPruneScheduler
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.sync.PushItemId
import dev.kpetriv.sprout.core.sync.PushRejection
import dev.kpetriv.sprout.core.sync.SyncError
import dev.kpetriv.sprout.core.sync.SyncReport
import dev.kpetriv.sprout.core.time.FakeClock
import dev.kpetriv.sprout.kidSession
import dev.kpetriv.sprout.parentSession
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class SyncReportProcessorTest {
    private val sessions = FakeSessionStore(parentSession())
    private val audited = mutableListOf<PushRejection>()
    private val removals = mutableListOf<RemovalReason>()
    private var plans = 0
    private var verifications = 0
    private val preferences = FakeShellPreferences()
    private val clock = FakeClock(Instant.fromEpochSeconds(1_000))
    private var upkeeps = 0
    private var upkeepFailure: Exception? = null
    private var pruneRequests = 0
    private var widgetRefreshes = 0
    private val processor = SyncReportProcessor(
        auditor = { audited += it },
        sessions = sessions,
        planner = { plans += 1 },
        preferences = preferences,
        removals = { removals += it },
        credentials = object : CredentialCheck {
            override fun verify() {
                verifications += 1
            }

            override fun credentialsLost() = Unit
        },
        clock = clock,
        upkeep = {
            upkeeps += 1
            upkeepFailure?.let { throw it }
        },
        prunes = object : ChatPruneScheduler {
            override fun scheduleDaily() = Unit

            override fun pruneSoon() {
                pruneRequests += 1
            }
        },
        widgets = { widgetRefreshes += 1 },
    )

    private fun refused(seq: Long) = PushRejection(seq, PushItemId("event/e$seq:abc"), "permission-denied", true)

    @Test
    fun `two back-to-back reports are both audited`() = runTest {
        processor.handle(SyncReport(rejections = listOf(refused(4))))
        processor.handle(SyncReport(rejections = listOf(refused(5)), error = SyncError.Permanent("permission-denied")))
        assertEquals(listOf(refused(4), refused(5)), audited)
        assertEquals(clock.now(), processor.status.value.lastSyncedAt)
        assertTrue(processor.status.value.lastFailed)
    }

    @Test
    fun `the first good sync of an ACTIVE parent records the first pull and plans once per process`() = runTest {
        processor.handle(SyncReport(error = SyncError.Transient()))
        assertEquals(0, plans, "a failed sync does not plan")
        processor.handle(SyncReport())
        assertEquals(setOf(REAL_HOUSEHOLD), preferences.firstPulls)
        assertEquals(1, plans)
        processor.handle(SyncReport())
        assertEquals(1, plans)
    }

    @Test
    fun `a kid's or a PENDING phone never records a first pull or plans`() = runTest {
        sessions.session.value = kidSession()
        processor.handle(SyncReport())
        sessions.session.value = parentSession(RegistrationStatus.PENDING)
        processor.handle(SyncReport())
        assertEquals(0, plans)
        assertEquals(emptySet(), preferences.firstPulls)
    }

    @Test
    fun `the M4 upkeep runs after every successful sync only, for any session, and may fail quietly`() = runTest {
        processor.handle(SyncReport(error = SyncError.Transient()))
        assertEquals(0, upkeeps)
        processor.handle(SyncReport())
        sessions.session.value = kidSession()
        processor.handle(SyncReport())
        assertEquals(2, upkeeps)
        upkeepFailure = IllegalStateException("thread key store")
        processor.handle(SyncReport())
        assertEquals(3, upkeeps)
        assertEquals(clock.now(), processor.status.value.lastSyncedAt)
    }

    @Test
    fun `household gone is a removal, and an unauthenticated sync checks the credentials`() = runTest {
        processor.handle(SyncReport(householdGone = true, error = SyncError.HouseholdGone))
        assertEquals(listOf(RemovalReason.HOUSEHOLD_GONE), removals)
        processor.handle(SyncReport(error = SyncError.Unauthenticated))
        assertEquals(1, verifications)
        assertEquals(0, plans)
    }

    @Test
    fun `a failing auditor does not stop the rest`() = runTest {
        val failing = SyncReportProcessor(
            auditor = { error("store closed") },
            sessions = sessions,
            planner = { plans += 1 },
            preferences = preferences,
            removals = { removals += it },
            credentials = object : CredentialCheck {
                override fun verify() = Unit

                override fun credentialsLost() = Unit
            },
            clock = clock,
        )
        failing.handle(SyncReport(rejections = listOf(refused(1))))
        assertEquals(1, plans)
    }

    @Test
    fun `every successful sync asks for the chat prune and a widget refresh, even after a failed upkeep`() = runTest {
        upkeepFailure = IllegalStateException("upkeep")
        processor.handle(SyncReport())
        processor.handle(SyncReport(error = SyncError.Transient()))
        processor.handle(SyncReport(householdGone = true))
        assertEquals(1, pruneRequests)
        assertEquals(1, widgetRefreshes)
    }
}

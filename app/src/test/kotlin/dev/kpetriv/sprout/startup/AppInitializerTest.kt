package dev.kpetriv.sprout.startup

import dagger.Lazy
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.parentSession
import dev.kpetriv.sprout.trialSession
import dev.kpetriv.sprout.work.PlanScheduler
import dev.kpetriv.sprout.work.ScheduledActionsPlanner
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.security.GeneralSecurityException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppInitializerTest {
    private val scope = TestScope(StandardTestDispatcher())
    private val steps = mutableListOf<String>()
    private var photoCacheClears = 0
    private var planFailure: Exception? = null
    private val planner = Lazy<ScheduledActionsPlanner> {
        ScheduledActionsPlanner {
            steps += "plan"
            planFailure?.let { throw it }
        }
    }
    private val scheduler = Lazy<PlanScheduler> { PlanScheduler { steps += "schedule" } }

    private fun initializer(seed: (suspend () -> Unit)? = null, quarantine: suspend () -> Unit = { steps += "check" }) =
        AppInitializer(
            scope,
            seed,
            quarantine,
            planner,
            scheduler,
            clearPhotoCache = { photoCacheClears += 1 },
            // The trial session exists once seeded (or always, without seeding).
            reconcileSession = { trialSession().takeIf { seed == null || "seed" in steps } },
        )

    private var removing = false
    private var storedRemoval = false

    private fun withSession(
        session: Session?,
        linked: Boolean = false,
        firebase: () -> Unit = { steps += "firebase" },
        seed: (suspend () -> Unit)? = { steps += "seed" },
        reconciled: () -> Unit = {},
        quarantine: suspend () -> Unit = { steps += "check" },
    ) = AppInitializer(
        scope = scope,
        seedDemoHousehold = seed,
        quarantineLedger = quarantine,
        planner = planner,
        scheduler = scheduler,
        initialiseFirebase = firebase,
        reconcileSession = {
            steps += "session"
            reconciled()
            session
        },
        wasLinked = { linked },
        startSync = { steps += "sync" },
        resumeRemoval = {
            if (storedRemoval) steps += "resume wipe"
            storedRemoval
        },
        removing = { removing || storedRemoval },
        blankWidgets = { steps += "blank widget" },
        checkKeptChatKey = {
            keptKeyChecks += 1
            keptKeyCheck()
        },
        resumeChatSeed = {
            steps += "chat seed"
            chatSeed(it)
        },
    )

    private var chatSeed: suspend (Session) -> Unit = {}

    private var keptKeyChecks = 0
    private var keptKeyCheck: suspend () -> Unit = {}

    @Test
    fun `only a start without a session checks a chat key a sign-out kept, and its failure never fails startup`() =
        scope.runTest {
            keptKeyCheck = { error("offline") }
            withSession(null, linked = true).apply {
                start()
                advanceUntilIdle()
                assertEquals(StartupState.Ready, state.value)
            }
            assertEquals(1, keptKeyChecks)
            assertEquals(listOf("firebase", "session", "check", "schedule", "sync"), steps)

            keptKeyChecks = 0
            withSession(parentSession()).apply {
                start()
                advanceUntilIdle()
            }
            assertEquals(0, keptKeyChecks, "a session owns the key: nothing to check (D-REL-28)")
        }

    @Test
    fun `a real session resumes the chat seeding before sync, and its failure never fails startup`() = scope.runTest {
        val seeded = mutableListOf<Session>()
        chatSeed = {
            seeded += it
            error("disk")
        }
        withSession(parentSession()).apply {
            start()
            advanceUntilIdle()
            assertEquals(StartupState.Ready, state.value)
        }
        assertEquals(listOf(parentSession()), seeded, "D-REL-33: a record a process death left is used")
        assertTrue(steps.indexOf("chat seed") in 0 until steps.indexOf("sync"))

        seeded.clear()
        withSession(null, linked = true).apply {
            start()
            advanceUntilIdle()
        }
        assertTrue(seeded.isEmpty(), "no session: the kept key is checked instead")
    }

    @Test
    fun `a real household's session is not seeded and plans only after its first pull, then sync starts`() =
        scope.runTest {
            withSession(parentSession()).apply {
                start()
                advanceUntilIdle()
                assertEquals(StartupState.Ready, state.value)
            }
            assertEquals(listOf("firebase", "session", "chat seed", "check", "schedule", "sync"), steps)
        }

    @Test
    fun `the M4 upkeep runs after the ledger check and before sync, and its failure never fails startup`() =
        scope.runTest {
            fun withChat(chat: suspend () -> Unit) = AppInitializer(
                scope = scope,
                seedDemoHousehold = null,
                quarantineLedger = { steps += "check" },
                planner = planner,
                scheduler = scheduler,
                reconcileSession = { parentSession() },
                startSync = { steps += "sync" },
                chatStartup = chat,
            )
            withChat { steps += "chat" }.apply {
                start()
                advanceUntilIdle()
                assertEquals(StartupState.Ready, state.value)
            }
            assertEquals(listOf("check", "chat", "schedule", "sync"), steps)

            steps.clear()
            withChat { throw GeneralSecurityException("encryption key store unavailable") }.apply {
                start()
                advanceUntilIdle()
                assertEquals(StartupState.Ready, state.value)
            }
            assertEquals(listOf("check", "schedule", "sync"), steps)
        }

    @Test
    fun `the trial session is not seeded again and still plans at start`() = scope.runTest {
        withSession(trialSession()).apply {
            start()
            advanceUntilIdle()
        }
        assertEquals(listOf("firebase", "session", "check", "plan", "schedule", "sync"), steps)
    }

    @Test
    fun `a device that was linked once (signed out or wiped) is never seeded and plans nothing`() = scope.runTest {
        withSession(null, linked = true).apply {
            start()
            advanceUntilIdle()
        }
        assertEquals(listOf("firebase", "session", "check", "schedule", "sync"), steps)
    }

    @Test
    fun `a removal stored by an earlier process resumes its wipe and skips every other step`() = scope.runTest {
        storedRemoval = true
        withSession(parentSession()).apply {
            start()
            advanceUntilIdle()
            assertEquals(StartupState.Ready, state.value)
        }
        assertEquals(listOf("blank widget", "firebase", "resume wipe"), steps, "the widget first (audit 2 N1)")
    }

    @Test
    fun `a stored removal blanks the widget even when that fails, and a start without one never does`() =
        scope.runTest {
            storedRemoval = true
            AppInitializer(
                scope = scope,
                seedDemoHousehold = null,
                quarantineLedger = { },
                planner = planner,
                scheduler = scheduler,
                resumeRemoval = {
                    steps += "resume wipe"
                    true
                },
                removing = { true },
                blankWidgets = { error("no widget host") },
            ).apply {
                start()
                advanceUntilIdle()
                assertEquals(StartupState.Ready, state.value)
            }
            assertEquals(listOf("resume wipe"), steps)

            steps.clear()
            storedRemoval = false
            withSession(parentSession()).apply {
                start()
                advanceUntilIdle()
            }
            assertFalse("blank widget" in steps)
        }

    @Test
    fun `a removal found while reconciling skips the rest and still ends Ready`() = scope.runTest {
        withSession(parentSession(), reconciled = { removing = true }).apply {
            start()
            advanceUntilIdle()
            assertEquals(StartupState.Ready, state.value)
        }
        assertEquals(listOf("firebase", "session"), steps)
    }

    @Test
    fun `a step that fails because a removal closed the database never ends in Failed`() = scope.runTest {
        withSession(
            parentSession(),
            quarantine = {
                removing = true
                throw android.database.SQLException("database closed by the wipe")
            },
        ).apply {
            start()
            advanceUntilIdle()
            assertEquals(StartupState.Ready, state.value)
        }
        assertEquals(listOf("firebase", "session", "chat seed"), steps)
    }

    @Test
    fun `a fresh debug device seeds the trial and re-reads the session`() = scope.runTest {
        withSession(null).apply {
            start()
            advanceUntilIdle()
        }
        assertEquals(listOf("firebase", "session", "seed", "session", "check", "schedule", "sync"), steps)
    }

    @Test
    fun `a Firebase start-up failure never stops the app from opening`() = scope.runTest {
        withSession(parentSession(), firebase = { error("no google play services") }).apply {
            start()
            advanceUntilIdle()
            assertEquals(StartupState.Ready, state.value)
        }
        assertEquals(listOf("session", "chat seed", "check", "schedule", "sync"), steps)
    }

    @Test
    fun `a failure becomes a failed state instead of a crash, and retry runs the work again`() = scope.runTest {
        var attempts = 0
        val initializer = initializer(quarantine = {
            attempts += 1
            throw GeneralSecurityException("keystore unavailable")
        })
        assertEquals(StartupState.Running, initializer.state.value)

        initializer.start()
        initializer.start()
        advanceUntilIdle()
        assertEquals(StartupState.Failed(StartupFailure.KEYS), initializer.state.value)
        assertEquals(1, attempts)

        initializer.retry()
        assertEquals(StartupState.Running, initializer.state.value)
        advanceUntilIdle()
        assertEquals(StartupState.Failed(StartupFailure.KEYS), initializer.state.value)
        assertEquals(2, attempts)
        assertEquals(emptyList(), steps, "nothing is planned or scheduled before the ledger check passed")
    }

    @Test
    fun `failures are classified by cause`() = scope.runTest {
        initializer(quarantine = { throw java.io.IOException("open failed", android.database.SQLException("disk")) })
            .apply {
                start()
                advanceUntilIdle()
                assertEquals(StartupState.Failed(StartupFailure.DATABASE), state.value)
            }
        initializer(quarantine = { error("boom") }).apply {
            start()
            advanceUntilIdle()
            assertEquals(StartupState.Failed(StartupFailure.UNKNOWN), state.value)
        }
    }

    @Test
    fun `retry before a failure does nothing`() = scope.runTest {
        val initializer = initializer()
        initializer.retry()
        advanceUntilIdle()
        assertEquals(emptyList(), steps)
        assertEquals(StartupState.Running, initializer.state.value)
    }

    @Test
    fun `seeds, checks the ledger, plans once and schedules the daily planner, in that order`() = scope.runTest {
        val initializer = initializer(seed = { steps += "seed" })
        initializer.start()
        advanceUntilIdle()
        assertEquals(StartupState.Ready, initializer.state.value)
        assertEquals(listOf("seed", "check", "plan", "schedule"), steps)
        assertEquals(1, photoCacheClears, "the plaintext capture cache is cleared at every start")
    }

    @Test
    fun `release builds never seed`() = scope.runTest {
        initializer(seed = null).apply {
            start()
            advanceUntilIdle()
        }
        assertEquals(listOf("check", "plan", "schedule"), steps)
    }

    @Test
    fun `a failing first plan does not fail startup and the daily planner is still scheduled`() = scope.runTest {
        planFailure = android.database.SQLException("locked")
        val initializer = initializer()
        initializer.start()
        advanceUntilIdle()
        assertEquals(StartupState.Ready, initializer.state.value)
        assertEquals(listOf("check", "plan", "schedule"), steps)
    }

    @Test
    fun `a failure to clear the photo cache does not fail startup`() = scope.runTest {
        val initializer = AppInitializer(
            scope,
            null,
            { steps += "check" },
            planner,
            scheduler,
            clearPhotoCache = { throw SecurityException("cache unavailable") },
            reconcileSession = { trialSession() },
        )
        initializer.start()
        advanceUntilIdle()
        assertEquals(StartupState.Ready, initializer.state.value)
        assertEquals(listOf("check", "plan", "schedule"), steps)
    }

    @Test
    fun `voice temp files are swept first at every start, and a failing sweep does not fail startup`() = scope.runTest {
        var sweeps = 0
        val initializer = AppInitializer(
            scope,
            null,
            { steps += "check" },
            planner,
            scheduler,
            reconcileSession = { trialSession() },
            sweepVoiceTempFiles = {
                sweeps += 1
                steps += "sweep"
                throw SecurityException("cache unavailable")
            },
        )
        initializer.start()
        advanceUntilIdle()
        assertEquals(StartupState.Ready, initializer.state.value)
        assertEquals(1, sweeps)
        assertEquals(listOf("sweep", "check", "plan", "schedule"), steps)
    }

    @Test
    fun `the AI status is read once per process, never fails startup and is not repeated by a retry`() = scope.runTest {
        var reads = 0
        var failure: Exception? = IllegalStateException("AICore not answering")
        var quarantineFailure: Exception? = GeneralSecurityException("keys")
        AppInitializer(
            scope = scope,
            seedDemoHousehold = null,
            quarantineLedger = { quarantineFailure?.let { throw it } },
            planner = planner,
            scheduler = scheduler,
            reconcileSession = { parentSession() },
            refreshAiStatus = {
                reads += 1
                failure?.let { throw it }
            },
        ).apply {
            start()
            advanceUntilIdle()
            assertEquals(StartupState.Failed(StartupFailure.KEYS), state.value)
            assertEquals(1, reads)

            failure = null
            quarantineFailure = null
            retry()
            start()
            advanceUntilIdle()
            assertEquals(StartupState.Ready, state.value)
            assertEquals(1, reads)
        }
    }
}

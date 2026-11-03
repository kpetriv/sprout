package dev.kpetriv.sprout.startup

import dagger.Lazy
import dev.kpetriv.sprout.work.PlanScheduler
import dev.kpetriv.sprout.work.ScheduledActionsPlanner
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.security.GeneralSecurityException
import kotlin.test.assertEquals

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
        AppInitializer(scope, seed, quarantine, planner, scheduler) { photoCacheClears += 1 }

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
        val initializer = AppInitializer(scope, null, { steps += "check" }, planner, scheduler) {
            throw SecurityException("cache unavailable")
        }
        initializer.start()
        advanceUntilIdle()
        assertEquals(StartupState.Ready, initializer.state.value)
        assertEquals(listOf("check", "plan", "schedule"), steps)
    }
}

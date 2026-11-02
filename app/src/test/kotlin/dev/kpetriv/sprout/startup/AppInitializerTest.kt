package dev.kpetriv.sprout.startup

import dagger.Lazy
import dev.kpetriv.sprout.core.database.HouseholdBootstrap
import dev.kpetriv.sprout.core.database.RoomLedgerRepository
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.security.GeneralSecurityException
import kotlin.test.assertEquals
import kotlin.test.fail

class AppInitializerTest {
    private val scope = TestScope(StandardTestDispatcher())
    private val neverSeed = Lazy<HouseholdBootstrap> { fail("release builds must not seed the demo household") }

    @Test
    fun `a failure becomes a failed state instead of a crash, and retry runs the work again`() = scope.runTest {
        var attempts = 0
        val ledger = Lazy<RoomLedgerRepository> {
            attempts += 1
            throw GeneralSecurityException("keystore unavailable")
        }
        val initializer = AppInitializer(scope, neverSeed, ledger, seedDemoHousehold = false)
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
    }

    @Test
    fun `failures are classified by cause`() = scope.runTest {
        val database = Lazy<RoomLedgerRepository> {
            throw java.io.IOException("open failed", android.database.SQLException("disk I/O"))
        }
        AppInitializer(scope, neverSeed, database, seedDemoHousehold = false).apply {
            start()
            advanceUntilIdle()
            assertEquals(StartupState.Failed(StartupFailure.DATABASE), state.value)
        }
        val other = Lazy<RoomLedgerRepository> { error("boom") }
        AppInitializer(scope, neverSeed, other, seedDemoHousehold = false).apply {
            start()
            advanceUntilIdle()
            assertEquals(StartupState.Failed(StartupFailure.UNKNOWN), state.value)
        }
    }

    @Test
    fun `retry before a failure does nothing`() = scope.runTest {
        var attempts = 0
        val ledger = Lazy<RoomLedgerRepository> {
            attempts += 1
            error("boom")
        }
        val initializer = AppInitializer(scope, neverSeed, ledger, seedDemoHousehold = false)
        initializer.retry()
        advanceUntilIdle()
        assertEquals(0, attempts)
        assertEquals(StartupState.Running, initializer.state.value)
    }
}

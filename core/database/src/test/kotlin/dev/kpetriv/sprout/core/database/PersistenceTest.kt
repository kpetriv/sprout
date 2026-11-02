package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.DeviceWriter
import dev.kpetriv.sprout.core.domainledger.Fixtures
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals

/** Head hash and lamport come from storage, so they survive new repository instances and a database reopen. */
@RunWith(RobolectricTestRunner::class)
class PersistenceTest {
    @Test
    fun headHashAndLamportSurviveReopen() = runTest {
        val name = "persistence-test.db"
        RuntimeEnvironment.getApplication().deleteDatabase(name)
        val parent = DeviceWriter(Fixtures.parentPhone)
        val events = listOf(parent.chore(100), parent.allowance(50))

        fileDatabase(name).apply {
            register(Fixtures.accounts)
            val repository = multiDeviceRepository(this)
            events.forEach { assertEquals(AppendResult.Accepted, repository.append(it)) }
            close()
        }

        fileDatabase(name).apply {
            val repository = multiDeviceRepository(this)
            assertEquals(events.last().envelope.hash, repository.headHash(Fixtures.parentPhone.deviceId))
            assertEquals(2, repository.latestLamport())
            // The next event from the same device links to the stored head.
            assertEquals(AppendResult.Accepted, repository.append(parent.chore(10)))
            close()
        }
    }
}

package dev.kpetriv.sprout.chat

import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.kidSession
import dev.kpetriv.sprout.parentSession
import dev.kpetriv.sprout.trialSession
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class ChatMaintenanceTest {
    private val scope = TestScope(StandardTestDispatcher())
    private val steps = mutableListOf<String>()
    private var session: Session? = parentSession()
    private var registration = KeyRegistration.REGISTERED
    private var threadKeyFailure: Exception? = null
    private var reconciled = emptyList<KidId>()
    private var endedElsewhere = emptyList<KidId>()
    private var photosWaiting = false

    private val maintenance = ChatMaintenance(
        scope = scope,
        session = { session },
        registerKey = {
            steps += "register"
            registration
        },
        maintainThreadKeys = {
            steps += "thread keys"
            threadKeyFailure?.let { throw it }
        },
        reconcileTimeBank = {
            steps += "reconcile"
            reconciled
        },
        timeBankEnded = { steps += "ended ${it.value}" },
        endedElsewhere = { endedElsewhere },
        sweepOrphans = { steps += "sweep" },
        photosWaiting = { photosWaiting },
        uploadPhotos = { steps += "upload" },
        requestSync = { steps += "sync" },
    )

    @Test
    fun `after a parent's sync the key is checked, thread keys kept up, time bank reconciled, photos uploaded`() =
        scope.runTest {
            photosWaiting = true
            reconciled = listOf(KidId("kid-leo"))
            endedElsewhere = listOf(KidId("kid-mia"))
            maintenance.afterSync()
            assertEquals(
                listOf("register", "thread keys", "reconcile", "ended kid-leo", "ended kid-mia", "upload"),
                steps,
            )
        }

    @Test
    fun `a kid's phone never touches thread keys, and a key sent now asks for a sync`() = scope.runTest {
        session = kidSession()
        registration = KeyRegistration.SENT
        maintenance.afterSync()
        assertEquals(listOf("register", "sync", "reconcile"), steps)
    }

    @Test
    fun `a failing step does not stop the others`() = scope.runTest {
        threadKeyFailure = IllegalStateException("no key pair")
        maintenance.afterSync()
        assertEquals(listOf("register", "thread keys", "reconcile"), steps)
    }

    @Test
    fun `the trial household and a pending phone get no upkeep after a sync`() = scope.runTest {
        for (current in listOf(trialSession(), parentSession(RegistrationStatus.PENDING), null)) {
            session = current
            maintenance.afterSync()
        }
        assertEquals(emptyList(), steps)
    }

    @Test
    fun `at start-up the local steps run at once and the key registration in the background`() = scope.runTest {
        photosWaiting = true
        maintenance.atStartup()
        assertEquals(listOf("sweep", "reconcile", "thread keys", "upload"), steps)
        advanceUntilIdle()
        assertEquals(listOf("sweep", "reconcile", "thread keys", "upload", "register"), steps)
    }

    @Test
    fun `the trial household only sweeps and reconciles at start-up`() = scope.runTest {
        session = trialSession()
        maintenance.atStartup()
        advanceUntilIdle()
        assertEquals(listOf("sweep", "reconcile"), steps)
    }
}

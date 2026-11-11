package dev.kpetriv.sprout.chat

import android.app.Application
import dev.kpetriv.sprout.core.crypto.InMemoryBlobStorage
import dev.kpetriv.sprout.core.crypto.InMemoryKeyWrapper
import dev.kpetriv.sprout.core.crypto.JceX25519KeyAgreement
import dev.kpetriv.sprout.core.crypto.SecretStoreEncryptionKeyStore
import dev.kpetriv.sprout.core.crypto.WrappedSecretStore
import dev.kpetriv.sprout.core.database.ChatIdentityKeeper
import dev.kpetriv.sprout.core.database.RoomSessionStore
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.SproutDatabase
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.database.inMemoryDatabase
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.syncfirebase.DeviceStatusAnswer
import dev.kpetriv.sprout.core.syncfirebase.DeviceStatusValue
import dev.kpetriv.sprout.core.syncfirebase.FunctionsError
import dev.kpetriv.sprout.core.syncfirebase.FunctionsException
import dev.kpetriv.sprout.core.time.FakeClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The start-up check of a chat key a sign-out kept (D-REL-28). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class) // Not the Hilt app: its startup opens the encrypted database.
class KeptChatKeyCheckTest {
    private val database: SproutDatabase = inMemoryDatabase(RuntimeEnvironment.getApplication())
    private val store =
        WrappedSecretStore(InMemoryKeyWrapper(), InMemoryBlobStorage(), SecureRandom(), Dispatchers.Unconfined)
    private val identity = ChatIdentityKeeper(database, store)
    private val device = DeviceId("d".repeat(64))
    private val home = HouseholdId("home")
    private var answer: Result<DeviceStatusAnswer> = Result.success(DeviceStatusAnswer(DeviceStatusValue.ACTIVE, false))
    private val asked = mutableListOf<Pair<HouseholdId, DeviceId>>()

    private fun check(timeoutMs: Long = KeptChatKeyCheck.TIMEOUT_MS, status: (suspend () -> Unit)? = null) =
        KeptChatKeyCheck(
            identity = identity,
            currentDevice = { device },
            status = { householdId, deviceId ->
                asked += householdId to deviceId
                status?.invoke()
                answer
            },
            timeoutMs = timeoutMs,
        )

    @After
    fun close() = database.close()

    /** What a parent's sign-out wipe leaves: the key and its record. */
    private suspend fun keptBySignOut() {
        SecretStoreEncryptionKeyStore(store, JceX25519KeyAgreement(SecureRandom())).publicKey()
        val sessions = RoomSessionStore(database, FakeClock())
        sessions.save(
            Session(
                UserId("p"),
                home,
                Role.PARENT,
                null,
                device,
                RegistrationStatus.ACTIVE,
                Instant.fromEpochSeconds(0),
            ),
        )
        assertTrue(identity.keepForSignOut())
        sessions.clear()
    }

    @Test
    fun `a device still in the household keeps its key, and no answer keeps it too`() = runTest {
        keptBySignOut()
        assertEquals(KeptChatKeyCheck.Outcome(liveForgotten = false, stashDropped = false), check()())
        assertEquals(listOf(home to device), asked)

        answer = Result.failure(FunctionsException(FunctionsError.Unavailable))
        check()()
        check(timeoutMs = 10) { awaitCancellation() }()
        assertTrue(identity.hasKey())
        assertNotNull(identity.kept())
    }

    @Test
    fun `a revoked or unknown device, or a household that is gone, loses its key`() = runTest {
        for (gone in listOf(
            Result.success(DeviceStatusAnswer(DeviceStatusValue.REVOKED, false)),
            Result.success(DeviceStatusAnswer(DeviceStatusValue.UNKNOWN, false)),
            Result.success(DeviceStatusAnswer(DeviceStatusValue.UNKNOWN, true)),
            Result.failure(FunctionsException(FunctionsError.PermissionDenied)),
        )) {
            keptBySignOut()
            answer = gone
            assertTrue(check()().liveForgotten, gone.toString())
            assertFalse(identity.hasKey())
            assertNull(identity.kept())
        }
    }

    @Test
    fun `a stash of a removed device is dropped as well, and no record means nothing is asked`() = runTest {
        check()()
        assertTrue(asked.isEmpty())

        keptBySignOut()
        identity.stash(device)
        answer = Result.success(DeviceStatusAnswer(DeviceStatusValue.REVOKED, false))
        assertEquals(KeptChatKeyCheck.Outcome(liveForgotten = false, stashDropped = true), check()())
        assertNull(identity.stashed())
    }
}

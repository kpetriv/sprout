package dev.kpetriv.sprout.chat

import dev.kpetriv.sprout.REAL_HOUSEHOLD
import dev.kpetriv.sprout.THIS_DEVICE
import dev.kpetriv.sprout.core.crypto.X25519PublicKey
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.syncfirebase.FunctionsError
import dev.kpetriv.sprout.core.syncfirebase.FunctionsException
import dev.kpetriv.sprout.kidSession
import dev.kpetriv.sprout.parentSession
import dev.kpetriv.sprout.trialSession
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EncryptionKeyRegistrarTest {
    private val localKey = X25519PublicKey(ByteArray(32) { 7 })
    private var session: Session? = parentSession()
    private var mirrored: ByteArray? = null
    private var stored: X25519PublicKey? = null
    private val bound = mutableListOf<Triple<HouseholdId, DeviceId, X25519PublicKey>>()
    private val sent = mutableListOf<Pair<ByteArray, ByteArray>>()
    private var answer: Result<Boolean> = Result.success(true)

    private val registrar = EncryptionKeyRegistrar(
        session = { session },
        mirroredKey = { device -> mirrored.takeIf { device == THIS_DEVICE } },
        localKey = { create -> stored ?: localKey.takeIf { create }?.also { stored = it } },
        bind = { hid, device, key ->
            bound += Triple(hid, device, key)
            byteArrayOf(1, 2, 3)
        },
        register = { key, binding ->
            sent += key to binding
            answer
        },
    )

    @Test
    fun `a device the mirror shows without a key creates, binds and registers it`() = runTest {
        assertEquals(KeyRegistration.SENT, registrar.ensureRegistered())
        assertEquals(listOf(Triple(REAL_HOUSEHOLD, THIS_DEVICE, localKey)), bound)
        val (key, binding) = sent.single()
        assertContentEquals(localKey.bytes, key)
        assertContentEquals(byteArrayOf(1, 2, 3), binding)
    }

    @Test
    fun `kid devices register too, and a key the server already had is not sent again as new`() = runTest {
        session = kidSession()
        answer = Result.success(false)
        assertEquals(KeyRegistration.ALREADY_SENT, registrar.ensureRegistered())
        assertEquals(1, sent.size)
    }

    @Test
    fun `a mirrored key that is this device's needs nothing, another one is a conflict (D-M4-14)`() = runTest {
        stored = localKey
        mirrored = localKey.bytes
        assertEquals(KeyRegistration.REGISTERED, registrar.ensureRegistered())
        mirrored = ByteArray(32) { 9 }
        assertEquals(KeyRegistration.CONFLICT, registrar.ensureRegistered())
        stored = null
        mirrored = localKey.bytes
        assertEquals(KeyRegistration.CONFLICT, registrar.ensureRegistered(), "this device lost its key")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `the server refusing another key is a conflict, any other failure is retried later`() = runTest {
        answer = Result.failure(FunctionsException(FunctionsError.Unavailable))
        assertEquals(KeyRegistration.FAILED, registrar.ensureRegistered())
        answer = Result.failure(FunctionsException(FunctionsError.FailedPrecondition))
        assertEquals(KeyRegistration.CONFLICT, registrar.ensureRegistered())
        assertEquals(KeyRegistration.CONFLICT, registrar.ensureRegistered())
        assertEquals(2, sent.size, "a refused key is not sent again in this process")
    }

    @Test
    fun `the trial household, a pending phone and no session register nothing`() = runTest {
        for (current in listOf(trialSession(), parentSession(RegistrationStatus.PENDING), null)) {
            session = current
            assertEquals(KeyRegistration.NOT_NEEDED, registrar.ensureRegistered())
        }
        assertTrue(sent.isEmpty())
    }
}

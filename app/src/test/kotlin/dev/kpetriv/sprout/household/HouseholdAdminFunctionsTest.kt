package dev.kpetriv.sprout.household

import dev.kpetriv.sprout.FakeSessionStore
import dev.kpetriv.sprout.REAL_HOUSEHOLD
import dev.kpetriv.sprout.THIS_DEVICE
import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.crypto.InMemoryDeviceKeyStore
import dev.kpetriv.sprout.core.database.HouseholdPolicy
import dev.kpetriv.sprout.core.database.HouseholdPolicyRepository
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.syncfirebase.CallableInvoker
import dev.kpetriv.sprout.core.syncfirebase.DeviceProof
import dev.kpetriv.sprout.core.syncfirebase.FunctionsError
import dev.kpetriv.sprout.core.syncfirebase.FunctionsException
import dev.kpetriv.sprout.core.syncfirebase.HouseholdFunctions
import dev.kpetriv.sprout.core.time.FakeClock
import dev.kpetriv.sprout.parentSession
import dev.kpetriv.sprout.trialSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.security.Signature
import kotlin.io.encoding.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HouseholdAdminFunctionsTest {
    private val keys = InMemoryDeviceKeyStore(FakeClock())
    private val signer = DeviceSigner(keys, Dispatchers.Unconfined)
    private val sessions = FakeSessionStore(parentSession())
    private val policies = RecordingPolicies()

    private class RecordingInvoker(private val response: Any?) : CallableInvoker {
        val calls = mutableListOf<Pair<String, Map<String, Any?>>>()
        var failure: FunctionsException? = null

        override suspend fun call(name: String, data: Map<String, Any?>, timeoutMs: Long): Any? {
            calls += name to data
            failure?.let { throw it }
            return response
        }
    }

    /** Only [recordServerRegisteredKey] is exercised. */
    private class RecordingPolicies : HouseholdPolicyRepository {
        val recorded = mutableListOf<Pair<String, Long>>()

        override fun observe(): Flow<HouseholdPolicy?> = flowOf(null)

        override suspend fun get(): HouseholdPolicy? = null

        override suspend fun ensure(fiatCurrency: Currency.Fiat): HouseholdPolicy = error("not used")

        override suspend fun registerParentKey(publicKeyDer: ByteArray): HouseholdPolicy = error("not used")

        override suspend fun recordServerRegisteredKey(fingerprint: String, validFromLamport: Long): Boolean {
            recorded += fingerprint to validFromLamport
            return true
        }
    }

    private fun admin(invoker: CallableInvoker, store: FakeSessionStore = sessions) =
        HouseholdAdminFunctions(HouseholdFunctions(invoker), signer, store, policies)

    private fun verifies(payload: ByteArray, signatureBase64: Any?): Boolean =
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(keys.publicKey().toPublicKey())
            update(payload)
            verify(Base64.Default.decode(signatureBase64 as String))
        }

    @Test
    fun `createLinkCode carries this device and its signature over the core DeviceProof payload`() = runTest {
        val invoker = RecordingInvoker(mapOf("code" to "K7QM2X9P", "expiresAt" to 1_793_700_600_000L))
        val code = admin(invoker).createLinkCode(KidId("kid-leo")).getOrThrow()
        assertEquals("K7QM2X9P", code.code)
        assertFalse("K7QM" in code.toString())
        val (name, data) = invoker.calls.single()
        assertEquals(HouseholdFunctions.CREATE_LINK_CODE, name)
        assertEquals(THIS_DEVICE.value, data["callerDeviceId"])
        assertTrue(verifies(DeviceProof.createLinkCodePayload(REAL_HOUSEHOLD, KidId("kid-leo")), data["signature"]))
    }

    @Test
    fun `revoke, export and delete sign their own payloads`() = runTest {
        val target = DeviceId("e".repeat(64))
        val invoker = RecordingInvoker(mapOf("deviceId" to target.value, "revokedAtLamport" to 12L, "deleted" to true))
        val functions = admin(invoker)
        functions.revokeDevice(target)
        functions.exportHousehold()
        functions.deleteHousehold()
        val (revoke, export, delete) = invoker.calls
        assertTrue(verifies(DeviceProof.revokeDevicePayload(REAL_HOUSEHOLD, target), revoke.second["signature"]))
        assertTrue(verifies(DeviceProof.exportHouseholdPayload(REAL_HOUSEHOLD), export.second["signature"]))
        assertTrue(verifies(DeviceProof.deleteHouseholdPayload(REAL_HOUSEHOLD), delete.second["signature"]))
        assertEquals(REAL_HOUSEHOLD.value, delete.second["confirmation"])
    }

    @Test
    fun `registerParentKey signs the key and records the server's registration`() = runTest {
        val key = byteArrayOf(1, 2, 3, 4)
        val invoker = RecordingInvoker(mapOf("fingerprint" to "f".repeat(64), "validFromLamport" to 42L))
        admin(invoker).registerParentKey(key).getOrThrow()
        val data = invoker.calls.single().second
        assertEquals(THIS_DEVICE.value, data["deviceId"])
        assertTrue(verifies(DeviceProof.registerParentKeyPayload(REAL_HOUSEHOLD, key), data["signature"]))
        assertEquals(listOf("f".repeat(64) to 42L), policies.recorded)
    }

    @Test
    fun `failures are codes, and the trial household cannot call`() = runTest {
        val invoker = RecordingInvoker(null).apply { failure = FunctionsException(FunctionsError.ResourceExhausted) }
        val failed = admin(invoker).createLinkCode(KidId("k")).exceptionOrNull() as FunctionsException
        assertEquals(FunctionsError.ResourceExhausted, failed.error)
        val trial = admin(invoker, FakeSessionStore(trialSession())).exportHousehold()
        assertEquals(FunctionsError.Unauthenticated, (trial.exceptionOrNull() as FunctionsException).error)
        assertEquals(1, invoker.calls.size, "the trial household never reaches the backend")
        assertTrue(policies.recorded.isEmpty())
    }
}

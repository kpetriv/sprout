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
import dev.kpetriv.sprout.core.syncfirebase.DeviceStatusAnswer
import dev.kpetriv.sprout.core.syncfirebase.DeviceStatusValue
import dev.kpetriv.sprout.core.syncfirebase.FunctionsError
import dev.kpetriv.sprout.core.syncfirebase.FunctionsException
import dev.kpetriv.sprout.core.syncfirebase.HouseholdFunctions
import dev.kpetriv.sprout.core.syncfirebase.IdTokenHashSource
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
    private val tokens = FakeIdTokens()

    /** Hands out the hash of token 1, 2, 3, ... (one fresh token per proof, as the real refresh does). */
    private class FakeIdTokens : IdTokenHashSource {
        var issued = 0
        var failure: FunctionsException? = null

        fun hashOf(token: Int): ByteArray = DeviceProof.idTokenHash("token-$token")

        override suspend fun currentIdTokenHash(): ByteArray {
            failure?.let { throw it }
            issued++
            return hashOf(issued)
        }
    }

    private class RecordingInvoker(private val response: Any?) : CallableInvoker {
        val calls = mutableListOf<Pair<String, Map<String, Any?>>>()
        val withoutAuth = mutableListOf<String>()
        var failure: FunctionsException? = null

        /** Failures for the next calls, in order, before [failure] applies. */
        val failures = ArrayDeque<FunctionsException>()

        override suspend fun call(name: String, data: Map<String, Any?>, timeoutMs: Long): Any? {
            calls += name to data
            failures.removeFirstOrNull()?.let { throw it }
            failure?.let { throw it }
            return response
        }

        override suspend fun callWithoutAuth(name: String, data: Map<String, Any?>, timeoutMs: Long): Any? {
            withoutAuth += name
            return call(name, data, timeoutMs)
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
        HouseholdAdminFunctions(HouseholdFunctions(invoker), signer, store, policies, tokens)

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
        val payload = DeviceProof.createLinkCodePayload(REAL_HOUSEHOLD, tokens.hashOf(1), KidId("kid-leo"))
        assertTrue(verifies(payload, data["signature"]), "bound to the token the call carries")
    }

    @Test
    fun `approve, revoke, export and delete sign their own payloads over a fresh token each`() = runTest {
        val target = DeviceId("e".repeat(64))
        val invoker = RecordingInvoker(
            mapOf("deviceId" to target.value, "revokedAtLamport" to 12L, "deleted" to true, "status" to "ACTIVE"),
        )
        val functions = admin(invoker)
        functions.approveDevice(target).getOrThrow()
        functions.revokeDevice(target).getOrThrow()
        functions.exportHousehold().getOrThrow()
        functions.deleteHousehold().getOrThrow()
        val (approve, revoke, export) = invoker.calls
        val delete = invoker.calls[3]
        assertEquals(HouseholdFunctions.APPROVE_DEVICE, approve.first)
        assertEquals(target.value, approve.second["deviceId"])
        assertEquals(THIS_DEVICE.value, approve.second["approverDeviceId"])
        val approval = DeviceProof.approveDevicePayload(REAL_HOUSEHOLD, tokens.hashOf(1), target)
        assertTrue(verifies(approval, approve.second["signature"]))
        val revocation = DeviceProof.revokeDevicePayload(REAL_HOUSEHOLD, tokens.hashOf(2), target)
        assertTrue(verifies(revocation, revoke.second["signature"]))
        val exported = DeviceProof.exportHouseholdPayload(REAL_HOUSEHOLD, tokens.hashOf(3))
        assertTrue(verifies(exported, export.second["signature"]))
        val deletion = DeviceProof.deleteHouseholdPayload(REAL_HOUSEHOLD, tokens.hashOf(4))
        assertTrue(verifies(deletion, delete.second["signature"]))
        assertEquals(REAL_HOUSEHOLD.value, delete.second["confirmation"])
        assertTrue(invoker.withoutAuth.isEmpty(), "every proof call carries the ID token")
    }

    @Test
    fun `a proof refused with permission-denied is signed over a fresh token and sent once more`() = runTest {
        val invoker = RecordingInvoker(mapOf("deleted" to true))
        invoker.failures += FunctionsException(FunctionsError.PermissionDenied)
        admin(invoker).deleteHousehold().getOrThrow()
        val (first, second) = invoker.calls
        val deletion = { token: Int -> DeviceProof.deleteHouseholdPayload(REAL_HOUSEHOLD, tokens.hashOf(token)) }
        assertTrue(verifies(deletion(1), first.second["signature"]))
        assertTrue(verifies(deletion(2), second.second["signature"]))

        // A second refusal is final; other failures are not retried.
        invoker.calls.clear()
        invoker.failure = FunctionsException(FunctionsError.PermissionDenied)
        val denied = admin(invoker).exportHousehold().exceptionOrNull() as FunctionsException
        assertEquals(FunctionsError.PermissionDenied, denied.error)
        assertEquals(2, invoker.calls.size)
        invoker.calls.clear()
        invoker.failure = FunctionsException(FunctionsError.FailedPrecondition)
        val stale = admin(invoker).deleteHousehold().exceptionOrNull() as FunctionsException
        assertEquals(FunctionsError.FailedPrecondition, stale.error, "deleteHousehold: sign in again")
        assertEquals(1, invoker.calls.size)
    }

    @Test
    fun `without a token there is no call`() = runTest {
        val invoker = RecordingInvoker(mapOf("deleted" to true))
        tokens.failure = FunctionsException(FunctionsError.Unavailable)
        val failure = admin(invoker).deleteHousehold().exceptionOrNull() as FunctionsException
        assertEquals(FunctionsError.Unavailable, failure.error)
        assertTrue(invoker.calls.isEmpty())
    }

    @Test
    fun `deviceStatus signs the session's household and device and needs no ID token`() = runTest {
        val invoker = RecordingInvoker(mapOf("status" to "REVOKED", "householdGone" to false))
        tokens.failure = FunctionsException(FunctionsError.Unauthenticated) // signed out, or the user is disabled
        val answer = admin(invoker).deviceStatus().getOrThrow()
        assertEquals(DeviceStatusAnswer(DeviceStatusValue.REVOKED, householdGone = false), answer)
        assertEquals(listOf(HouseholdFunctions.DEVICE_STATUS), invoker.withoutAuth)
        val data = invoker.calls.single().second
        assertEquals(REAL_HOUSEHOLD.value, data["hid"])
        assertEquals(THIS_DEVICE.value, data["deviceId"])
        assertTrue(verifies(DeviceProof.deviceStatusPayload(REAL_HOUSEHOLD, THIS_DEVICE), data["signature"]))
        assertEquals(0, tokens.issued)
    }

    @Test
    fun `registerParentKey signs the key, sends its attestation chain and records the server's registration`() =
        runTest {
            val key = byteArrayOf(1, 2, 3, 4)
            val chain = listOf(byteArrayOf(9, 8), byteArrayOf(7))
            val invoker = RecordingInvoker(mapOf("fingerprint" to "f".repeat(64), "validFromLamport" to 42L))
            admin(invoker).registerParentKey(key, chain).getOrThrow()
            val (name, data) = invoker.calls.single()
            assertEquals(HouseholdFunctions.REGISTER_PARENT_KEY, name)
            assertEquals(THIS_DEVICE.value, data["deviceId"])
            assertEquals(Base64.Default.encode(key), data["publicKey"])
            assertEquals(chain.map { Base64.Default.encode(it) }, data["attestationChain"], "leaf first, as given")
            val payload = DeviceProof.registerParentKeyPayload(REAL_HOUSEHOLD, tokens.hashOf(1), key)
            assertTrue(verifies(payload, data["signature"]))
            assertEquals(listOf("f".repeat(64) to 42L), policies.recorded)
        }

    @Test
    fun `an unattested parent key is sent without an attestationChain field`() = runTest {
        val invoker = RecordingInvoker(mapOf("fingerprint" to "f".repeat(64), "validFromLamport" to 1L))
        admin(invoker).registerParentKey(byteArrayOf(1, 2), emptyList()).getOrThrow()
        val data = invoker.calls.single().second
        assertFalse("attestationChain" in data, "the server then applies its unattested rule")
    }

    @Test
    fun `the registration challenge comes from beginDeviceRegistration, and the trial household has none`() = runTest {
        val challenge = ByteArray(32) { 5 }
        val invoker = RecordingInvoker(mapOf("challenge" to Base64.Default.encode(challenge)))
        val source = ServerParentKeyChallengeSource(admin(invoker))
        assertTrue(challenge.contentEquals(source.registrationChallenge()))
        assertEquals(HouseholdFunctions.BEGIN_DEVICE_REGISTRATION, invoker.calls.single().first)

        val trial = ServerParentKeyChallengeSource(admin(invoker, FakeSessionStore(trialSession())))
        assertEquals(null, trial.registrationChallenge())
        assertEquals(1, invoker.calls.size, "the trial household never reaches the backend")

        invoker.failure = FunctionsException(FunctionsError.Unavailable)
        val failure = runCatching { source.registrationChallenge() }.exceptionOrNull() as FunctionsException
        assertEquals(FunctionsError.Unavailable, failure.error)
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

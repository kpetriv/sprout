package dev.kpetriv.sprout.core.crypto.chat

import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.crypto.EncryptionKeyStore
import dev.kpetriv.sprout.core.crypto.InMemoryBlobStorage
import dev.kpetriv.sprout.core.crypto.InMemoryKeyWrapper
import dev.kpetriv.sprout.core.crypto.JceEcdsaVerifier
import dev.kpetriv.sprout.core.crypto.JceX25519KeyAgreement
import dev.kpetriv.sprout.core.crypto.SecretStoreEncryptionKeyStore
import dev.kpetriv.sprout.core.crypto.SecretUnavailableException
import dev.kpetriv.sprout.core.crypto.TinkX25519KeyAgreement
import dev.kpetriv.sprout.core.crypto.WrappedSecretStore
import dev.kpetriv.sprout.core.domainledger.AttestationLevel
import dev.kpetriv.sprout.core.domainledger.BoundEncryptionKey
import dev.kpetriv.sprout.core.domainledger.DeviceOwner
import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncryptionKeyTest {
    private val random = SecureRandom()
    private val storage = InMemoryBlobStorage()
    private val secrets = WrappedSecretStore(InMemoryKeyWrapper(random), storage, random, Dispatchers.Unconfined)
    private val agreement = JceX25519KeyAgreement(random)
    private val store = SecretStoreEncryptionKeyStore(secrets, agreement)
    private val check = JceEcdsaVerifier().asSignatureCheck()
    private val device = DeviceId("parent-phone")

    @Test
    fun theKeyPairIsCreatedOnceAndPersistedUnderItsSecretName() = runTest {
        assertFailsWith<SecretUnavailableException> { store.keyPair(createIfMissing = false) }
        val first = store.keyPair()
        val again = store.keyPair(createIfMissing = false)
        assertEquals(first.publicKey, again.publicKey)
        assertEquals(first.publicKey, agreement.publicKeyOf(again.privateKey))
        assertEquals(first.publicKey, store.publicKey())
        assertTrue(storage.read(EncryptionKeyStore.SECRET_NAME) != null)
        assertEquals("chat.x25519.v1", EncryptionKeyStore.SECRET_NAME)
        // The same stored scalar gives the same public key through the fallback implementation.
        val tinkStore = SecretStoreEncryptionKeyStore(secrets, TinkX25519KeyAgreement(random))
        assertEquals(first.publicKey, tinkStore.publicKey())
        first.close()
        again.close()
        assertFalse(first.toString().contains("material=["))
    }

    @Test
    fun theBindingSignedWithTheDeviceKeyVerifiesForReceivers() = runTest {
        val deviceKeys = dev.kpetriv.sprout.core.crypto.InMemoryDeviceKeyStore(dev.kpetriv.sprout.core.time.FakeClock())
        val binding = EncryptionKeyBinding(DeviceSigner(deviceKeys, Dispatchers.Unconfined))
        val publicKey = store.publicKey()
        val signature = binding.sign(household, device, publicKey)
        val devicePublicKey = deviceKeys.publicKey()
        val bound = BoundEncryptionKey(household, publicKey.bytes, signature)

        assertTrue(EncryptionKeyBinding.verify(check, bound, device, devicePublicKey))
        assertFalse(EncryptionKeyBinding.verify(check, bound, DeviceId("kid-tablet"), devicePublicKey))
        val otherHousehold = BoundEncryptionKey(HouseholdId("house-2"), publicKey.bytes, signature)
        assertFalse(EncryptionKeyBinding.verify(check, otherHousehold, device, devicePublicKey))
        val otherKey = BoundEncryptionKey(household, agreement.generateKeyPair().publicKey.bytes, signature)
        assertFalse(EncryptionKeyBinding.verify(check, otherKey, device, devicePublicKey))
        val otherDevice = dev.kpetriv.sprout.core.crypto.generateSoftwareSigningKey()
        assertFalse(EncryptionKeyBinding.verify(check, bound, device, otherDevice.publicKey))

        val registered = RegisteredDevice(
            device,
            DeviceOwner(UserId("parent-uid"), Role.PARENT),
            devicePublicKey.encoded,
            DeviceStatus.Active,
            AttestationLevel.TEE,
            bound,
        )
        assertContentEquals(publicKey.bytes, registered.verifiedEncryptionKey(check))
        val tampered = BoundEncryptionKey(
            household,
            publicKey.bytes,
            signature.copyOf().also {
                it[8] =
                    (it[8] + 1).toByte()
            },
        )
        assertNull(
            RegisteredDevice(
                device,
                registered.owner,
                registered.publicKey,
                DeviceStatus.Active,
                AttestationLevel.TEE,
                tampered,
            )
                .verifiedEncryptionKey(check),
        )
    }

    @Test
    fun aPendingRegistrationKeyCanSignTheBinding() = runTest {
        val deviceKeys = dev.kpetriv.sprout.core.crypto.InMemoryDeviceKeyStore(dev.kpetriv.sprout.core.time.FakeClock())
        val binding = EncryptionKeyBinding(DeviceSigner(deviceKeys, Dispatchers.Unconfined))
        val pending = deviceKeys.regenerateForRegistration(byteArrayOf(7))
        val pendingKey = deviceKeys.pendingSigningKey()!!
        val publicKey = store.publicKey()
        val signature = binding.signWith(pendingKey, household, device, publicKey)
        val bound = BoundEncryptionKey(household, publicKey.bytes, signature)
        assertTrue(EncryptionKeyBinding.verify(check, bound, device, pending.publicKey))
        assertFalse(EncryptionKeyBinding.verify(check, bound, device, deviceKeys.publicKey()))
    }
}

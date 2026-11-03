package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncryptionKeyBindingTest {
    private val household = HouseholdId("house-1")
    private val device = DeviceId("parent-phone")
    private val deviceKey = SoftwareKey()
    private val x25519 = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun payloadLayoutIsDomainHouseholdDeviceAndKeySeparatedByZero() {
        val expected =
            "sprout/device-encryption-key/v1\u0000house-1\u0000parent-phone\u0000".encodeToByteArray() + x25519
        assertContentEquals(expected, EncryptionKeyBindingPayload.of(household, device, x25519))
        assertFailsWith<IllegalArgumentException> { EncryptionKeyBindingPayload.of(household, device, ByteArray(31)) }
    }

    @Test
    fun aSmallOrderKeyNeverVerifiesEvenWithAValidBinding() {
        val smallOrder = listOf(
            ByteArray(32),
            ByteArray(32).also { it[0] = 1 },
            ByteArray(32) { 0xff.toByte() }.also {
                it[0] = 0xec.toByte()
                it[31] = 0x7f
            },
            // The same point with the unused top bit set.
            ByteArray(32).also {
                it[0] = 1
                it[31] = 0x80.toByte()
            },
        )
        for (key in smallOrder) {
            assertTrue(X25519SmallOrder.isSmallOrder(key))
            val bound = deviceKey.bindEncryptionKey(household, device, key)
            assertFalse(bound.verify(SoftwareSignatureCheck, device, deviceKey.publicKey))
        }
        assertFalse(X25519SmallOrder.isSmallOrder(x25519))
        assertFalse(X25519SmallOrder.isSmallOrder(ByteArray(31)))
    }

    @Test
    fun aBindingVerifiesOnlyForItsHouseholdDeviceKeyAndDeviceKey() {
        val bound = deviceKey.bindEncryptionKey(household, device, x25519)
        assertTrue(bound.verify(SoftwareSignatureCheck, device, deviceKey.publicKey))

        assertFalse(bound.verify(SoftwareSignatureCheck, DeviceId("kid-tablet"), deviceKey.publicKey))
        assertFalse(bound.verify(SoftwareSignatureCheck, device, SoftwareKey().publicKey))
        val otherHousehold = BoundEncryptionKey(HouseholdId("house-2"), x25519, bound.signature)
        assertFalse(otherHousehold.verify(SoftwareSignatureCheck, device, deviceKey.publicKey))
        val otherKey = BoundEncryptionKey(household, x25519.copyOf().also { it[0] = 99 }, bound.signature)
        assertFalse(otherKey.verify(SoftwareSignatureCheck, device, deviceKey.publicKey))
    }

    @Test
    fun malformedBindingsNeverVerifyAndNeverThrow() {
        val bound = deviceKey.bindEncryptionKey(household, device, x25519)
        val shortKey = BoundEncryptionKey(household, ByteArray(31), bound.signature)
        assertFalse(shortKey.verify(SoftwareSignatureCheck, device, deviceKey.publicKey))
        val noSignature = BoundEncryptionKey(household, x25519, ByteArray(0))
        assertFalse(noSignature.verify(SoftwareSignatureCheck, device, deviceKey.publicKey))
        val tampered = BoundEncryptionKey(household, x25519, bound.signature.also { it[it.size - 1] = 0 })
        assertFalse(tampered.verify(SoftwareSignatureCheck, device, deviceKey.publicKey))
        val throwing = SignatureCheck { _, _, _ -> error("provider crashed") }
        assertFalse(bound.verify(throwing, device, deviceKey.publicKey))
    }

    @Test
    fun registeredDevicesExposeOnlyAVerifiedKey() {
        val good = deviceKey.bindEncryptionKey(household, device, x25519)
        fun registered(key: BoundEncryptionKey?) = RegisteredDevice(
            device,
            DeviceOwner(dev.kpetriv.sprout.core.model.UserId("u"), dev.kpetriv.sprout.core.model.Role.PARENT),
            deviceKey.publicKey,
            DeviceStatus.Active,
            AttestationLevel.TEE,
            key,
        )
        assertContentEquals(x25519, registered(good).verifiedEncryptionKey(SoftwareSignatureCheck))
        assertNull(registered(null).verifiedEncryptionKey(SoftwareSignatureCheck))
        val forged = SoftwareKey().bindEncryptionKey(household, device, x25519)
        assertNull(registered(forged).verifiedEncryptionKey(SoftwareSignatureCheck))
    }

    @Test
    fun registryRowsDecodeFromBase64() {
        val bound = deviceKey.bindEncryptionKey(household, device, x25519)
        val decoded = BoundEncryptionKey.fromBase64(
            household,
            Base64.Default.encode(x25519),
            Base64.Default.encode(bound.signature),
        )!!
        assertTrue(decoded.verify(SoftwareSignatureCheck, device, deviceKey.publicKey))
        assertNull(BoundEncryptionKey.fromBase64(household, null, "AA=="))
        assertNull(BoundEncryptionKey.fromBase64(household, "not base64!", "AA=="))
        assertEquals(false, decoded.toString().contains(Base64.Default.encode(bound.signature)))
    }
}

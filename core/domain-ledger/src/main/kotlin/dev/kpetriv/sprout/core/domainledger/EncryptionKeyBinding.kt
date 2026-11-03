package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import kotlin.io.encoding.Base64

/**
 * The bytes a device signs with its P-256 device key to bind its X25519 chat key to itself (M4 §1.1, D-M4-2):
 *
 * `UTF-8("sprout/device-encryption-key/v1") || 0x00 || UTF-8(householdId) || 0x00 || UTF-8(deviceId) || 0x00 ||
 * x25519PublicKey(32 raw bytes)`
 *
 * Ids never contain 0x00 (`ID_PATTERN`) and the key has a fixed length, so the layout is unambiguous. The server
 * verifies the binding before it stores the key; every receiver verifies it again ([BoundEncryptionKey.verify]).
 */
object EncryptionKeyBindingPayload {
    const val DOMAIN: String = "sprout/device-encryption-key/v1"

    /** Raw X25519 public key length (RFC 7748). */
    const val KEY_SIZE: Int = 32

    private const val SEPARATOR: Byte = 0

    /** @throws IllegalArgumentException if [encryptionKey] is not [KEY_SIZE] bytes. */
    fun of(householdId: HouseholdId, deviceId: DeviceId, encryptionKey: ByteArray): ByteArray {
        require(encryptionKey.size == KEY_SIZE) { "An X25519 public key is $KEY_SIZE bytes" }
        return DOMAIN.encodeToByteArray() + SEPARATOR + householdId.value.encodeToByteArray() + SEPARATOR +
            deviceId.value.encodeToByteArray() + SEPARATOR + encryptionKey
    }
}

/**
 * A device's X25519 public key ([publicKey], 32 raw bytes) and the device-key [signature] (DER ECDSA P-256) binding it
 * to the device in [householdId], as mirrored from the registry. Holds copies; the getters return copies.
 */
class BoundEncryptionKey(val householdId: HouseholdId, publicKey: ByteArray, signature: ByteArray) {
    private val key: ByteArray = publicKey.copyOf()
    private val sig: ByteArray = signature.copyOf()

    val publicKey: ByteArray get() = key.copyOf()
    val signature: ByteArray get() = sig.copyOf()

    /**
     * True only if the key is 32 bytes and [signature] verifies over [EncryptionKeyBindingPayload.of] for
     * ([householdId], [deviceId], [publicKey]) against [devicePublicKey] (the registered SPKI DER device key).
     * Never throws: a malformed key or a [SignatureCheck] that throws counts as "does not verify".
     */
    @Suppress("TooGenericExceptionCaught") // A third-party SignatureCheck must not crash registry reads.
    fun verify(check: SignatureCheck, deviceId: DeviceId, devicePublicKey: ByteArray): Boolean {
        if (key.size != EncryptionKeyBindingPayload.KEY_SIZE || sig.isEmpty()) return false
        return try {
            check.verify(devicePublicKey, EncryptionKeyBindingPayload.of(householdId, deviceId, key), sig)
        } catch (expected: Exception) {
            false
        }
    }

    override fun toString(): String = "BoundEncryptionKey(householdId=$householdId, publicKey=${key.toHex()})"

    companion object {
        /**
         * From the registry row's base64 fields (`encryptionKey`, `encryptionKeySignature`); null when either is
         * missing or not valid base64. The result still has to [verify].
         */
        fun fromBase64(householdId: HouseholdId, encryptionKey: String?, signature: String?): BoundEncryptionKey? {
            if (encryptionKey == null || signature == null) return null
            return try {
                BoundEncryptionKey(householdId, Base64.Default.decode(encryptionKey), Base64.Default.decode(signature))
            } catch (expected: IllegalArgumentException) {
                null
            }
        }
    }
}

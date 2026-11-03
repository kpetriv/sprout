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
     * True only if the key is 32 bytes, is not a small-order point ([X25519SmallOrder], audit L1: every agreement
     * with it would give a fixed shared secret), and [signature] verifies over [EncryptionKeyBindingPayload.of] for
     * ([householdId], [deviceId], [publicKey]) against [devicePublicKey] (the registered SPKI DER device key).
     * Never throws: a malformed key or a [SignatureCheck] that throws counts as "does not verify".
     */
    @Suppress("TooGenericExceptionCaught") // A third-party SignatureCheck must not crash registry reads.
    fun verify(check: SignatureCheck, deviceId: DeviceId, devicePublicKey: ByteArray): Boolean {
        val usable = key.size == EncryptionKeyBindingPayload.KEY_SIZE && sig.isNotEmpty() &&
            !X25519SmallOrder.isSmallOrder(key)
        if (!usable) return false
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

/**
 * The X25519 public keys of small order (RFC 7748 §7, the blocklist libsodium uses), compared with the unused top bit
 * cleared: an agreement with any of them yields a shared secret that does not depend on the private key (often all
 * zero). A registered encryption key must never be one of them.
 */
object X25519SmallOrder {
    private const val TOP_BIT_MASK = 0x7f
    private const val LAST = EncryptionKeyBindingPayload.KEY_SIZE - 1

    private val BLOCKLIST: List<ByteArray> = listOf(
        "0000000000000000000000000000000000000000000000000000000000000000",
        "0100000000000000000000000000000000000000000000000000000000000000",
        "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
        "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
        "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
    ).map(::hexToBytes)

    /** Whether [key] (32 raw bytes) is a small-order point; false for any other length. */
    fun isSmallOrder(key: ByteArray): Boolean {
        if (key.size != EncryptionKeyBindingPayload.KEY_SIZE) return false
        val masked = key.copyOf().also { it[LAST] = (it[LAST].toInt() and TOP_BIT_MASK).toByte() }
        return BLOCKLIST.any { it.contentEquals(masked) }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(HEX_RADIX).toByte() }

    private const val HEX_RADIX = 16
}

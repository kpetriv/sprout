package dev.kpetriv.sprout.core.crypto

import dev.kpetriv.sprout.core.time.FakeClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Provider
import java.security.ProviderException
import java.security.PublicKey
import java.security.Security
import java.security.Signature
import java.security.SignatureSpi
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EcdsaTest {
    private val verifier = JceEcdsaVerifier()
    private val key = generateSoftwareSigningKey()
    private val signer = JceEcdsaSigner(key)
    private val payload = "event:42|lamport:7|amount:150".encodeToByteArray()

    @Test
    fun `signature round trips`() {
        val signature = signer.sign(payload)
        assertTrue(verifier.verify(signer.publicKey, payload, signature))
    }

    @Test
    fun `public key is X509 DER for P-256`() {
        val decoded = signer.publicKey.toPublicKey()
        assertEquals("EC", decoded.algorithm)
        assertEquals("X.509", decoded.format)
        assertContentEquals(decoded.encoded, signer.publicKey.encoded)
    }

    @Test
    fun `tampered payload fails`() {
        val signature = signer.sign(payload)
        val tampered = payload.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(verifier.verify(signer.publicKey, tampered, signature))
    }

    @Test
    fun `tampered signature fails`() {
        val signature = signer.sign(payload)
        val tampered = signature.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFalse(verifier.verify(signer.publicKey, payload, tampered))
    }

    @Test
    fun `signature from another key fails`() {
        val other = JceEcdsaSigner(generateSoftwareSigningKey())
        assertFalse(verifier.verify(signer.publicKey, payload, other.sign(payload)))
    }

    @Test
    fun `malformed signature and key return false instead of throwing`() {
        assertFalse(verifier.verify(signer.publicKey, payload, byteArrayOf(1, 2, 3)))
        assertFalse(verifier.verify(signer.publicKey, payload, ByteArray(0)))
        assertFalse(verifier.verify(PublicKeyBytes(byteArrayOf(0x30, 0x01, 0x00)), payload, signer.sign(payload)))
    }

    @Test
    fun `structurally broken DER signatures return false`() {
        val valid = signer.sign(payload)
        val broken = listOf(
            // Wrong outer length, truncated, trailing garbage.
            valid.copyOf().also { it[1] = (it[1] + 5).toByte() },
            valid.copyOf(valid.size - 3),
            valid + byteArrayOf(0, 0),
            // Length field claiming 4 GiB, and a negative INTEGER length.
            byteArrayOf(0x30, 0x84.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()),
            byteArrayOf(0x30, 0x06, 0x02, 0x81.toByte(), 0xff.toByte(), 0x02, 0x01, 0x01),
            // Raw r || s (IEEE P1363) instead of DER, and an all-zero signature of the right size.
            ByteArray(64) { 1 },
            ByteArray(valid.size),
            // DER SEQUENCE of zero INTEGERs (r = s = 0 is invalid).
            byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x00, 0x02, 0x01, 0x00),
        )
        broken.forEachIndexed { index, signature ->
            assertFalse(verifier.verify(signer.publicKey, payload, signature), "case $index")
        }
    }

    @Test
    fun `malformed public keys return false`() {
        val signature = signer.sign(payload)
        val encoded = signer.publicKey.encoded
        listOf(
            ByteArray(0),
            encoded.copyOf(encoded.size - 1),
            encoded.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() },
            ByteArray(91) { 0x30 },
        ).forEachIndexed { index, key ->
            assertFalse(verifier.verify(PublicKeyBytes(key), payload, signature), "case $index")
        }
    }

    @Test
    fun `runtime exceptions from the provider return false instead of throwing`() {
        val signature = signer.sign(payload)
        for (failure in listOf(ProviderException("simulated"), ArrayIndexOutOfBoundsException("simulated"))) {
            ThrowingEcdsaSpi.failure = failure
            Security.insertProviderAt(ThrowingProvider, 1)
            try {
                assertFalse(verifier.verify(signer.publicKey, payload, signature), failure.javaClass.simpleName)
            } finally {
                Security.removeProvider(ThrowingProvider.name)
            }
        }
        // With the provider removed, the same signature verifies again.
        assertTrue(verifier.verify(signer.publicKey, payload, signature))
    }

    @Test
    fun `keys on other curves are rejected`() {
        val p384 = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp384r1"))
            generateKeyPair()
        }
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(p384.private)
            update(payload)
            sign()
        }
        assertFalse(verifier.verify(PublicKeyBytes(p384.public.encoded), payload, signature))
    }

    @Test
    fun `public key bytes compare by content and hide nothing secret`() {
        val copy = PublicKeyBytes(signer.publicKey.encoded)
        assertEquals(signer.publicKey, copy)
        assertEquals(signer.publicKey.hashCode(), copy.hashCode())
        assertNotEquals(signer.publicKey, generateSoftwareSigningKey().publicKey)
        assertEquals(32, copy.fingerprint().size)
        assertTrue(key.toString().contains("SigningKeyHandle(alias=null"))
    }

    @Test
    fun `device signer signs with the device key`() = runTest {
        val store = InMemoryDeviceKeyStore(FakeClock())
        val deviceSigner = DeviceSigner(store, Dispatchers.Unconfined)
        val signature = deviceSigner.sign(payload)
        assertTrue(verifier.verify(store.publicKey(), payload, signature))
        assertEquals(store.publicKey(), deviceSigner.publicKey())
        assertFalse(store.keyProperties().isHardwareBacked)
    }

    @Test
    fun `device signer ignores a pending registration key until it is promoted`() = runTest {
        val store = InMemoryDeviceKeyStore(FakeClock())
        val deviceSigner = DeviceSigner(store, Dispatchers.Unconfined)
        val active = store.publicKey()
        val pending = store.regenerateForRegistration(byteArrayOf(1)).publicKey

        assertTrue(verifier.verify(active, payload, deviceSigner.sign(payload)))
        assertFalse(verifier.verify(pending, payload, deviceSigner.sign(payload)))

        store.promotePendingKey()
        assertTrue(verifier.verify(pending, payload, deviceSigner.sign(payload)))
        assertEquals(pending, deviceSigner.publicKey())
    }
}

/** Test-only JCA provider whose ECDSA verification throws [ThrowingEcdsaSpi.failure], like a buggy provider. */
// android.jar (the unit-test compile classpath) only has the deprecated double-version constructor.
@Suppress("DEPRECATION")
private object ThrowingProvider : Provider("SproutThrowingEcdsa", 1.0, "Test provider that throws on verify") {
    init {
        put("Signature.SHA256withECDSA", ThrowingEcdsaSpi::class.java.name)
    }
}

class ThrowingEcdsaSpi : SignatureSpi() {
    override fun engineInitVerify(publicKey: PublicKey?) = Unit

    override fun engineInitSign(privateKey: PrivateKey?) = throw UnsupportedOperationException()

    override fun engineUpdate(b: Byte) = Unit

    override fun engineUpdate(b: ByteArray?, off: Int, len: Int) = Unit

    override fun engineSign(): ByteArray = throw UnsupportedOperationException()

    override fun engineVerify(sigBytes: ByteArray?): Boolean = throw failure

    @Deprecated("Required by SignatureSpi")
    override fun engineSetParameter(param: String?, value: Any?) = Unit

    @Deprecated("Required by SignatureSpi")
    override fun engineGetParameter(param: String?): Any? = null

    companion object {
        @Volatile
        var failure: RuntimeException = ProviderException("simulated")
    }
}

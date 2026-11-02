package dev.kpetriv.sprout.core.crypto

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class X25519Test {
    private val random = SecureRandom()
    private val agreement = JceX25519KeyAgreement(random)
    private val derivation = HouseholdKeyDerivation()

    @Test
    fun `RFC 7748 section 6_1 test vector`() {
        val alice = X25519PrivateKey.copyOf(hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"))
        val bob = X25519PrivateKey.copyOf(hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"))
        val alicePublic = agreement.publicKeyOf(alice)
        val bobPublic = agreement.publicKeyOf(bob)
        assertContentEquals(hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"), alicePublic.bytes)
        assertContentEquals(hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"), bobPublic.bytes)
        val expected = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
        agreement.sharedSecret(alice, bobPublic).use { assertContentEquals(expected, it.copyBytes()) }
        agreement.sharedSecret(bob, alicePublic).use { assertContentEquals(expected, it.copyBytes()) }
    }

    @Test
    fun `shared secret is equal in both directions`() {
        agreement.generateKeyPair().use { parent ->
            agreement.generateKeyPair().use { kid ->
                val fromParent = agreement.sharedSecret(parent.privateKey, kid.publicKey)
                val fromKid = agreement.sharedSecret(kid.privateKey, parent.publicKey)
                assertContentEquals(fromParent.copyBytes(), fromKid.copyBytes())
                assertEquals(32, fromParent.size)
                fromParent.close()
                fromKid.close()
            }
        }
    }

    @Test
    fun `both devices derive the same thread key`() {
        val parent = agreement.generateKeyPair()
        val kid = agreement.generateKeyPair()
        val salt = "household-1".encodeToByteArray()
        val info = "thread-7|epoch-1".encodeToByteArray()
        val parentKey = agreement.sharedSecret(parent.privateKey, kid.publicKey).use {
            derivation.deriveThreadKey(it, salt, info)
        }
        val kidKey = agreement.sharedSecret(kid.privateKey, parent.publicKey).use {
            derivation.deriveThreadKey(it, salt, info)
        }
        assertContentEquals(parentKey.copyBytes(), kidKey.copyBytes())

        // The derived key works with Aead on both sides.
        val aead = AesGcmAead(random)
        val message = "Great job!".encodeToByteArray()
        assertContentEquals(message, aead.decrypt(kidKey, aead.encrypt(parentKey, message, info), info))
    }

    @Test
    fun `new epoch or other thread gives a different key`() {
        val parent = agreement.generateKeyPair()
        val kid = agreement.generateKeyPair()
        val secret = agreement.sharedSecret(parent.privateKey, kid.publicKey)
        val salt = "household-1".encodeToByteArray()
        val epoch1 = derivation.deriveThreadKey(secret, salt, "thread-7|epoch-1".encodeToByteArray()).copyBytes()
        val epoch2 = derivation.deriveThreadKey(secret, salt, "thread-7|epoch-2".encodeToByteArray()).copyBytes()
        val other = derivation.deriveThreadKey(secret, salt, "thread-8|epoch-1".encodeToByteArray()).copyBytes()
        assertFalse(epoch1.contentEquals(epoch2))
        assertFalse(epoch1.contentEquals(other))
        // The thread key is never the raw shared secret.
        assertFalse(epoch1.contentEquals(secret.copyBytes()))
    }

    @Test
    fun `low-order peer key is rejected`() {
        agreement.generateKeyPair().use { pair ->
            assertFailsWith<KeyAgreementException> {
                agreement.sharedSecret(pair.privateKey, X25519PublicKey(ByteArray(32)))
            }
        }
    }

    @Test
    fun `private key is wiped on close and never printed`() {
        val pair = agreement.generateKeyPair()
        assertFalse(pair.toString().contains(pair.privateKey.copyBytes().toHex()))
        pair.close()
        assertFailsWith<IllegalStateException> { pair.privateKey.copyBytes() }
    }
}

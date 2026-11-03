package dev.kpetriv.sprout.core.crypto

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** The X25519 contract: every RFC 7748 vector and behaviour, run against both implementations (D-M4-1). */
abstract class X25519ContractTest {
    protected val random = SecureRandom()
    protected abstract val agreement: X25519KeyAgreement
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

    @Test
    fun `RFC 7748 section 5_2 scalar multiplication vectors`() {
        val vectors = listOf(
            Triple(
                "a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4",
                "e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c",
                "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            ),
            Triple(
                "4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d",
                // The top bit of this u-coordinate is set: implementations must mask it (RFC 7748 section 5).
                "e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493",
                "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957",
            ),
        )
        for ((scalar, u, expected) in vectors) {
            X25519PrivateKey.copyOf(hex(scalar)).use { k ->
                agreement.sharedSecret(k, X25519PublicKey(hex(u))).use {
                    assertContentEquals(hex(expected), it.copyBytes())
                }
            }
        }
    }

    @Test
    fun `RFC 7748 section 5_2 iterated vectors after 1 and 1000 iterations`() {
        var k = ByteArray(32).also { it[0] = 9 }
        var u = ByteArray(32).also { it[0] = 9 }
        val after = mutableMapOf<Int, ByteArray>()
        for (iteration in 1..1000) {
            val result = X25519PrivateKey.copyOf(k).use { scalar ->
                agreement.sharedSecret(scalar, X25519PublicKey(u)).use { it.copyBytes() }
            }
            u = k
            k = result
            if (iteration == 1 || iteration == 1000) after[iteration] = k
        }
        assertContentEquals(hex("422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079"), after[1])
        assertContentEquals(hex("684cf59ba83309552800ef566f2f4d3c1c3887c49360e3875f2eb94d99532c51"), after[1000])
    }

    @Test
    fun `known low-order points are rejected`() {
        // u = 1 is a point of order 4: the product is all zeros, which both implementations must refuse.
        val one = ByteArray(32).also { it[0] = 1 }
        agreement.generateKeyPair().use { pair ->
            assertFailsWith<KeyAgreementException> { agreement.sharedSecret(pair.privateKey, X25519PublicKey(one)) }
        }
    }
}

class JceX25519Test : X25519ContractTest() {
    override val agreement: X25519KeyAgreement = JceX25519KeyAgreement(random)
}

class TinkX25519Test : X25519ContractTest() {
    override val agreement: X25519KeyAgreement = TinkX25519KeyAgreement(random)

    @Test
    fun `Tink and JCE agree on keys and secrets in both directions`() {
        val jce = JceX25519KeyAgreement(random)
        repeat(20) {
            agreement.generateKeyPair().use { tinkPair ->
                jce.generateKeyPair().use { jcePair ->
                    assertEquals(tinkPair.publicKey, jce.publicKeyOf(tinkPair.privateKey))
                    assertEquals(jcePair.publicKey, agreement.publicKeyOf(jcePair.privateKey))
                    val fromTink = agreement.sharedSecret(tinkPair.privateKey, jcePair.publicKey).use { it.copyBytes() }
                    val fromJce = jce.sharedSecret(jcePair.privateKey, tinkPair.publicKey).use { it.copyBytes() }
                    assertContentEquals(fromTink, fromJce)
                }
            }
        }
    }
}

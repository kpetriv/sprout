package dev.kpetriv.sprout.core.crypto

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AesGcmAeadTest {
    private val random = SecureRandom()
    private val aead = AesGcmAead(random)
    private val key = SymmetricKey.generate(random)
    private val plaintext = "Tidy your room for 50 credits".encodeToByteArray()
    private val aad = "household-1|doc-9".encodeToByteArray()

    @Test
    fun `round trip`() {
        val ciphertext = aead.encrypt(key, plaintext, aad)
        assertContentEquals(plaintext, aead.decrypt(key, ciphertext, aad))
    }

    @Test
    fun `empty plaintext round trips`() {
        val ciphertext = aead.encrypt(key, ByteArray(0), aad)
        assertEquals(AesGcmAead.OVERHEAD, ciphertext.size)
        assertContentEquals(ByteArray(0), aead.decrypt(key, ciphertext, aad))
    }

    @Test
    fun `layout is version then nonce then ciphertext then tag`() {
        val ciphertext = aead.encrypt(key, plaintext, aad)
        assertEquals(1 + 12 + plaintext.size + 16, ciphertext.size)
        assertEquals(29, AesGcmAead.OVERHEAD)
        assertEquals(0x01, ciphertext[0].toInt())
        assertFalse(ciphertext.copyOfRange(13, 13 + plaintext.size).contentEquals(plaintext))
    }

    @Test
    fun `wrong associated data fails`() {
        val ciphertext = aead.encrypt(key, plaintext, aad)
        assertFailsWith<AeadException> { aead.decrypt(key, ciphertext, "household-1|doc-10".encodeToByteArray()) }
    }

    @Test
    fun `empty associated data is rejected on both sides`() {
        assertFailsWith<IllegalArgumentException> { aead.encrypt(key, plaintext, ByteArray(0)) }
        val ciphertext = aead.encrypt(key, plaintext, aad)
        assertFailsWith<IllegalArgumentException> { aead.decrypt(key, ciphertext, ByteArray(0)) }
    }

    @Test
    fun `unknown version byte fails`() {
        val ciphertext = aead.encrypt(key, plaintext, aad)
        for (version in listOf(0x00, 0x02, 0x7f, 0xff)) {
            val relabelled = ciphertext.copyOf().also { it[0] = version.toByte() }
            val error = assertFailsWith<AeadException>("version $version") { aead.decrypt(key, relabelled, aad) }
            assertEquals("Unsupported ciphertext version", error.message)
        }
    }

    @Test
    fun `version byte is bound into the tag so a relabelled v1 blob fails authentication`() {
        // Emulates a future decoder that accepts version 0x02: it authenticates `0x02 || aad`, which must not
        // match a tag computed over `0x01 || aad`. Also checks that the tag does not cover the bare aad.
        val ciphertext = aead.encrypt(key, plaintext, aad)
        val nonce = ciphertext.copyOfRange(1, 13)
        val body = ciphertext.copyOfRange(13, ciphertext.size)
        fun open(associatedData: ByteArray): ByteArray = key.withMaterial { material ->
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(material, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(associatedData)
                doFinal(body)
            }
        }
        assertContentEquals(plaintext, open(byteArrayOf(0x01) + aad))
        assertFailsWith<AEADBadTagException> { open(byteArrayOf(0x02) + aad) }
        assertFailsWith<AEADBadTagException> { open(aad) }
    }

    @Test
    fun `tampering with any byte fails`() {
        val ciphertext = aead.encrypt(key, plaintext, aad)
        // Version, nonce, body, and tag positions.
        for (index in listOf(0, 1, 12, 13, 13 + plaintext.size - 1, ciphertext.size - 1)) {
            val tampered = ciphertext.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertFailsWith<AeadException>("byte $index") { aead.decrypt(key, tampered, aad) }
        }
    }

    @Test
    fun `truncated ciphertext fails`() {
        val ciphertext = aead.encrypt(key, plaintext, aad)
        assertFailsWith<AeadException> { aead.decrypt(key, ciphertext.copyOf(ciphertext.size - 1), aad) }
        assertFailsWith<AeadException> { aead.decrypt(key, ciphertext.copyOf(AesGcmAead.OVERHEAD - 1), aad) }
    }

    @Test
    fun `wrong key fails`() {
        val ciphertext = aead.encrypt(key, plaintext, aad)
        SymmetricKey.generate(random).use { other ->
            assertFailsWith<AeadException> { aead.decrypt(other, ciphertext, aad) }
        }
    }

    @Test
    fun `10000 encryptions use 10000 distinct nonces`() {
        val nonces = HashSet<String>()
        repeat(10_000) {
            val ciphertext = aead.encrypt(key, plaintext, aad)
            nonces += ciphertext.copyOfRange(1, 1 + AesGcmAead.NONCE_SIZE).toHex()
        }
        assertEquals(10_000, nonces.size)
    }

    @Test
    fun `same plaintext encrypts differently each time`() {
        assertFalse(aead.encrypt(key, plaintext, aad).contentEquals(aead.encrypt(key, plaintext, aad)))
    }

    @Test
    fun `rejects non 256-bit keys`() {
        SymmetricKey.generate(random, size = 16).use { short ->
            assertFailsWith<IllegalArgumentException> { aead.encrypt(short, plaintext, aad) }
        }
    }

    @Test
    fun `closed key is wiped and unusable`() {
        val local = SymmetricKey.generate(random)
        local.close()
        assertTrue(local.isClosed)
        assertFailsWith<IllegalStateException> { local.copyBytes() }
        assertFailsWith<IllegalStateException> { aead.encrypt(local, plaintext, aad) }
    }

    @Test
    fun `close zeroes the owned bytes`() {
        val source = ByteArray(32) { 7 }
        val local = SymmetricKey.adopt(source)
        local.close()
        assertTrue(source.all { it == 0.toByte() })
    }

    @Test
    fun `toString never prints key material`() {
        val material = key.copyBytes()
        val text = key.toString()
        assertEquals("SymmetricKey(size=32, material=<redacted>)", text)
        assertFalse(text.contains(material.toHex()))
        material.fill(0)
    }
}

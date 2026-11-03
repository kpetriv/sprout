package dev.kpetriv.sprout.core.crypto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PinHasherTest {
    private val random = SecureRandom()
    private val pepper = InMemoryHmacPepper(random)

    // Low iteration count keeps most tests fast; one test below runs the production default.
    private val hasher = Pbkdf2PinHasher(random, Dispatchers.Unconfined, pepper, iterations = 1_000)

    @Test
    fun `default hasher verifies the right PIN and rejects a wrong one`() = runTest {
        val production = Pbkdf2PinHasher(random, Dispatchers.Unconfined, pepper)
        val encoded = production.hash("4821".toCharArray())
        assertTrue(encoded.startsWith("v2\$pbkdf2-sha256-pepper\$i=600000\$"))
        assertTrue(production.verify("4821".toCharArray(), encoded))
        assertFalse(production.verify("4822".toCharArray(), encoded))
    }

    @Test
    fun `mismatch returns false`() = runTest {
        val encoded = hasher.hash("1234".toCharArray())
        assertFalse(hasher.verify("4321".toCharArray(), encoded))
        assertFalse(hasher.verify("12345".toCharArray(), encoded))
        assertTrue(hasher.verify("1234".toCharArray(), encoded))
    }

    @Test
    fun `encoded format is v2 alg-pepper params salt hash`() = runTest {
        val encoded = hasher.hash("1234".toCharArray())
        val fields = encoded.split("$")
        assertEquals(5, fields.size)
        assertEquals("v2", fields[0])
        assertEquals("pbkdf2-sha256-pepper", fields[1])
        assertEquals("i=1000", fields[2])
        assertEquals(16, Base64.getDecoder().decode(fields[3]).size)
        assertEquals(32, Base64.getDecoder().decode(fields[4]).size)

        val parsed = assertNotNull(PinHash.parse(encoded))
        assertEquals(2, parsed.version)
        assertEquals("pbkdf2-sha256-pepper", parsed.algorithm)
        assertEquals(mapOf("i" to 1000), parsed.parameters)
        assertEquals(encoded, parsed.encode())
    }

    @Test
    fun `same PIN hashes differently thanks to the random salt`() = runTest {
        val first = hasher.hash("1234".toCharArray())
        val second = hasher.hash("1234".toCharArray())
        assertNotEquals(first, second)
        assertTrue(hasher.verify("1234".toCharArray(), first))
        assertTrue(hasher.verify("1234".toCharArray(), second))
    }

    @Test
    fun `argon2 format parses with ordered parameters`() {
        val salt = ByteArray(16) { it.toByte() }
        val hash = ByteArray(32) { (it * 3).toByte() }
        val params = linkedMapOf("m" to 65536, "t" to 3, "p" to 1)
        val encoded = PinHash(2, "argon2id-pepper", params, salt, hash).encode()
        assertTrue(encoded.startsWith("v2\$argon2id-pepper\$m=65536,t=3,p=1\$"))
        val parsed = assertNotNull(PinHash.parse(encoded))
        assertEquals(listOf("m", "t", "p"), parsed.parameters.keys.toList())
        assertContentEquals(salt, parsed.salt)
        assertContentEquals(hash, parsed.hash)
    }

    @Test
    fun `malformed encodings do not parse`() {
        val valid = "v2\$pbkdf2-sha256-pepper\$i=1000\$AAECAwQFBgcICQoLDA0ODw\$" +
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        assertNotNull(PinHash.parse(valid))
        listOf(
            "",
            // The retired M1 version no longer parses.
            valid.replaceFirst("v2", "v1"),
            "v3\$pbkdf2-sha256\$i=1000\$AAEC\$AAEC",
            "v0\$pbkdf2-sha256\$i=1000\$AAEC\$AAEC",
            "V1\$pbkdf2-sha256\$i=1000\$AAEC\$AAEC",
            "1\$pbkdf2-sha256\$i=1000\$AAEC\$AAEC",
            "v1\$PBKDF2\$i=1000\$AAEC\$AAEC",
            "v1\$pbkdf2-sha256\$i=abc\$AAEC\$AAEC",
            "v1\$pbkdf2-sha256\$i=1,i=2\$AAEC\$AAEC",
            "v1\$pbkdf2-sha256\$i=1000\$!!!\$AAEC",
            "v1\$pbkdf2-sha256\$i=1000\$\$AAEC",
            "v1\$pbkdf2-sha256\$i=1000\$AAEC",
            "v1\$pbkdf2-sha256\$i=99999999999\$AAEC\$AAEC",
            "$valid\$extra",
        ).forEach { assertNull(PinHash.parse(it), it) }
    }

    @Test
    fun `salt and hash lengths are bounded when parsing`() {
        fun encoded(saltSize: Int, hashSize: Int) =
            PinHash(2, "pbkdf2-sha256-pepper", mapOf("i" to 1000), ByteArray(saltSize) { 1 }, ByteArray(hashSize) { 2 })
                .encode()

        for ((salt, hash) in listOf(16 to 16, 16 to 32, 64 to 64, 32 to 64)) {
            assertNotNull(PinHash.parse(encoded(salt, hash)), "salt $salt hash $hash")
        }
        for ((salt, hash) in listOf(15 to 32, 65 to 32, 16 to 15, 16 to 65, 1 to 32, 16 to 1, 4096 to 32, 16 to 4096)) {
            assertNull(PinHash.parse(encoded(salt, hash)), "salt $salt hash $hash")
        }
    }

    @Test
    fun `out of range hash lengths are corrupt storage for verify`() = runTest {
        val huge = PinHash(2, "pbkdf2-sha256-pepper", mapOf("i" to 1000), ByteArray(16), ByteArray(1_000_000)).encode()
        assertFailsWith<IllegalArgumentException> { hasher.verify("1234".toCharArray(), huge) }
        assertTrue(hasher.needsRehash(huge))
    }

    @Test
    fun `verify throws on corrupt storage rather than reporting a wrong PIN`() = runTest {
        assertFailsWith<IllegalArgumentException> { hasher.verify("1234".toCharArray(), "garbage") }
        val argon = PinHash(2, "argon2id-pepper", mapOf("m" to 65536, "t" to 3, "p" to 1), ByteArray(16), ByteArray(32))
        assertFailsWith<IllegalArgumentException> { hasher.verify("1234".toCharArray(), argon.encode()) }
        val hostile = PinHash(2, "pbkdf2-sha256-pepper", mapOf("i" to 2_000_000_000), ByteArray(16), ByteArray(32))
        assertFailsWith<IllegalArgumentException> { hasher.verify("1234".toCharArray(), hostile.encode()) }
    }

    @Test
    fun `needsRehash flags weaker or foreign hashes`() = runTest {
        val strong = Pbkdf2PinHasher(random, Dispatchers.Unconfined, pepper, iterations = 2_000)
        val weak = hasher.hash("1234".toCharArray())
        assertTrue(strong.needsRehash(weak))
        assertFalse(hasher.needsRehash(weak))
        assertTrue(hasher.needsRehash("garbage"))
        val argon = PinHash(2, "argon2id-pepper", mapOf("m" to 65536, "t" to 3, "p" to 1), ByteArray(16), ByteArray(32))
        assertTrue(hasher.needsRehash(argon.encode()))
    }

    @Test
    fun `retired v1 hashes are refused as reset-required, never verified`() = runTest {
        for (retired in listOf(retiredPbkdf2("4821", iterations = 1_000), retiredArgon2id())) {
            assertTrue(PinHash.isRetiredFormat(retired))
            assertNull(PinHash.parse(retired))
            // Even the right PIN is not checked against an unpeppered hash.
            assertFailsWith<UnsupportedPinHashException> { hasher.verify("4821".toCharArray(), retired) }
            assertTrue(hasher.needsRehash(retired))
        }
        // Still an IllegalArgumentException, so callers that treat corrupt storage as "unavailable" keep doing so.
        assertIs<IllegalArgumentException>(UnsupportedPinHashException())
        assertFalse(PinHash.isRetiredFormat(hasher.hash("4821".toCharArray())))
    }

    @Test
    fun `version and algorithm must match`() = runTest {
        val peppered = assertNotNull(PinHash.parse(hasher.hash("4821".toCharArray())))
        val mismatched = listOf(
            PinHash(2, "pbkdf2-sha256", peppered.parameters, peppered.salt, peppered.hash),
            PinHash(2, "argon2id", mapOf("m" to 65536, "t" to 3, "p" to 1), ByteArray(16), ByteArray(32)),
        )
        mismatched.forEach { hash ->
            assertFailsWith<IllegalArgumentException>(hash.toString()) {
                hasher.verify("4821".toCharArray(), hash.encode())
            }
            assertTrue(hasher.needsRehash(hash.encode()))
        }
        assertFailsWith<IllegalArgumentException> { PinHash(1, "pbkdf2-sha256", mapOf(), ByteArray(16), ByteArray(32)) }
        assertFailsWith<IllegalArgumentException> { PinHash(3, "pbkdf2-sha256", mapOf(), ByteArray(16), ByteArray(32)) }
    }

    @Test
    fun `a peppered hash does not verify under another device's pepper`() = runTest {
        val encoded = hasher.hash("4821".toCharArray())
        val otherPepper = InMemoryHmacPepper(random)
        val otherDevice = Pbkdf2PinHasher(random, Dispatchers.Unconfined, otherPepper, iterations = 1_000)
        assertFalse(otherDevice.verify("4821".toCharArray(), encoded))
        assertTrue(hasher.verify("4821".toCharArray(), encoded))
    }

    @Test
    fun `the slow hash runs over the pepper output, not the PIN`() = runTest {
        val encoded = hasher.hash("4821".toCharArray())
        val parsed = assertNotNull(PinHash.parse(encoded))
        val unpeppered = pbkdf2("4821".toCharArray(), parsed.salt, 1_000)
        assertFalse(parsed.matches(unpeppered))
        val pepperHex = pepper.apply("4821".encodeToByteArray()).toHex().toCharArray()
        assertTrue(parsed.matches(pbkdf2(pepperHex, parsed.salt, 1_000)))
    }

    @Test
    fun `verifying with a lost pepper key fails as corrupt storage, not as a wrong PIN`() = runTest {
        val encoded = hasher.hash("4821".toCharArray())
        val lostKey = object : PinPepper {
            override fun apply(pin: ByteArray): ByteArray = pepper.apply(pin)

            override fun applyExisting(pin: ByteArray): ByteArray = throw PinPepperUnavailableException("gone")
        }
        val afterLoss = Pbkdf2PinHasher(random, Dispatchers.Unconfined, lostKey, iterations = 1_000)
        assertFailsWith<PinPepperUnavailableException> { afterLoss.verify("4821".toCharArray(), encoded) }
    }

    @Test
    fun `peppered PIN bytes are the HMAC of the UTF-8 PIN`() {
        val expected = pepper.apply("pä12".encodeToByteArray())
        assertContentEquals(expected, pepperedPin("pä12".toCharArray(), pepper, existingKeyOnly = true))
        assertContentEquals(expected, pepperedPin("pä12".toCharArray(), pepper, existingKeyOnly = false))
        assertEquals(32, expected.size)
    }

    /** A retired M1-format hash: PBKDF2 straight over the PIN chars, no pepper. */
    private fun retiredPbkdf2(pin: String, iterations: Int): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = pbkdf2(pin.toCharArray(), salt, iterations)
        return "v1\$pbkdf2-sha256\$i=$iterations\$${b64(salt)}\$${b64(hash)}"
    }

    /** A retired M1-format Argon2id encoding (the hash bytes do not matter: it is refused before hashing). */
    private fun retiredArgon2id(): String =
        "v1\$argon2id\$m=65536,t=3,p=1\$${b64(ByteArray(16))}\$${b64(ByteArray(32))}"

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(bytes)

    private fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password, salt, iterations, 256))
            .encoded

    @Test
    fun `empty PIN is rejected`() = runTest {
        assertFailsWith<IllegalArgumentException> { hasher.hash(CharArray(0)) }
    }

    @Test
    fun `constant time equals`() {
        assertTrue(ConstantTime.equals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(ConstantTime.equals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(ConstantTime.equals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertTrue(ConstantTime.equals(ByteArray(0), ByteArray(0)))
    }

    @Test
    fun `PIN chars encode as UTF-8`() {
        assertContentEquals("1234".encodeToByteArray(), "1234".toCharArray().toUtf8Bytes())
        assertContentEquals("pä".encodeToByteArray(), "pä".toCharArray().toUtf8Bytes())
    }
}

package dev.kpetriv.sprout.core.crypto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PinHasherTest {
    private val random = SecureRandom()

    // Low iteration count keeps most tests fast; one test below runs the production default.
    private val hasher = Pbkdf2PinHasher(random, Dispatchers.Unconfined, iterations = 1_000)

    @Test
    fun `default hasher verifies the right PIN and rejects a wrong one`() = runTest {
        val production = Pbkdf2PinHasher(random, Dispatchers.Unconfined)
        val encoded = production.hash("4821".toCharArray())
        assertTrue(encoded.startsWith("v1\$pbkdf2-sha256\$i=600000\$"))
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
    fun `encoded format is v1 alg params salt hash`() = runTest {
        val encoded = hasher.hash("1234".toCharArray())
        val fields = encoded.split("$")
        assertEquals(5, fields.size)
        assertEquals("v1", fields[0])
        assertEquals("pbkdf2-sha256", fields[1])
        assertEquals("i=1000", fields[2])
        assertEquals(16, Base64.getDecoder().decode(fields[3]).size)
        assertEquals(32, Base64.getDecoder().decode(fields[4]).size)

        val parsed = assertNotNull(PinHash.parse(encoded))
        assertEquals("pbkdf2-sha256", parsed.algorithm)
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
        val encoded = PinHash("argon2id", linkedMapOf("m" to 65536, "t" to 3, "p" to 1), salt, hash).encode()
        assertTrue(encoded.startsWith("v1\$argon2id\$m=65536,t=3,p=1\$"))
        val parsed = assertNotNull(PinHash.parse(encoded))
        assertEquals(listOf("m", "t", "p"), parsed.parameters.keys.toList())
        assertContentEquals(salt, parsed.salt)
        assertContentEquals(hash, parsed.hash)
    }

    @Test
    fun `malformed encodings do not parse`() {
        val valid = "v1\$pbkdf2-sha256\$i=1000\$AAECAwQFBgcICQoLDA0ODw\$AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        assertNotNull(PinHash.parse(valid))
        listOf(
            "",
            "v2\$pbkdf2-sha256\$i=1000\$AAEC\$AAEC",
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
            PinHash("pbkdf2-sha256", mapOf("i" to 1000), ByteArray(saltSize) { 1 }, ByteArray(hashSize) { 2 }).encode()

        for ((salt, hash) in listOf(16 to 16, 16 to 32, 64 to 64, 32 to 64)) {
            assertNotNull(PinHash.parse(encoded(salt, hash)), "salt $salt hash $hash")
        }
        for ((salt, hash) in listOf(15 to 32, 65 to 32, 16 to 15, 16 to 65, 1 to 32, 16 to 1, 4096 to 32, 16 to 4096)) {
            assertNull(PinHash.parse(encoded(salt, hash)), "salt $salt hash $hash")
        }
    }

    @Test
    fun `out of range hash lengths are corrupt storage for verify`() = runTest {
        val huge = PinHash("pbkdf2-sha256", mapOf("i" to 1000), ByteArray(16), ByteArray(1_000_000)).encode()
        assertFailsWith<IllegalArgumentException> { hasher.verify("1234".toCharArray(), huge) }
        assertTrue(hasher.needsRehash(huge))
    }

    @Test
    fun `verify throws on corrupt storage rather than reporting a wrong PIN`() = runTest {
        assertFailsWith<IllegalArgumentException> { hasher.verify("1234".toCharArray(), "garbage") }
        val argon = PinHash("argon2id", mapOf("m" to 65536, "t" to 3, "p" to 1), ByteArray(16), ByteArray(32))
        assertFailsWith<IllegalArgumentException> { hasher.verify("1234".toCharArray(), argon.encode()) }
        val hostile = PinHash("pbkdf2-sha256", mapOf("i" to 2_000_000_000), ByteArray(16), ByteArray(32))
        assertFailsWith<IllegalArgumentException> { hasher.verify("1234".toCharArray(), hostile.encode()) }
    }

    @Test
    fun `needsRehash flags weaker or foreign hashes`() = runTest {
        val strong = Pbkdf2PinHasher(random, Dispatchers.Unconfined, iterations = 2_000)
        val weak = hasher.hash("1234".toCharArray())
        assertTrue(strong.needsRehash(weak))
        assertFalse(hasher.needsRehash(weak))
        assertTrue(hasher.needsRehash("garbage"))
        val argon = PinHash("argon2id", mapOf("m" to 65536, "t" to 3, "p" to 1), ByteArray(16), ByteArray(32))
        assertTrue(hasher.needsRehash(argon.encode()))
    }

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

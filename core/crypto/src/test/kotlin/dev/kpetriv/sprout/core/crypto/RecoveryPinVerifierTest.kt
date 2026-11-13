package dev.kpetriv.sprout.core.crypto

import dev.kpetriv.sprout.core.model.HouseholdId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0013 §1, §4. Expected values were computed independently (Python `hashlib.pbkdf2_hmac`); the server derives the
 * same bytes from the same framing.
 */
class RecoveryPinVerifierTest {
    private val household = HouseholdId("hh-test-1")
    private val salt = ByteArray(16) { it.toByte() }

    /** The JVM verifier: no Argon2id, so PBKDF2 by default. */
    private val jvm = RecoveryPinVerifier(SecureRandom(), Dispatchers.Unconfined, argon2id = null)

    @Test
    fun `the password is the domain, the household and the PIN, separated by zero bytes`() {
        val password = RecoveryPinVerifier.password("482915".toCharArray(), household)
        assertContentEquals(
            hex("7370726f75742f7265636f766572792d70696e2f76310068682d746573742d3100343832393135"),
            password,
        )
        assertContentEquals(
            "sprout/recovery-pin/v1".encodeToByteArray() + 0 + "hh-test-1".encodeToByteArray() + 0 +
                "482915".encodeToByteArray(),
            password,
        )
    }

    @Test
    fun `PBKDF2-HMAC-SHA256 matches RFC 7914 and JCE`() {
        assertContentEquals(
            hex(
                "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                    "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            ),
            RecoveryPinVerifier.pbkdf2HmacSha256("passwd".encodeToByteArray(), "salt".encodeToByteArray(), 1, 64),
        )
        assertContentEquals(
            hex(
                "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56" +
                    "a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            ),
            RecoveryPinVerifier.pbkdf2HmacSha256(
                "Password".encodeToByteArray(),
                "NaCl".encodeToByteArray(),
                80_000,
                64,
            ),
        )
        // An ASCII password: JCE's PBKDF2 gives the same bytes.
        val jce = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec("abc123".toCharArray(), salt, 1_000, 256)).encoded
        assertContentEquals(jce, RecoveryPinVerifier.pbkdf2HmacSha256("abc123".encodeToByteArray(), salt, 1_000, 32))
    }

    @Test
    fun `the PBKDF2 verifier of a fixed PIN, household and salt is fixed`() = runTest {
        val verifier = jvm.derive("482915".toCharArray(), household, salt, "pbkdf2-sha256\$i=600000")
        assertContentEquals(hex("64338e20519247a792dd19f8f9b888bb5837d9c2c0c0ca1c7c1725b5d5a327ae"), verifier)
    }

    @Test
    fun `create makes a random 16-byte salt and a value derive finds again, for this household and PIN only`() =
        runTest {
            val value = jvm.create("482915".toCharArray(), household)
            assertEquals(16, value.salt.size)
            assertEquals(RecoveryKdf.PBKDF2_DEFAULT, value.kdf)
            assertEquals("pbkdf2-sha256\$i=600000", value.kdf.descriptor)
            val verifier = value.verifierBytes()
            assertEquals(32, verifier.size)
            val again = jvm.derive("482915".toCharArray(), household, value.salt, value.kdf.descriptor)
            assertContentEquals(verifier, again)
            assertFalse(
                verifier.contentEquals(jvm.derive("482916".toCharArray(), household, value.salt, value.kdf.descriptor)),
            )
            assertFalse(
                verifier.contentEquals(
                    jvm.derive("482915".toCharArray(), HouseholdId("hh-test-2"), value.salt, value.kdf.descriptor),
                ),
            )
            assertTrue("pbkdf2" in value.toString())
            assertFalse(verifier.toHexString() in value.toString())
            value.close()
            assertFailsWith<IllegalStateException> { value.verifierBytes() }
        }

    @Test
    fun `a device with Argon2id creates Argon2id values and passes the framing and parameters`() = runTest {
        var seen: Triple<ByteArray, ByteArray, RecoveryKdf.Argon2id>? = null
        val fake = Argon2idFunction { password, salt, kdf, size ->
            seen = Triple(password.copyOf(), salt.copyOf(), kdf)
            ByteArray(size) { 9 }
        }
        val device = RecoveryPinVerifier(SecureRandom(), Dispatchers.Unconfined, fake)
        val value = device.create("482915".toCharArray(), household)
        assertEquals("argon2id\$v=19\$m=65536,t=3,p=1", value.kdf.descriptor)
        val (password, usedSalt, kdf) = requireNotNull(seen)
        assertContentEquals(RecoveryPinVerifier.password("482915".toCharArray(), household), password)
        assertContentEquals(value.salt, usedSalt)
        assertEquals(RecoveryKdf.ARGON2ID_DEFAULT, kdf)
        assertTrue(device.supports("argon2id\$v=19\$m=131072,t=4,p=2"))
        device.derive("482915".toCharArray(), household, salt, "argon2id\$v=19\$m=131072,t=4,p=2")
        assertEquals(RecoveryKdf.Argon2id(131_072, 4, 2), seen?.third)
    }

    @Test
    fun `descriptors parse only in the server's shape and within the client's bounds`() {
        assertEquals(RecoveryKdf.ARGON2ID_DEFAULT, RecoveryKdf.parse("argon2id\$v=19\$m=65536,t=3,p=1"))
        assertEquals(RecoveryKdf.Argon2id(262_144, 10, 4), RecoveryKdf.parse("argon2id\$v=19\$m=262144,t=10,p=4"))
        assertEquals(RecoveryKdf.PBKDF2_DEFAULT, RecoveryKdf.parse("pbkdf2-sha256\$i=600000"))
        assertEquals(RecoveryKdf.Pbkdf2Sha256(10_000_000), RecoveryKdf.parse("pbkdf2-sha256\$i=10000000"))
        val refused = listOf(
            // Above the bounds.
            "argon2id\$v=19\$m=262145,t=3,p=1",
            "argon2id\$v=19\$m=65536,t=11,p=1",
            "argon2id\$v=19\$m=65536,t=3,p=5",
            // Below today's defaults.
            "argon2id\$v=19\$m=65535,t=3,p=1",
            "argon2id\$v=19\$m=65536,t=2,p=1",
            "argon2id\$v=19\$m=65536,t=3,p=0",
            "pbkdf2-sha256\$i=599999",
            "pbkdf2-sha256\$i=1000",
            // Not the shape.
            "argon2id\$v=16\$m=65536,t=3,p=1",
            "argon2i\$v=19\$m=65536,t=3,p=1",
            "argon2id\$v=19\$m=65536,t=3,p=1,x=1",
            "pbkdf2-sha256\$i=100000000",
            "pbkdf2-sha1\$i=600000",
            "pbkdf2-sha256\$i=-600000",
            "",
        )
        refused.forEach { assertNull(RecoveryKdf.parse(it), it) }
    }

    @Test
    fun `a phone without Argon2id says so and refuses to derive an Argon2id value`() = runTest {
        assertFalse(jvm.supports("argon2id\$v=19\$m=65536,t=3,p=1"))
        assertTrue(jvm.supports("pbkdf2-sha256\$i=600000"))
        assertFalse(jvm.supports("pbkdf2-sha256\$i=1"))
        assertIs<UnsupportedRecoveryKdfException>(
            assertFailsWith<IllegalArgumentException> {
                jvm.derive("482915".toCharArray(), household, salt, "argon2id\$v=19\$m=65536,t=3,p=1")
            },
        )
        assertFailsWith<UnsupportedRecoveryKdfException> {
            jvm.derive("482915".toCharArray(), household, salt, "pbkdf2-sha256\$i=1000")
        }
        assertFailsWith<IllegalArgumentException> {
            jvm.derive("482915".toCharArray(), household, ByteArray(8), "pbkdf2-sha256\$i=600000")
        }
    }

    @Test
    fun `the PIN array is left to the caller and the password copy is zeroed`() = runTest {
        var password: ByteArray? = null
        val device = RecoveryPinVerifier(
            SecureRandom(),
            Dispatchers.Unconfined,
        ) { pw, _, _, size ->
            password = pw
            ByteArray(size)
        }
        val pin = "482915".toCharArray()
        device.create(pin, household).close()
        assertContentEquals("482915".toCharArray(), pin)
        assertTrue(requireNotNull(password).all { it == 0.toByte() }, "the KDF password is zeroed after use")
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}

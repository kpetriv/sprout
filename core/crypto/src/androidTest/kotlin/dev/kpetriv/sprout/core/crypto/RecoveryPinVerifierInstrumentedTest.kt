package dev.kpetriv.sprout.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kpetriv.sprout.core.model.HouseholdId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom

/** The Argon2id path of [RecoveryPinVerifier] (ADR-0013 §1), which needs argon2kt's native library. */
@RunWith(AndroidJUnit4::class)
class RecoveryPinVerifierInstrumentedTest {
    private val household = HouseholdId("hh-test-1")

    @Test
    fun argon2ktMatchesTheReferenceVector() {
        // phc-winner-argon2 test.c: Argon2id v0x13, t=2, m=2^16, p=1, "password", "somesalt".
        val hash = Argon2KtFunction().hash(
            "password".encodeToByteArray(),
            "somesalt".encodeToByteArray(),
            RecoveryKdf.Argon2id(memoryKib = 65_536, iterations = 2, parallelism = 1),
            size = 32,
        )
        assertArrayEquals(hex("09316115d5cf24ed5a15a31a3ba326e5cf32edc24702987c02b6566f61913cf7"), hash)
    }

    @Test
    fun argon2idValuesAreCreatedAndDerivedAgain() = runTest {
        val verifier = RecoveryPinVerifier(SecureRandom(), Dispatchers.Default, Argon2KtFunction())
        val value = verifier.create("482915".toCharArray(), household)
        assertEquals("argon2id\$v=19\$m=65536,t=3,p=1", value.kdf.descriptor)
        val again = verifier.derive("482915".toCharArray(), household, value.salt, value.kdf.descriptor)
        assertArrayEquals(value.verifierBytes(), again)
        val wrong = verifier.derive("482916".toCharArray(), household, value.salt, value.kdf.descriptor)
        assertFalse(wrong.contentEquals(again))
        // A recorded PBKDF2 value (set on a phone without Argon2id) is derived with PBKDF2.
        val salt = ByteArray(16) { it.toByte() }
        val pbkdf2 = verifier.derive("482915".toCharArray(), household, salt, "pbkdf2-sha256\$i=600000")
        assertArrayEquals(hex("64338e20519247a792dd19f8f9b888bb5837d9c2c0c0ca1c7c1725b5d5a327ae"), pbkdf2)
    }

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

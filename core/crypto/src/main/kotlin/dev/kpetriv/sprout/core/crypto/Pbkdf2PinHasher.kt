package dev.kpetriv.sprout.core.crypto

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2-HMAC-SHA256 PIN hasher (OWASP 2023 guidance: 600,000 iterations), 16-byte random salt, 32-byte output,
 * over the [PinPepper] output (ADR-0004 section 3).
 *
 * Writes `v2$pbkdf2-sha256-pepper$i=<n>$<salt>$<hash>`. The 32 pepper bytes reach PBKDF2 as 64 lowercase hex
 * characters, so the password is ASCII and every JCE provider encodes it the same way. The retired M1 format
 * `v1$pbkdf2-sha256$...` (no pepper) is refused with [UnsupportedPinHashException].
 *
 * This is the JVM/test implementation and the documented fallback if Argon2id is unavailable. On device the
 * [Argon2idPinHasher] hashes new PINs and delegates PBKDF2 hashes here for verification.
 */
class Pbkdf2PinHasher(
    private val random: SecureRandom,
    private val dispatcher: CoroutineDispatcher,
    private val pepper: PinPepper,
    private val iterations: Int = DEFAULT_ITERATIONS,
) : PinHasher {
    init {
        require(iterations in 1..MAX_ITERATIONS) { "iterations out of range" }
    }

    override suspend fun hash(pin: CharArray): String {
        require(pin.isNotEmpty()) { "PIN must not be empty" }
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val derived = withContext(dispatcher) {
            derive(pepperedPassword(pin, existingKeyOnly = false), salt, iterations, HASH_SIZE)
        }
        return try {
            PinHash(PinHash.VERSION_2, ALGORITHM, mapOf(ITERATIONS_KEY to iterations), salt, derived).encode()
        } finally {
            derived.fill(0)
        }
    }

    override suspend fun verify(pin: CharArray, encoded: String): Boolean {
        val parsed = parseForVerify(encoded)
        require(handles(parsed)) { "Unsupported PIN hash algorithm" }
        val storedIterations = requireNotNull(parsed.parameters[ITERATIONS_KEY]) { "Missing iteration count" }
        require(storedIterations in 1..MAX_ITERATIONS) { "Iteration count out of range" }
        val expectedSize = parsed.hash.size
        val derived = withContext(dispatcher) {
            derive(pepperedPassword(pin, existingKeyOnly = true), parsed.salt, storedIterations, expectedSize)
        }
        return try {
            parsed.matches(derived)
        } finally {
            derived.fill(0)
        }
    }

    override fun needsRehash(encoded: String): Boolean {
        val parsed = PinHash.parse(encoded) ?: return true
        val storedIterations = parsed.parameters[ITERATIONS_KEY] ?: 0
        return parsed.version != PinHash.VERSION_2 || parsed.algorithm != ALGORITHM || storedIterations < iterations
    }

    private fun pepperedPassword(pin: CharArray, existingKeyOnly: Boolean): CharArray {
        val peppered = pepperedPin(pin, pepper, existingKeyOnly)
        return try {
            peppered.toHexChars()
        } finally {
            peppered.fill(0)
        }
    }

    /** PBKDF2 over [password], which this call zeroes. */
    private fun derive(password: CharArray, salt: ByteArray, iterations: Int, size: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, size * Byte.SIZE_BITS)
        password.fill('\u0000')
        return try {
            SecretKeyFactory.getInstance(CryptoAlgorithms.PBKDF2).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.toHexChars(): CharArray {
        val chars = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and BYTE_MASK
            chars[index * 2] = HEX_DIGITS[value ushr NIBBLE_BITS]
            chars[index * 2 + 1] = HEX_DIGITS[value and NIBBLE_MASK]
        }
        return chars
    }

    companion object {
        /** `v2` peppered PBKDF2. */
        const val ALGORITHM = "pbkdf2-sha256" + PinHash.PEPPER_SUFFIX

        const val DEFAULT_ITERATIONS = 600_000
        const val SALT_SIZE = 16
        const val HASH_SIZE = 32
        private const val ITERATIONS_KEY = "i"
        private const val HEX_DIGITS = "0123456789abcdef"
        private const val BYTE_MASK = 0xff
        private const val NIBBLE_MASK = 0x0f
        private const val NIBBLE_BITS = 4

        /** Upper bound accepted from storage, so a corrupted value cannot hang the unlock screen. */
        private const val MAX_ITERATIONS = 10_000_000

        /** True for the peppered PBKDF2 format, so [Argon2idPinHasher] knows to delegate. */
        internal fun handles(parsed: PinHash): Boolean =
            parsed.version == PinHash.VERSION_2 && parsed.algorithm == ALGORITHM
    }
}

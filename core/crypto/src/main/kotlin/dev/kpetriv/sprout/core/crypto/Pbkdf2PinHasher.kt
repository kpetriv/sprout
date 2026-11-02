package dev.kpetriv.sprout.core.crypto

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2-HMAC-SHA256 PIN hasher (OWASP 2023 guidance: 600,000 iterations), 16-byte random salt, 32-byte output.
 *
 * This is the JVM/test implementation and the documented fallback if Argon2id is unavailable. On device the
 * [Argon2idPinHasher] hashes new PINs and delegates `pbkdf2-sha256` hashes here for verification.
 */
class Pbkdf2PinHasher(
    private val random: SecureRandom,
    private val dispatcher: CoroutineDispatcher,
    private val iterations: Int = DEFAULT_ITERATIONS,
) : PinHasher {
    init {
        require(iterations in 1..MAX_ITERATIONS) { "iterations out of range" }
    }

    override suspend fun hash(pin: CharArray): String {
        require(pin.isNotEmpty()) { "PIN must not be empty" }
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val derived = withContext(dispatcher) { derive(pin, salt, iterations, HASH_SIZE) }
        return try {
            PinHash(ALGORITHM, mapOf(ITERATIONS_KEY to iterations), salt, derived).encode()
        } finally {
            derived.fill(0)
        }
    }

    override suspend fun verify(pin: CharArray, encoded: String): Boolean {
        val parsed = requireNotNull(PinHash.parse(encoded)) { "Malformed PIN hash" }
        require(parsed.algorithm == ALGORITHM) { "Unsupported PIN hash algorithm" }
        val storedIterations = requireNotNull(parsed.parameters[ITERATIONS_KEY]) { "Missing iteration count" }
        require(storedIterations in 1..MAX_ITERATIONS) { "Iteration count out of range" }
        val expectedSize = parsed.hash.size
        val derived = withContext(dispatcher) { derive(pin, parsed.salt, storedIterations, expectedSize) }
        return try {
            parsed.matches(derived)
        } finally {
            derived.fill(0)
        }
    }

    override fun needsRehash(encoded: String): Boolean {
        val parsed = PinHash.parse(encoded)
        val storedIterations = parsed?.parameters?.get(ITERATIONS_KEY) ?: 0
        return parsed?.algorithm != ALGORITHM || storedIterations < iterations
    }

    private fun derive(pin: CharArray, salt: ByteArray, iterations: Int, size: Int): ByteArray {
        val spec = PBEKeySpec(pin, salt, iterations, size * Byte.SIZE_BITS)
        return try {
            SecretKeyFactory.getInstance(CryptoAlgorithms.PBKDF2).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        const val ALGORITHM = "pbkdf2-sha256"
        const val DEFAULT_ITERATIONS = 600_000
        const val SALT_SIZE = 16
        const val HASH_SIZE = 32
        private const val ITERATIONS_KEY = "i"

        /** Upper bound accepted from storage, so a corrupted value cannot hang the unlock screen. */
        private const val MAX_ITERATIONS = 10_000_000
    }
}

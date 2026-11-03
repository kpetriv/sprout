package dev.kpetriv.sprout.core.crypto

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * Argon2id PIN hasher for devices (argon2kt, JNI). Parameters follow RFC 9106's memory-constrained profile:
 * 64 MiB, 3 passes, 1 lane, 16-byte salt, 32-byte output.
 *
 * Android only: argon2kt ships native libraries for Android ABIs and has no JVM build, so JVM tests exercise
 * [Pbkdf2PinHasher] and this class is covered by instrumented tests. Hashes in the `pbkdf2-sha256` format are
 * still verified (through [pbkdf2]) and reported by [needsRehash] so they upgrade on the next unlock.
 */
class Argon2idPinHasher(
    private val random: SecureRandom,
    private val dispatcher: CoroutineDispatcher,
    private val pbkdf2: Pbkdf2PinHasher = Pbkdf2PinHasher(random, dispatcher),
) : PinHasher {
    // TODO(M2, ADR-0004): add a Keystore HMAC pepper (non-exportable key) over the Argon2 output, so a dumped
    //  hash cannot be brute-forced off-device across the 10^4-10^6 PIN space (T7).
    private val argon2 by lazy { Argon2Kt() }

    override suspend fun hash(pin: CharArray): String {
        require(pin.isNotEmpty()) { "PIN must not be empty" }
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val derived = withContext(dispatcher) { derive(pin, salt, MEMORY_KIB, ITERATIONS, PARALLELISM, HASH_SIZE) }
        return try {
            PinHash(ALGORITHM, parametersOf(MEMORY_KIB, ITERATIONS, PARALLELISM), salt, derived).encode()
        } finally {
            derived.fill(0)
        }
    }

    override suspend fun verify(pin: CharArray, encoded: String): Boolean {
        val parsed = requireNotNull(PinHash.parse(encoded)) { "Malformed PIN hash" }
        if (parsed.algorithm == Pbkdf2PinHasher.ALGORITHM) return pbkdf2.verify(pin, encoded)
        require(parsed.algorithm == ALGORITHM) { "Unsupported PIN hash algorithm" }
        val memory = parameter(parsed, MEMORY_KEY, 1..MAX_MEMORY_KIB)
        val iterations = parameter(parsed, ITERATIONS_KEY, 1..MAX_ITERATIONS)
        val parallelism = parameter(parsed, PARALLELISM_KEY, 1..MAX_PARALLELISM)
        val expectedSize = parsed.hash.size
        val derived = withContext(dispatcher) {
            derive(pin, parsed.salt, memory, iterations, parallelism, expectedSize)
        }
        return try {
            parsed.matches(derived)
        } finally {
            derived.fill(0)
        }
    }

    override fun needsRehash(encoded: String): Boolean {
        val parsed = PinHash.parse(encoded)?.takeIf { it.algorithm == ALGORITHM } ?: return true
        val params = parsed.parameters
        return (params[MEMORY_KEY] ?: 0) < MEMORY_KIB ||
            (params[ITERATIONS_KEY] ?: 0) < ITERATIONS ||
            (params[PARALLELISM_KEY] ?: 0) < PARALLELISM
    }

    @Suppress("LongParameterList")
    private fun derive(
        pin: CharArray,
        salt: ByteArray,
        memoryKib: Int,
        iterations: Int,
        parallelism: Int,
        size: Int,
    ): ByteArray {
        // Direct buffers we own, so every copy argon2kt sees can be wiped afterwards.
        val password = directCopyOf(pin.toUtf8Bytes())
        val saltBuffer = directCopyOf(salt.copyOf())
        return try {
            val result = argon2.hash(
                mode = Argon2Mode.ARGON2_ID,
                password = password,
                salt = saltBuffer,
                tCostInIterations = iterations,
                mCostInKibibyte = memoryKib,
                parallelism = parallelism,
                hashLengthInBytes = size,
            )
            try {
                result.rawHashAsByteArray()
            } finally {
                result.rawHash.wipe()
                result.encodedOutput.wipe()
            }
        } finally {
            password.wipe()
        }
    }

    /** Copies [bytes] into a new direct buffer and zeroes [bytes]. */
    private fun directCopyOf(bytes: ByteArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(bytes.size)
        buffer.put(bytes)
        buffer.flip()
        bytes.fill(0)
        return buffer
    }

    private fun ByteBuffer.wipe() {
        clear()
        while (hasRemaining()) put(0)
    }

    private fun parameter(parsed: PinHash, key: String, range: IntRange): Int {
        val value = requireNotNull(parsed.parameters[key]) { "Missing Argon2 parameter" }
        require(value in range) { "Argon2 parameter out of range" }
        return value
    }

    private fun parametersOf(memory: Int, iterations: Int, parallelism: Int) =
        linkedMapOf(MEMORY_KEY to memory, ITERATIONS_KEY to iterations, PARALLELISM_KEY to parallelism)

    companion object {
        const val ALGORITHM = "argon2id"
        const val MEMORY_KIB = 64 * 1024
        const val ITERATIONS = 3
        const val PARALLELISM = 1
        const val SALT_SIZE = 16
        const val HASH_SIZE = 32
        private const val MEMORY_KEY = "m"
        private const val ITERATIONS_KEY = "t"
        private const val PARALLELISM_KEY = "p"

        // Upper bounds accepted from storage, so a corrupted value cannot exhaust memory or hang the unlock screen.
        private const val MAX_MEMORY_KIB = 256 * 1024
        private const val MAX_ITERATIONS = 10
        private const val MAX_PARALLELISM = 4
    }
}

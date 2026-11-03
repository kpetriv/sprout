package dev.kpetriv.sprout.core.crypto

import java.nio.CharBuffer
import java.util.Base64

/**
 * Hashes and verifies the parent PIN. Hashing is deliberately slow (hundreds of milliseconds), so both calls
 * suspend and run on a background dispatcher.
 *
 * Callers pass the PIN as a [CharArray] and should zero it afterwards; implementations zero every copy they make.
 */
interface PinHasher {
    /** Returns a self-describing encoded hash: `v1$<alg>$<params>$<saltB64>$<hashB64>`. */
    suspend fun hash(pin: CharArray): String

    /**
     * True when [pin] matches [encoded]. Compares in constant time. Throws [IllegalArgumentException] if
     * [encoded] is malformed or uses an algorithm this hasher cannot verify, since that means corrupt storage,
     * not a wrong PIN.
     */
    suspend fun verify(pin: CharArray, encoded: String): Boolean

    /** True when [encoded] uses an older algorithm or weaker parameters; re-hash after the next successful verify. */
    fun needsRehash(encoded: String): Boolean
}

/**
 * Parsed form of an encoded PIN hash: `v1$<alg>$<k=v,...>$<saltB64>$<hashB64>`. Base64 is RFC 4648 standard
 * alphabet without padding, so `$` and `,` never appear inside a field.
 */
class PinHash(
    val algorithm: String,
    /** Integer cost parameters in encoding order, for example `i=600000` or `m=65536,t=3,p=1`. */
    val parameters: Map<String, Int>,
    salt: ByteArray,
    hash: ByteArray,
) {
    private val saltBytes = salt.copyOf()
    private val hashBytes = hash.copyOf()

    val salt: ByteArray get() = saltBytes.copyOf()
    val hash: ByteArray get() = hashBytes.copyOf()

    fun encode(): String = listOf(
        VERSION,
        algorithm,
        parameters.entries.joinToString(",") { "${it.key}=${it.value}" },
        ENCODER.encodeToString(saltBytes),
        ENCODER.encodeToString(hashBytes),
    ).joinToString(SEPARATOR)

    /** Constant-time comparison of [candidate] with the stored hash. */
    fun matches(candidate: ByteArray): Boolean = ConstantTime.equals(candidate, hashBytes)

    override fun toString(): String = "PinHash(algorithm=$algorithm, parameters=$parameters, <redacted>)"

    companion object {
        const val VERSION = "v1"
        private const val SEPARATOR = "$"
        private const val FIELD_COUNT = 5
        private const val ALGORITHM_FIELD = 1
        private const val PARAMETERS_FIELD = 2
        private const val SALT_FIELD = 3
        private const val HASH_FIELD = 4
        private val ALGORITHM_PATTERN = Regex("[a-z0-9-]{1,32}")
        private val PARAMETER_PATTERN = Regex("([a-z]{1,8})=([0-9]{1,10})")
        private val ENCODER = Base64.getEncoder().withoutPadding()

        /** Salt lengths accepted from storage, in bytes. Both hashers write 16. */
        val SALT_SIZE_RANGE = 16..64

        /** Hash lengths accepted from storage, in bytes. Both hashers write 32. */
        val HASH_SIZE_RANGE = 16..64
        private val DECODER = Base64.getDecoder()

        /**
         * Parses [encoded], or returns null when it is not a well-formed `v1` hash. Salt and hash lengths must lie
         * in [SALT_SIZE_RANGE] and [HASH_SIZE_RANGE], so corrupt storage cannot ask a hasher for a huge output or
         * accept a trivially short one.
         */
        fun parse(encoded: String): PinHash? = try {
            parseStrict(encoded)
        } catch (expected: IllegalArgumentException) {
            // Includes Base64 and number-format errors.
            null
        }

        private fun parseStrict(encoded: String): PinHash {
            val fields = encoded.split(SEPARATOR)
            require(fields.size == FIELD_COUNT && fields[0] == VERSION)
            val algorithm = fields[ALGORITHM_FIELD]
            require(ALGORITHM_PATTERN.matches(algorithm))
            val parameters = LinkedHashMap<String, Int>()
            fields[PARAMETERS_FIELD].split(",").forEach { part ->
                val (name, value) = requireNotNull(PARAMETER_PATTERN.matchEntire(part)).destructured
                require(parameters.put(name, value.toInt()) == null)
            }
            val salt = DECODER.decode(fields[SALT_FIELD])
            val hash = DECODER.decode(fields[HASH_FIELD])
            require(salt.size in SALT_SIZE_RANGE && hash.size in HASH_SIZE_RANGE)
            return PinHash(algorithm, parameters, salt, hash)
        }
    }
}

/** Encodes a PIN as UTF-8 without creating an immutable String. The caller zeroes the result. */
internal fun CharArray.toUtf8Bytes(): ByteArray {
    val buffer = Charsets.UTF_8.encode(CharBuffer.wrap(this))
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    if (buffer.hasArray()) buffer.array().fill(0)
    return bytes
}

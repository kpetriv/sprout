package dev.kpetriv.sprout.core.crypto

import java.nio.CharBuffer
import java.util.Base64

/**
 * Hashes and verifies the parent PIN. Hashing is deliberately slow (hundreds of milliseconds), so both calls
 * suspend and run on a background dispatcher.
 *
 * Hashes are peppered (format `v2`): the slow hash runs over `HMAC(pepperKey, utf8(pin))` from a [PinPepper]
 * whose key never leaves the device (ADR-0004 section 3). The unpeppered M1 format `v1` is retired (no M1 build
 * shipped): [verify] refuses it with [UnsupportedPinHashException] instead of checking a PIN against it.
 *
 * Callers pass the PIN as a [CharArray] and should zero it afterwards; implementations zero every copy they make.
 */
interface PinHasher {
    /** Returns a self-describing encoded hash: `v2$<alg>-pepper$<params>$<saltB64>$<hashB64>`. */
    suspend fun hash(pin: CharArray): String

    /**
     * True when [pin] matches [encoded]. Compares in constant time. Throws [UnsupportedPinHashException] for a
     * retired `v1` hash (the PIN must be reset; it is never checked against an unpeppered hash), and
     * [IllegalArgumentException] if [encoded] is malformed or uses an algorithm this hasher cannot verify, since
     * that means corrupt storage, not a wrong PIN.
     */
    suspend fun verify(pin: CharArray, encoded: String): Boolean

    /**
     * True unless [encoded] is a well-formed `v2` hash in this hasher's algorithm with at least its current
     * parameters; re-hash after the next successful verify.
     */
    fun needsRehash(encoded: String): Boolean
}

/**
 * Parsed form of an encoded PIN hash: `v<version>$<alg>$<k=v,...>$<saltB64>$<hashB64>`. Base64 is RFC 4648
 * standard alphabet without padding, so `$` and `,` never appear inside a field.
 *
 * Version `v2` (M2) hashes the [PinPepper] output, algorithms `argon2id-pepper` and `pbkdf2-sha256-pepper`.
 * Parsing accepts `v2` with any well-formed algorithm name; each hasher accepts only the algorithms it knows. The
 * retired M1 version `v1` (unpeppered) does not parse; see [isRetiredFormat].
 */
class PinHash(
    /** Format version: always [VERSION_2]. */
    val version: Int,
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

    init {
        require(version in SUPPORTED_VERSIONS) { "Unsupported PIN hash version" }
    }

    fun encode(): String = listOf(
        "$VERSION_PREFIX$version",
        algorithm,
        parameters.entries.joinToString(",") { "${it.key}=${it.value}" },
        ENCODER.encodeToString(saltBytes),
        ENCODER.encodeToString(hashBytes),
    ).joinToString(SEPARATOR)

    /** Constant-time comparison of [candidate] with the stored hash. */
    fun matches(candidate: ByteArray): Boolean = ConstantTime.equals(candidate, hashBytes)

    override fun toString(): String =
        "PinHash(version=$version, algorithm=$algorithm, parameters=$parameters, <redacted>)"

    companion object {
        /** M2: peppered through [PinPepper]. Written and verified by every hasher. */
        const val VERSION_2 = 2

        /** M1: unpeppered. Retired: never verified, see [isRetiredFormat]. */
        private const val RETIRED_VERSION_1 = 1

        /** Suffix that marks a peppered algorithm in `v2` hashes, for example `argon2id-pepper`. */
        const val PEPPER_SUFFIX = "-pepper"
        private val SUPPORTED_VERSIONS = VERSION_2..VERSION_2
        private const val VERSION_PREFIX = "v"
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
         * True when [encoded] is in the retired, unpeppered M1 format (`v1$...`). Hashers refuse it with
         * [UnsupportedPinHashException]: the parent must set a new PIN.
         */
        fun isRetiredFormat(encoded: String): Boolean =
            encoded.startsWith("$VERSION_PREFIX$RETIRED_VERSION_1$SEPARATOR")

        /**
         * Parses [encoded], or returns null when it is not a well-formed `v2` hash. Salt and hash lengths
         * must lie in [SALT_SIZE_RANGE] and [HASH_SIZE_RANGE], so corrupt storage cannot ask a hasher for a huge
         * output or accept a trivially short one.
         */
        fun parse(encoded: String): PinHash? = try {
            parseStrict(encoded)
        } catch (expected: IllegalArgumentException) {
            // Includes Base64 and number-format errors.
            null
        }

        private fun parseStrict(encoded: String): PinHash {
            val fields = encoded.split(SEPARATOR)
            require(fields.size == FIELD_COUNT)
            val version = SUPPORTED_VERSIONS.firstOrNull { fields[0] == "$VERSION_PREFIX$it" }
            requireNotNull(version)
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
            return PinHash(version, algorithm, parameters, salt, hash)
        }
    }
}

/**
 * The stored PIN hash is in a retired format (`v1`, unpeppered, M1) that this build no longer verifies. Not a wrong
 * PIN and not corruption: the PIN has to be reset. An [IllegalArgumentException] so callers that treat unreadable
 * storage as "unavailable" keep doing so.
 */
class UnsupportedPinHashException : IllegalArgumentException("Retired PIN hash format; the PIN must be reset")

/** Throws [UnsupportedPinHashException] for a retired format, then parses or throws [IllegalArgumentException]. */
internal fun parseForVerify(encoded: String): PinHash {
    if (PinHash.isRetiredFormat(encoded)) throw UnsupportedPinHashException()
    return requireNotNull(PinHash.parse(encoded)) { "Malformed PIN hash" }
}

/**
 * The bytes a `v2` hash runs its slow function over: `pepper(utf8(pin))`. The intermediate UTF-8 copy is zeroed;
 * the caller zeroes the result. [existingKeyOnly] is true when verifying, so a lost pepper key throws
 * [PinPepperUnavailableException] instead of minting a new key.
 */
internal fun pepperedPin(pin: CharArray, pepper: PinPepper, existingKeyOnly: Boolean): ByteArray {
    val utf8 = pin.toUtf8Bytes()
    return try {
        if (existingKeyOnly) pepper.applyExisting(utf8) else pepper.apply(utf8)
    } finally {
        utf8.fill(0)
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

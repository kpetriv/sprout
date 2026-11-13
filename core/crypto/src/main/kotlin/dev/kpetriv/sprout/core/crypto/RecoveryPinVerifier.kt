package dev.kpetriv.sprout.core.crypto

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import com.lambdapioneer.argon2kt.Argon2Version
import dev.kpetriv.sprout.core.model.HouseholdId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The KDF of a recovery PIN check value (ADR-0013 §1, §4), as its descriptor string travels: `argon2id$v=19$m=65536,
 * t=3,p=1` (devices) or `pbkdf2-sha256$i=600000` (where Argon2id cannot run, and on the JVM in tests).
 */
sealed interface RecoveryKdf {
    /** The descriptor the server stores and returns. */
    val descriptor: String

    /** Argon2id version 0x13 (`v=19`): [memoryKib] KiB, [iterations] passes, [parallelism] lanes. */
    data class Argon2id(val memoryKib: Int, val iterations: Int, val parallelism: Int) : RecoveryKdf {
        override val descriptor: String get() = "argon2id\$v=19\$m=$memoryKib,t=$iterations,p=$parallelism"
    }

    /** PBKDF2-HMAC-SHA256 with [iterations]. */
    data class Pbkdf2Sha256(val iterations: Int) : RecoveryKdf {
        override val descriptor: String get() = "pbkdf2-sha256\$i=$iterations"
    }

    companion object {
        /** `Argon2idPinHasher`'s parameters: 64 MiB, 3 passes, 1 lane. */
        val ARGON2ID_DEFAULT: Argon2id = Argon2id(
            Argon2idPinHasher.MEMORY_KIB,
            Argon2idPinHasher.ITERATIONS,
            Argon2idPinHasher.PARALLELISM,
        )

        /** `Pbkdf2PinHasher`'s iteration count. */
        val PBKDF2_DEFAULT: Pbkdf2Sha256 = Pbkdf2Sha256(Pbkdf2PinHasher.DEFAULT_ITERATIONS)

        // The client's bounds (ADR-0013 §4): never above these, never below today's defaults.
        private const val MAX_ARGON2_MEMORY_KIB = 256 * 1024
        private const val MAX_ARGON2_ITERATIONS = 10
        private const val MAX_ARGON2_PARALLELISM = 4
        private const val MAX_PBKDF2_ITERATIONS = 10_000_000

        // The server's shape check (ADR-0013 §4), so a value the server would refuse never parses here either.
        private val ARGON2ID_SHAPE = Regex("argon2id\\\$v=19\\\$m=(\\d{1,7}),t=(\\d{1,2}),p=(\\d)")
        private val PBKDF2_SHAPE = Regex("pbkdf2-sha256\\\$i=(\\d{1,8})")

        /**
         * [descriptor] as a KDF this phone computes, or null when it is malformed or its parameters lie outside the
         * client's bounds: above the maxima (a value that would exhaust memory or hang the screen) or below today's
         * defaults (a weakened value).
         */
        @Suppress("ReturnCount") // One return per descriptor shape, and none matched.
        fun parse(descriptor: String): RecoveryKdf? {
            ARGON2ID_SHAPE.matchEntire(descriptor)?.let { match ->
                val (m, t, p) = match.destructured
                val kdf = Argon2id(m.toInt(), t.toInt(), p.toInt())
                return kdf.takeIf {
                    it.memoryKib in ARGON2ID_DEFAULT.memoryKib..MAX_ARGON2_MEMORY_KIB &&
                        it.iterations in ARGON2ID_DEFAULT.iterations..MAX_ARGON2_ITERATIONS &&
                        it.parallelism in ARGON2ID_DEFAULT.parallelism..MAX_ARGON2_PARALLELISM
                }
            }
            PBKDF2_SHAPE.matchEntire(descriptor)?.let { match ->
                val iterations = match.groupValues[1].toInt()
                return Pbkdf2Sha256(iterations)
                    .takeIf { iterations in PBKDF2_DEFAULT.iterations..MAX_PBKDF2_ITERATIONS }
            }
            return null
        }
    }
}

/**
 * A freshly made recovery PIN check value for `setRecoveryPin`: the random [salt] (16 bytes), the [kdf] it was
 * derived with, and the 32-byte verifier ([verifierBytes]). [close] zeroes the verifier; `toString` never shows it.
 */
class RecoveryCheckValue internal constructor(salt: ByteArray, val kdf: RecoveryKdf, verifier: ByteArray) :
    AutoCloseable {
    private val saltBytes = salt.copyOf()
    private val verifierMaterial = verifier

    @Volatile
    private var closed = false

    val salt: ByteArray get() = saltBytes.copyOf()

    /** A copy of the verifier for the caller to send and zero. */
    fun verifierBytes(): ByteArray {
        check(!closed) { "RecoveryCheckValue is closed" }
        return verifierMaterial.copyOf()
    }

    override fun close() {
        closed = true
        verifierMaterial.fill(0)
    }

    override fun toString(): String = "RecoveryCheckValue(kdf=${kdf.descriptor}, <redacted>)"
}

/** The recorded KDF descriptor is malformed, outside this phone's bounds, or one this phone cannot compute. */
class UnsupportedRecoveryKdfException : IllegalArgumentException("Unsupported recovery PIN KDF")

/** Argon2id over raw bytes (argon2kt on devices). JNI only, so it has no JVM implementation. */
fun interface Argon2idFunction {
    /** Argon2id v0x13 of [password] with [salt]; [password] is zeroed by the caller. */
    fun hash(password: ByteArray, salt: ByteArray, kdf: RecoveryKdf.Argon2id, size: Int): ByteArray
}

/**
 * Derives the recovery PIN check value of ADR-0013 (§1, §4): `verifier = KDF(password, salt)`, 32 bytes, where the
 * password is `utf8("sprout/recovery-pin/v1") || 0x00 || utf8(hid) || 0x00 || utf8(pin)` (domain-separated, bound to
 * the household, **no device pepper**: a phone that has nothing but the PIN must derive the same value).
 *
 * - [create]: a new value for `setRecoveryPin`, with 16 random salt bytes and [defaultKdf] (Argon2id where
 *   [argon2id] is available, else PBKDF2-HMAC-SHA256).
 * - [derive]: the value again from the salt and KDF descriptor the server recorded (`recoveryStatus`), within the
 *   bounds of [RecoveryKdf.parse]; [UnsupportedRecoveryKdfException] when this phone cannot compute it ([supports]).
 *
 * PBKDF2 is computed here over the raw password bytes (RFC 8018 with HMAC-SHA256), so no JCE provider's char
 * encoding can change the result. Every copy of the PIN and of the password is zeroed after use; the caller zeroes
 * the PIN it passed and the verifier it receives. Slow by design (hundreds of milliseconds): both calls run on
 * [dispatcher].
 */
class RecoveryPinVerifier(
    private val random: SecureRandom,
    private val dispatcher: CoroutineDispatcher,
    private val argon2id: Argon2idFunction?,
) {
    /** The KDF [create] uses. */
    val defaultKdf: RecoveryKdf = if (argon2id != null) RecoveryKdf.ARGON2ID_DEFAULT else RecoveryKdf.PBKDF2_DEFAULT

    /** True when [descriptor] parses within bounds and this phone can compute it. */
    fun supports(descriptor: String): Boolean = when (RecoveryKdf.parse(descriptor)) {
        null -> false
        is RecoveryKdf.Argon2id -> argon2id != null
        is RecoveryKdf.Pbkdf2Sha256 -> true
    }

    /** A new check value of [pin] for [householdId]. Does not zero [pin]. */
    suspend fun create(pin: CharArray, householdId: HouseholdId): RecoveryCheckValue {
        require(pin.isNotEmpty()) { "PIN must not be empty" }
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val verifier = withContext(dispatcher) { compute(pin, householdId, salt, defaultKdf) }
        return RecoveryCheckValue(salt, defaultKdf, verifier)
    }

    /**
     * The verifier of [pin] for [householdId] with the recorded [salt] and [kdf] descriptor. The caller zeroes the
     * result (and [pin]). Throws [UnsupportedRecoveryKdfException] for a descriptor this phone cannot compute and
     * [IllegalArgumentException] for a salt that is not 16 bytes.
     */
    suspend fun derive(pin: CharArray, householdId: HouseholdId, salt: ByteArray, kdf: String): ByteArray {
        require(pin.isNotEmpty()) { "PIN must not be empty" }
        require(salt.size == SALT_SIZE) { "salt must be $SALT_SIZE bytes" }
        if (!supports(kdf)) throw UnsupportedRecoveryKdfException()
        val parsed = RecoveryKdf.parse(kdf) ?: throw UnsupportedRecoveryKdfException()
        return withContext(dispatcher) { compute(pin, householdId, salt.copyOf(), parsed) }
    }

    private fun compute(pin: CharArray, householdId: HouseholdId, salt: ByteArray, kdf: RecoveryKdf): ByteArray {
        val password = password(pin, householdId)
        return try {
            when (kdf) {
                is RecoveryKdf.Argon2id -> requireNotNull(argon2id) { "Argon2id unavailable" }
                    .hash(password, salt, kdf, VERIFIER_SIZE)

                is RecoveryKdf.Pbkdf2Sha256 -> pbkdf2HmacSha256(password, salt, kdf.iterations, VERIFIER_SIZE)
            }
        } finally {
            password.fill(0)
        }
    }

    companion object {
        /** Domain of the KDF password (and of the server's MAC key). */
        const val DOMAIN: String = "sprout/recovery-pin/v1"
        const val SALT_SIZE: Int = 16
        const val VERIFIER_SIZE: Int = 32

        /**
         * The KDF password: `utf8(DOMAIN) || 0x00 || utf8(hid) || 0x00 || utf8(pin)`. The caller zeroes the result;
         * the intermediate UTF-8 copy of the PIN is zeroed here.
         */
        internal fun password(pin: CharArray, householdId: HouseholdId): ByteArray {
            val domain = DOMAIN.encodeToByteArray()
            val hid = householdId.value.encodeToByteArray()
            val pinBytes = pin.toUtf8Bytes()
            return try {
                ByteArray(domain.size + 1 + hid.size + 1 + pinBytes.size).also { out ->
                    domain.copyInto(out, 0)
                    hid.copyInto(out, domain.size + 1)
                    pinBytes.copyInto(out, domain.size + 1 + hid.size + 1)
                }
            } finally {
                pinBytes.fill(0)
            }
        }

        /**
         * PBKDF2 (RFC 8018 §5.2) with HMAC-SHA256 over the raw [password] bytes. `SecretKeySpec` keeps its own copy of
         * the password inside JCE, outside our control (as for every key handed to JCE, see [SecretBytes]).
         */
        internal fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, size: Int): ByteArray {
            require(iterations > 0 && size > 0)
            val mac = Mac.getInstance(CryptoAlgorithms.HMAC)
            mac.init(SecretKeySpec(password, CryptoAlgorithms.HMAC))
            val blockSize = mac.macLength
            val out = ByteArray(size)
            val u = ByteArray(blockSize)
            val t = ByteArray(blockSize)
            var block = 1
            var offset = 0
            while (offset < size) {
                mac.update(salt)
                mac.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(block).array())
                mac.doFinal(u, 0)
                u.copyInto(t)
                repeat(iterations - 1) {
                    mac.update(u)
                    mac.doFinal(u, 0)
                    for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
                }
                val take = minOf(blockSize, size - offset)
                t.copyInto(out, offset, 0, take)
                offset += take
                block += 1
            }
            u.fill(0)
            t.fill(0)
            return out
        }
    }
}

/**
 * [Argon2idFunction] over argon2kt (JNI, Android only), version 0x13. Copies the password into a direct buffer it
 * wipes, and wipes every buffer argon2kt returns.
 */
class Argon2KtFunction : Argon2idFunction {
    private val argon2 by lazy { Argon2Kt() }

    override fun hash(password: ByteArray, salt: ByteArray, kdf: RecoveryKdf.Argon2id, size: Int): ByteArray {
        val passwordBuffer = direct(password)
        val saltBuffer = direct(salt)
        return try {
            val result = argon2.hash(
                mode = Argon2Mode.ARGON2_ID,
                password = passwordBuffer,
                salt = saltBuffer,
                tCostInIterations = kdf.iterations,
                mCostInKibibyte = kdf.memoryKib,
                parallelism = kdf.parallelism,
                hashLengthInBytes = size,
                version = Argon2Version.V13,
            )
            try {
                result.rawHashAsByteArray()
            } finally {
                result.rawHash.wipe()
                result.encodedOutput.wipe()
            }
        } finally {
            passwordBuffer.wipe()
            saltBuffer.wipe()
        }
    }

    private fun direct(bytes: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(bytes.size).also { it.put(bytes).flip() }

    private fun ByteBuffer.wipe() {
        clear()
        while (hasRemaining()) put(0)
    }
}

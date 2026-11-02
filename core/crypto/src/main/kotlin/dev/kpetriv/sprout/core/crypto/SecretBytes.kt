package dev.kpetriv.sprout.core.crypto

import java.security.SecureRandom

/**
 * Secret key material that is wiped on [close]. Use with `use { }` so the bytes are zeroed as soon as the
 * caller is done. After [close] every accessor throws [IllegalStateException].
 *
 * Copies that JCE makes internally (for example inside `SecretKeySpec`) are outside our control; this type
 * only guarantees that the copy it owns does not outlive the caller's scope.
 *
 * `toString` never prints key material. Instances compare by identity, never by content, so secrets are not
 * accidentally compared in variable time.
 */
sealed class SecretBytes(private val material: ByteArray) : AutoCloseable {
    @Volatile
    private var closed = false

    /** Length of the secret in bytes. */
    val size: Int get() = material.size

    val isClosed: Boolean get() = closed

    /** Returns a copy of the secret. The caller owns the copy and must zero it (`fill(0)`) after use. */
    fun copyBytes(): ByteArray {
        check(!closed) { "${javaClass.simpleName} is closed" }
        return material.copyOf()
    }

    /** Runs [block] with the live secret, without copying. The array must not escape [block]. */
    internal fun <R> withMaterial(block: (ByteArray) -> R): R {
        check(!closed) { "${javaClass.simpleName} is closed" }
        return block(material)
    }

    override fun close() {
        closed = true
        material.fill(0)
    }

    override fun toString(): String = "${javaClass.simpleName}(size=$size, material=<redacted>)"
}

/** A symmetric key (AES-256 for [Aead]) or a stored secret such as the SQLCipher passphrase. */
class SymmetricKey private constructor(material: ByteArray) : SecretBytes(material) {
    companion object {
        const val AES_256_KEY_SIZE = 32

        /** Copies [bytes]; the caller still owns (and should zero) the array it passed in. */
        fun copyOf(bytes: ByteArray): SymmetricKey = SymmetricKey(bytes.copyOf())

        /** A fresh random key of [size] bytes from [random]. */
        fun generate(random: SecureRandom, size: Int = AES_256_KEY_SIZE): SymmetricKey {
            require(size > 0) { "size must be positive" }
            return SymmetricKey(ByteArray(size).also(random::nextBytes))
        }

        /** Takes ownership of [bytes] without copying; the array is zeroed when the key is closed. */
        internal fun adopt(bytes: ByteArray): SymmetricKey = SymmetricKey(bytes)
    }
}

/**
 * Raw output of an X25519 key agreement. Never use it directly as a key: feed it to [Hkdf] or
 * [HouseholdKeyDerivation].
 */
class SharedSecret internal constructor(material: ByteArray) : SecretBytes(material)

/** An X25519 private scalar (32 bytes, RFC 7748 encoding). Persist it only through [SecretStore]-style wrapping. */
class X25519PrivateKey private constructor(material: ByteArray) : SecretBytes(material) {
    companion object {
        const val SIZE = 32

        /** Copies [bytes]; the caller still owns (and should zero) the array it passed in. */
        fun copyOf(bytes: ByteArray): X25519PrivateKey {
            require(bytes.size == SIZE) { "X25519 private key must be $SIZE bytes" }
            return X25519PrivateKey(bytes.copyOf())
        }

        internal fun adopt(bytes: ByteArray): X25519PrivateKey {
            require(bytes.size == SIZE) { "X25519 private key must be $SIZE bytes" }
            return X25519PrivateKey(bytes)
        }
    }
}

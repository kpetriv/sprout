package dev.kpetriv.sprout.core.domainledger

import java.security.MessageDigest
import java.security.SecureRandom

/** SHA-256. Behind an interface so the domain stays KMP-ready; [JvmSha256Hasher] is the JVM implementation. */
fun interface Hasher {
    /** The 32-byte SHA-256 digest of [bytes]. */
    fun sha256(bytes: ByteArray): ByteArray
}

/** [Hasher] backed by `java.security.MessageDigest`. A new digest per call, so it is thread-safe. */
object JvmSha256Hasher : Hasher {
    override fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

/** Cryptographically secure random bytes. [JvmSecureRandomBytes] is the JVM implementation. */
fun interface RandomBytes {
    fun nextBytes(size: Int): ByteArray
}

/** [RandomBytes] backed by `java.security.SecureRandom`. */
object JvmSecureRandomBytes : RandomBytes {
    private val random = SecureRandom()

    override fun nextBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
}

private const val HEX_DIGITS = "0123456789abcdef"
private const val NIBBLE_BITS = 4
private const val NIBBLE_MASK = 0x0F

/** Lower-case hex, two characters per byte. */
fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        val value = byte.toInt()
        append(HEX_DIGITS[(value shr NIBBLE_BITS) and NIBBLE_MASK])
        append(HEX_DIGITS[value and NIBBLE_MASK])
    }
}

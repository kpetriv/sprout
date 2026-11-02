package dev.kpetriv.sprout.core.crypto

/** Parses hex, ignoring whitespace, for RFC test vectors. */
internal fun hex(value: String): ByteArray {
    val clean = value.filterNot { it.isWhitespace() }
    require(clean.length % 2 == 0)
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

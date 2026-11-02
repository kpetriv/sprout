package dev.kpetriv.sprout.core.crypto

/** Comparisons whose running time does not depend on where two secrets differ. */
internal object ConstantTime {
    /**
     * True when [a] and [b] have the same length and content. Time depends only on the lengths, which are
     * public (hash and tag sizes are fixed by the format).
     */
    fun equals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }
}

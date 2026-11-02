package dev.kpetriv.sprout.core.crypto

/**
 * Algorithm names used across Sprout's crypto layer (see docs/PLAN.md, "Security and privacy design").
 * Keystore-backed implementations arrive in M1.
 */
object CryptoAlgorithms {
    const val SYMMETRIC_CIPHER = "AES/GCM/NoPadding"
    const val SIGNATURE = "SHA256withECDSA"
    const val DIGEST = "SHA-256"
}

package dev.kpetriv.sprout.core.crypto

/**
 * Algorithm names used across Sprout's crypto layer (see docs/PLAN.md, "Security and privacy design").
 * Only these constants are passed to JCE `getInstance` calls, so a reviewer can audit every algorithm here.
 */
object CryptoAlgorithms {
    const val SYMMETRIC_CIPHER = "AES/GCM/NoPadding"
    const val SIGNATURE = "SHA256withECDSA"
    const val DIGEST = "SHA-256"
    const val HMAC = "HmacSHA256"
    const val EC = "EC"
    const val EC_CURVE = "secp256r1"
    const val AES = "AES"
    const val XDH = "XDH"
    const val PBKDF2 = "PBKDF2WithHmacSHA256"
}

/**
 * Android Keystore aliases. The version suffix lets a migration create a new key beside the old one, because
 * Keystore key parameters cannot change after creation.
 *
 * Retired aliases must never be reused for a different key policy: a device may still hold a key under them.
 * Retired so far: `sprout.parent.auth.v1` (30-second window, device credential accepted; replaced by
 * [PARENT_AUTH] in M2 under ADR-0004 before any production use).
 */
object KeyAliases {
    /** EC P-256 device signing key for ledger events and chat messages. No user authentication. */
    const val DEVICE_SIGNING = "sprout.device.signing.v1"

    /**
     * EC P-256 parent authentication key (ADR-0004): every signature needs its own strong-biometric
     * `BiometricPrompt` bound through a `CryptoObject`; device credential is not accepted.
     */
    const val PARENT_AUTH = "sprout.parent.auth.v2"

    /** HMAC-SHA256 key that peppers the parent PIN before Argon2id/PBKDF2 (ADR-0004 section 3). No user auth. */
    const val PIN_PEPPER = "sprout.pin.pepper.v1"

    /** AES-256-GCM key that wraps locally stored secrets such as the SQLCipher passphrase. */
    const val SECRET_WRAP = "sprout.secret.wrap.v1"
}

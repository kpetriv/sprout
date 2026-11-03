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

/** Android Keystore aliases. The `.v1` suffix lets a future migration create a new key beside the old one. */
object KeyAliases {
    /** EC P-256 device signing key for ledger events and chat messages. No user authentication. */
    const val DEVICE_SIGNING = "sprout.device.signing.v1"

    /** EC P-256 parent key; every use needs a recent biometric or device-credential authentication. */
    const val PARENT_AUTH = "sprout.parent.auth.v1"

    /** AES-256-GCM key that wraps locally stored secrets such as the SQLCipher passphrase. */
    const val SECRET_WRAP = "sprout.secret.wrap.v1"
}

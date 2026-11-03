package dev.kpetriv.sprout.core.crypto

import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec

/** Generates a software P-256 signing key: for JVM tests and fakes only, never for a real device key. */
fun generateSoftwareSigningKey(random: SecureRandom = SecureRandom()): SigningKeyHandle {
    val pair = KeyPairGenerator.getInstance(CryptoAlgorithms.EC).run {
        initialize(ECGenParameterSpec(CryptoAlgorithms.EC_CURVE), random)
        generateKeyPair()
    }
    return SigningKeyHandle(pair.private, PublicKeyBytes(pair.public.encoded), alias = null)
}

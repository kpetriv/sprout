package dev.kpetriv.sprout.core.crypto

import java.security.KeyStoreException
import java.security.ProviderException
import java.security.cert.Certificate

/**
 * Generates a key with an attestation challenge where Keystore can attest, and without one where it cannot.
 *
 * [generate] is called with [challenge]; when that throws [ProviderException] or [KeyStoreException] (Keystore
 * without attestation support refuses `setAttestationChallenge`), [cleanUp] removes whatever partial entry the attempt
 * left and [generate] runs again with null, so the key is created unattested. A failure of the second attempt is
 * thrown with the first attached as suppressed. Without a challenge [generate] runs once with null.
 */
internal inline fun <T> generateWithAttestationFallback(
    challenge: ByteArray?,
    cleanUp: () -> Unit,
    generate: (challenge: ByteArray?) -> T,
): T {
    if (challenge == null) return generate(null)
    val attested = try {
        Result.success(generate(challenge))
    } catch (e: ProviderException) {
        Result.failure(e)
    } catch (e: KeyStoreException) {
        Result.failure(e)
    }
    return attested.getOrElse { refusal ->
        cleanUp()
        try {
            generate(null)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            e.addSuppressed(refusal)
            throw e
        }
    }
}

/**
 * The attestation chain Keystore holds for a key ([KeyStore.getCertificateChain]), DER encoded, leaf first. Empty when
 * there is no chain or only the self-signed certificate Keystore makes for a key without attestation.
 */
internal fun attestationChainOf(chain: Array<out Certificate>?): List<ByteArray> {
    if (chain == null || chain.size < 2) return emptyList()
    return chain.map { it.encoded }
}

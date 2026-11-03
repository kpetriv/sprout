package dev.kpetriv.sprout.core.crypto

import java.security.KeyStoreException
import java.security.ProviderException

/**
 * Runs [strongBox] when [useStrongBox] is true and falls back to [tee] if StrongBox refuses the key.
 *
 * StrongBox refusals surface in several shapes: `StrongBoxUnavailableException` (a [ProviderException]), other
 * [ProviderException]s from Keystore 2 when the secure element cannot hold the key type or runs out of slots, and
 * [KeyStoreException]. Any of those (including subclasses) triggers the fallback. Before the TEE attempt
 * [cleanUp] deletes whatever partial entry the failed attempt may have left under the alias; a failure there is
 * recorded as suppressed and does not stop the fallback, since generating in the TEE replaces the entry anyway.
 *
 * Any other exception propagates unchanged. If the TEE attempt also fails, its exception propagates with the
 * StrongBox failure attached as suppressed.
 */
internal fun <T> withStrongBoxFallback(
    useStrongBox: Boolean,
    strongBox: () -> T,
    tee: () -> T,
    cleanUp: () -> Unit,
): T {
    if (!useStrongBox) return tee()
    return try {
        strongBox()
    } catch (e: ProviderException) {
        fallBackToTee(e, tee, cleanUp)
    } catch (e: KeyStoreException) {
        fallBackToTee(e, tee, cleanUp)
    }
}

private fun <T> fallBackToTee(strongBoxFailure: Exception, tee: () -> T, cleanUp: () -> Unit): T {
    try {
        cleanUp()
    } catch (e: ProviderException) {
        strongBoxFailure.addSuppressed(e)
    } catch (e: KeyStoreException) {
        strongBoxFailure.addSuppressed(e)
    }
    return runCatching(tee).onFailure { it.addSuppressed(strongBoxFailure) }.getOrThrow()
}

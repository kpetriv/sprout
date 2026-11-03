package dev.kpetriv.sprout.core.syncfirebase

/**
 * The SHA-256 of the ID token the next callable request will carry ([DeviceProof.idTokenHash]), which every device
 * proof is bound to (audit Low 2). Only the hash leaves the implementation: the token itself is never returned, kept,
 * logged or put in a `toString`. [FirebaseAuthSession] in the app, a fake in tests.
 */
fun interface IdTokenHashSource {
    /**
     * The hash of a freshly refreshed ID token, which the Functions SDK then sends (it reuses the cached token until
     * it nears expiry, an hour later). Fails with [FunctionsException]: [FunctionsError.Unauthenticated] when signed
     * out or the session is refused, [FunctionsError.Unavailable] offline.
     */
    suspend fun currentIdTokenHash(): ByteArray
}

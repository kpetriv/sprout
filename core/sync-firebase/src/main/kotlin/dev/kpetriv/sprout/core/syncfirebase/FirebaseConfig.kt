package dev.kpetriv.sprout.core.syncfirebase

/**
 * How this build talks to Firebase, provided by `app` (M3 §5, D-M3-1).
 *
 * @property emulatorHost host of a running Firebase Emulator Suite (`10.0.2.2` from an Android emulator), or null to
 *   use the real project. Only debug builds act on it (`src/debug` [EmulatorSupport]); a release build ignores it, so
 *   it can never be pointed at an emulator. In debug builds non-null also enables e-mail/password sign-in (D-M3-2).
 * @property region the Cloud Functions region (D-M3-8), used when [functionsBaseUrl] is null.
 * @property functionsBaseUrl base URL of the HTTP service that speaks the callable protocol (D-M3-29: the backend
 *   runs on a free Node host instead of Cloud Functions), for example `https://api.example.com` or, in debug builds,
 *   the local dev server `http://10.0.2.2:8787`. Callables are then `<functionsBaseUrl>/<name>`, and the Functions
 *   emulator is not used even when [emulatorHost] is set. Null: Cloud Functions in [region].
 * @property debugAppCheck install the debug App Check provider instead of Play Integrity (debug builds, D-M3-6).
 * @property googleServerClientId the OAuth web client id Google ID tokens are issued for; null until a real Firebase
 *   project exists, in which case Google sign-in fails with [AuthError.GoogleNotConfigured].
 */
data class FirebaseConfig(
    val emulatorHost: String?,
    val region: String = DEFAULT_REGION,
    val debugAppCheck: Boolean,
    val googleServerClientId: String? = null,
    val functionsBaseUrl: String? = null,
) {
    /** Where callables go: the custom domain [functionsBaseUrl] when set, else Cloud Functions in [region]. */
    internal val functionsEndpoint: FunctionsEndpoint
        get() = functionsBaseUrl?.let { FunctionsEndpoint.CustomDomain(it.trimEnd('/')) }
            ?: FunctionsEndpoint.Region(region)

    /** Whether the Functions SDK is pointed at the Functions emulator: an emulator host and no custom base URL. */
    internal val usesFunctionsEmulator: Boolean get() = emulatorHost != null && functionsBaseUrl == null

    companion object {
        const val DEFAULT_REGION: String = "europe-west1"
    }
}

/**
 * The `regionOrCustomDomain` argument of `FirebaseFunctions.getInstance`: a Cloud Functions [Region], or a
 * [CustomDomain] URL serving the callable protocol (D-M3-29).
 */
internal sealed interface FunctionsEndpoint {
    /** What `FirebaseFunctions.getInstance(app, value)` takes. */
    val value: String

    data class Region(override val value: String) : FunctionsEndpoint

    data class CustomDomain(override val value: String) : FunctionsEndpoint
}

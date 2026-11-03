package dev.kpetriv.sprout.core.syncfirebase

/**
 * How this build talks to Firebase, provided by `app` (M3 §5, D-M3-1).
 *
 * @property emulatorHost host of a running Firebase Emulator Suite (`10.0.2.2` from an Android emulator), or null to
 *   use the real project. Debug builds only; non-null also enables e-mail/password sign-in (D-M3-2).
 * @property region the Cloud Functions region (D-M3-8).
 * @property debugAppCheck install the debug App Check provider instead of Play Integrity (debug builds, D-M3-6).
 * @property googleServerClientId the OAuth web client id Google ID tokens are issued for; null until a real Firebase
 *   project exists, in which case Google sign-in fails with [AuthError.GoogleNotConfigured].
 */
data class FirebaseConfig(
    val emulatorHost: String?,
    val region: String = DEFAULT_REGION,
    val debugAppCheck: Boolean,
    val googleServerClientId: String? = null,
) {
    companion object {
        const val DEFAULT_REGION: String = "europe-west1"
    }
}

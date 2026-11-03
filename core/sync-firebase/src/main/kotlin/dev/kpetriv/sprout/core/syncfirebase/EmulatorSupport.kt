package dev.kpetriv.sprout.core.syncfirebase

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser

/**
 * The emulator-only paths (D-M3-1, D-M3-2): pointing the SDKs at a running Firebase Emulator Suite and e-mail/password
 * sign-in. The implementation is the build variant's [Emulators] object: `src/debug` does the work, `src/release` does
 * nothing and refuses the sign-in, so a release build can never be pointed at an emulator whatever its
 * [FirebaseConfig] says.
 */
internal interface EmulatorSupport {
    /**
     * Points Auth and Firestore at [FirebaseConfig.emulatorHost], and Functions too unless a
     * [FirebaseConfig.functionsBaseUrl] is set ([FirebaseConfig.usesFunctionsEmulator]). Does nothing without a host.
     * Must run before the first use of each SDK instance.
     */
    fun connect(app: FirebaseApp, config: FirebaseConfig)

    /**
     * Signs in with e-mail and password against the Auth emulator, creating the account on first use. Throws
     * [AuthException] with [AuthError.NotAvailableInThisBuild] when there is no emulator or in a release build; any
     * other failure is the SDK's (mapped to an [AuthError] by the caller).
     */
    suspend fun signInWithEmailPassword(auth: FirebaseAuth, config: FirebaseConfig, email: String, password: String)

    /**
     * Re-authenticates [user] with e-mail and password against the Auth emulator (`FirebaseUser.reauthenticate`): the
     * user is never replaced, and another account fails with `ERROR_USER_MISMATCH`. Throws [AuthException] with
     * [AuthError.NotAvailableInThisBuild] when there is no emulator or in a release build.
     */
    suspend fun reauthenticateWithEmailPassword(
        user: FirebaseUser,
        config: FirebaseConfig,
        email: String,
        password: String,
    )
}

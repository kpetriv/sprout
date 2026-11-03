package dev.kpetriv.sprout.core.syncfirebase

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth

/**
 * Release builds: no emulator support at all. [connect] never points an SDK at an emulator and e-mail/password
 * sign-in is refused, whatever [FirebaseConfig.emulatorHost] says (D-M3-2).
 */
internal object Emulators : EmulatorSupport {
    override fun connect(app: FirebaseApp, config: FirebaseConfig) = Unit

    override suspend fun signInWithEmailPassword(
        auth: FirebaseAuth,
        config: FirebaseConfig,
        email: String,
        password: String,
    ): Unit = throw AuthException(AuthError.NotAvailableInThisBuild)
}

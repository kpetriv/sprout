package dev.kpetriv.sprout.core.syncfirebase

import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await

/** Debug builds: the real [EmulatorSupport] (D-M3-1, D-M3-2). */
internal object Emulators : EmulatorSupport {
    override fun connect(app: FirebaseApp, config: FirebaseConfig) {
        val host = config.emulatorHost ?: return
        FirebaseAuth.getInstance(app).useEmulator(host, FirebaseBootstrap.AUTH_EMULATOR_PORT)
        FirebaseFirestore.getInstance(app).useEmulator(host, FirebaseBootstrap.FIRESTORE_EMULATOR_PORT)
        if (config.usesFunctionsEmulator) {
            FirebaseFunctions.getInstance(app, config.functionsEndpoint.value)
                .useEmulator(host, FirebaseBootstrap.FUNCTIONS_EMULATOR_PORT)
        }
    }

    override suspend fun signInWithEmailPassword(
        auth: FirebaseAuth,
        config: FirebaseConfig,
        email: String,
        password: String,
    ) {
        if (config.emulatorHost == null) throw AuthException(AuthError.NotAvailableInThisBuild)
        try {
            auth.signInWithEmailAndPassword(email, password).await()
        } catch (unknownOrWrong: FirebaseAuthInvalidUserException) {
            createAccount(auth, email, password, unknownOrWrong)
        } catch (unknownOrWrong: FirebaseAuthInvalidCredentialsException) {
            // With e-mail enumeration protection, an unknown account and a wrong password look the same.
            createAccount(auth, email, password, unknownOrWrong)
        }
    }

    private suspend fun createAccount(
        auth: FirebaseAuth,
        email: String,
        password: String,
        signInFailure: FirebaseException,
    ) {
        try {
            auth.createUserWithEmailAndPassword(email, password).await()
        } catch (expected: FirebaseAuthUserCollisionException) {
            // The account exists, so the sign-in failure was a wrong password.
            throw signInFailure
        }
    }
}

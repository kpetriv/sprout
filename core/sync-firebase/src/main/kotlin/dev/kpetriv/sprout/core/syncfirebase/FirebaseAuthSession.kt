package dev.kpetriv.sprout.core.syncfirebase

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kpetriv.sprout.core.model.ID_PATTERN
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Firebase Auth session (M3 §1, §5): Google sign-in for parents through Credential Manager, e-mail/password
 * against the Auth emulator in debug builds only (D-M3-2), anonymous sign-in for kid devices, and the custom claims
 * (`hid`, `role`, `kidId`) Cloud Functions set.
 *
 * Nothing here logs, and failures carry an [AuthError] code only: e-mail addresses, ID tokens and Firebase's error
 * messages (which can name the account) never leave this class.
 */
@Singleton
class FirebaseAuthSession @Inject constructor(
    private val bootstrap: FirebaseBootstrap,
    private val config: FirebaseConfig,
    @param:ApplicationContext private val context: Context,
) {
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance(bootstrap.ensureInitialised(context)) }

    /** Last claims read per uid, used when a fresh token cannot be fetched (offline with an expired token). */
    private val lastClaims = ConcurrentHashMap<String, Claims>()

    /** The session, re-read on every sign-in, sign-out and ID token change (claims refreshes included). */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<AuthState> = callbackFlow {
        val listener = FirebaseAuth.IdTokenListener { trySend(Unit) }
        auth.addIdTokenListener(listener)
        trySend(Unit)
        awaitClose { auth.removeIdTokenListener(listener) }
    }.conflate().mapLatest { current() }.distinctUntilChanged()

    /** The session now, with the claims of the cached ID token. */
    suspend fun current(): AuthState = stateOf(auth.currentUser, forceRefresh = false)

    /**
     * Signs a parent in with a Google ID token from Credential Manager. [activityContext] must be an Activity: the
     * account picker is shown over it.
     */
    suspend fun signInWithGoogle(activityContext: Context): Result<Unit> = guarded {
        val serverClientId = config.googleServerClientId ?: throw AuthException(AuthError.GoogleNotConfigured)
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(false)
            .build()
        val response = CredentialManager.create(activityContext)
            .getCredential(activityContext, GetCredentialRequest.Builder().addCredentialOption(option).build())
        val credential = response.credential
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            throw AuthException(AuthError.InvalidCredential)
        }
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
    }

    /**
     * Debug builds against the Auth emulator only ([FirebaseConfig.emulatorHost] set): signs in, creating the
     * emulator account on first use. Fails with [AuthError.NotAvailableInThisBuild] everywhere else.
     */
    suspend fun signInWithEmailPassword(email: String, password: String): Result<Unit> = guarded {
        if (config.emulatorHost == null) throw AuthException(AuthError.NotAvailableInThisBuild)
        try {
            auth.signInWithEmailAndPassword(email, password).await()
        } catch (unknownOrWrong: FirebaseAuthInvalidUserException) {
            createEmulatorAccount(email, password, unknownOrWrong)
        } catch (unknownOrWrong: FirebaseAuthInvalidCredentialsException) {
            // With e-mail enumeration protection, an unknown account and a wrong password look the same.
            createEmulatorAccount(email, password, unknownOrWrong)
        }
    }

    private suspend fun createEmulatorAccount(email: String, password: String, signInFailure: FirebaseException) {
        try {
            auth.createUserWithEmailAndPassword(email, password).await()
        } catch (expected: FirebaseAuthUserCollisionException) {
            // The account exists, so the sign-in failure was a wrong password.
            throw signInFailure
        }
    }

    /** Signs a kid device in anonymously (before it redeems a link code). */
    suspend fun signInAnonymously(): Result<Unit> = guarded { auth.signInAnonymously().await() }

    /** Forces an ID token refresh and returns the new claims (after `createHousehold` or `redeemLinkCode`). */
    suspend fun refreshClaims(): Result<Claims> = guarded {
        val user = auth.currentUser ?: throw AuthException(AuthError.SignedOut)
        when (val state = stateOf(user, forceRefresh = true)) {
            is AuthState.SignedIn -> state.claims
            AuthState.SignedOut -> throw AuthException(AuthError.SignedOut)
        }
    }

    /** Signs out of Firebase and clears Credential Manager's state so the account picker shows next time. */
    suspend fun signOut() {
        auth.signOut()
        lastClaims.clear()
        try {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (ignored: ClearCredentialException) {
            // Nothing to clear (no Google account was used) or no provider: the Firebase sign-out already happened.
        }
    }

    private suspend fun stateOf(user: FirebaseUser?, forceRefresh: Boolean): AuthState {
        if (user == null || !ID_PATTERN.matches(user.uid)) return AuthState.SignedOut
        val claims = try {
            Claims.from(user.getIdToken(forceRefresh).await().claims).also { lastClaims[user.uid] = it }
        } catch (unavailable: FirebaseException) {
            if (forceRefresh) throw unavailable
            lastClaims[user.uid] ?: Claims.NONE
        }
        return AuthState.SignedIn(UserId(user.uid), user.isAnonymous, claims)
    }

    // Every failure becomes an AuthException with a fixed code; the original exception (and its message) is dropped.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun <T> guarded(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        Result.failure(AuthException(authErrorOf(failure)))
    }

    private fun authErrorOf(failure: Exception): AuthError = when (failure) {
        is AuthException -> failure.error
        is GetCredentialCancellationException -> AuthError.Cancelled
        is NoCredentialException -> AuthError.NoCredential
        is GetCredentialException -> AuthError.Unknown
        is GoogleIdTokenParsingException -> AuthError.InvalidCredential
        is FirebaseAuthInvalidUserException -> AuthError.UserDisabled
        is FirebaseAuthInvalidCredentialsException -> AuthError.InvalidCredential
        is FirebaseNetworkException -> AuthError.Network
        is FirebaseTooManyRequestsException -> AuthError.TooManyRequests
        else -> AuthError.Unknown
    }
}

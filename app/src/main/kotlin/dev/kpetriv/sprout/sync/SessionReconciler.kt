package dev.kpetriv.sprout.sync

import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.syncfirebase.AuthState
import dev.kpetriv.sprout.core.syncfirebase.FirebaseAuthSession
import javax.inject.Inject

/** Where a gone household is reported ([SyncController.householdGone]). */
fun interface HouseholdRemovals {
    fun householdGone()
}

/** The Firebase Auth state now; a seam over [FirebaseAuthSession.current] for tests. */
fun interface AuthStateReader {
    suspend fun current(): AuthState
}

class FirebaseAuthStateReader @Inject constructor(private val auth: FirebaseAuthSession) : AuthStateReader {
    override suspend fun current(): AuthState = auth.current()
}

/**
 * Restores the stored [Session] at process start and checks it against Firebase Auth (M3 §6): a real household's
 * session whose Firebase user is signed out, is another user, or whose ID token names another household is cleared,
 * so the device goes back to onboarding instead of syncing as someone it no longer is; an account that is gone starts
 * the wipe. Missing claims (an expired
 * token read offline) are not a mismatch: the session is kept until the claims can be read. The trial session has no
 * Firebase user and is left alone.
 */
class SessionReconciler @Inject constructor(
    private val sessions: SessionStore,
    private val auth: AuthStateReader,
    private val removals: HouseholdRemovals,
) {
    /**
     * The session after reconciliation, or null when there is none (any more). A Firebase account that is gone
     * ([AuthState.UserGone]: the household was deleted, or this kid device revoked) keeps the session and reports the
     * removal instead, so the device shows the wipe screen rather than a plain sign-in.
     */
    suspend fun reconcile(): Session? {
        val session = sessions.get()
        val keep = session == null || session.isLocalTrial || when (val state = auth.current()) {
            AuthState.UserGone -> true.also { removals.householdGone() }
            else -> matches(session, state)
        }
        if (keep) return session
        sessions.clear()
        return null
    }

    private fun matches(session: Session, state: AuthState): Boolean = when (state) {
        AuthState.SignedOut, AuthState.UserGone -> false

        is AuthState.SignedIn ->
            state.uid == session.uid &&
                (state.claims.hid == null || state.claims.hid == session.householdId)
    }
}

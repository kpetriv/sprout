package dev.kpetriv.sprout.sync

import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.syncfirebase.AuthState
import dev.kpetriv.sprout.core.syncfirebase.FirebaseAuthSession
import javax.inject.Inject

/** The Firebase Auth state now; a seam over [FirebaseAuthSession.current] for tests. */
fun interface AuthStateReader {
    suspend fun current(): AuthState
}

class FirebaseAuthStateReader @Inject constructor(private val auth: FirebaseAuthSession) : AuthStateReader {
    override suspend fun current(): AuthState = auth.current()
}

/**
 * Restores the stored [Session] at process start and checks it against Firebase Auth (M3 §6, D-M3-47):
 * - the account is gone ([AuthState.UserGone]: household deleted, kid device revoked, or merely disabled): the
 *   "Sign in again" gate asks the registry first with the token-less `deviceStatus` ([CredentialCheck.credentialsLost])
 *   and wipes only on REVOKED or a household that is gone; anything else stays gated (audit L6);
 * - another account is signed in, or the ID token names another household: the session changed, the phone wipes
 *   ([RemovalReason.SIGNED_OUT]) before anything else runs;
 * - no Firebase user although a real session is stored: the credentials were lost (a revoked refresh token signs the
 *   SDK out; this app's own sign-outs wipe first), so the "Sign in again" gate checks the device's status with the
 *   token-less `deviceStatus` ([CredentialCheck.credentialsLost]) and shows no household data meanwhile.
 *
 * Missing claims (an expired token read offline) are not a mismatch. The session is returned as stored until the
 * wipe removes it. The trial session has no Firebase user and is left alone.
 */
class SessionReconciler @Inject constructor(
    private val sessions: SessionStore,
    private val auth: AuthStateReader,
    private val removals: HouseholdRemovals,
    private val credentials: CredentialCheck,
) {
    /** The session after reconciliation, or null when there is none. */
    suspend fun reconcile(): Session? {
        val session = sessions.get()
        if (session == null || session.isLocalTrial) return session
        when (val state = auth.current()) {
            AuthState.UserGone, AuthState.SignedOut -> credentials.credentialsLost()
            is AuthState.SignedIn -> if (!matches(session, state)) removals.remove(RemovalReason.SIGNED_OUT)
        }
        return session
    }

    private fun matches(session: Session, state: AuthState.SignedIn): Boolean =
        state.uid == session.uid && (state.claims.hid == null || state.claims.hid == session.householdId)
}

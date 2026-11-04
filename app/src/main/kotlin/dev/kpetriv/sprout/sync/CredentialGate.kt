package dev.kpetriv.sprout.sync

import android.content.Context
import dagger.Lazy
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.syncfirebase.AuthError
import dev.kpetriv.sprout.core.syncfirebase.AuthException
import dev.kpetriv.sprout.core.syncfirebase.AuthState
import dev.kpetriv.sprout.core.syncfirebase.DeviceStatusValue
import dev.kpetriv.sprout.core.syncfirebase.FirebaseAuthSession
import dev.kpetriv.sprout.core.syncfirebase.FirebaseConfig
import dev.kpetriv.sprout.di.ApplicationScope
import dev.kpetriv.sprout.household.HouseholdAdminFunctions
import dev.kpetriv.sprout.widget.WidgetRefresher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Lost-credential signals for the "Sign in again" gate (audit H2). */
interface CredentialCheck {
    /** A sync was refused as unauthenticated: check whether the credentials are gone (not just offline). */
    fun verify()

    /** A real session has no usable Firebase credentials any more: gate the app and ask the registry. */
    fun credentialsLost()
}

/** What the "Sign in again" gate shows. No household data is shown while it is up. */
sealed interface GateState {
    /** Asking the registry about this device (token-less `deviceStatus`). */
    data object Checking : GateState

    /** The registry could not be read: Retry. */
    data object Unreachable : GateState

    /** This device is still in the household: sign in again with the same account. */
    data class SignInRequired(
        val emailPassword: Boolean,
        val busy: Boolean = false,
        val failure: GateFailure? = null,
    ) : GateState
}

/** Why a sign-in on the gate did not get through. */
enum class GateFailure {
    /** Another account signed in; it was signed out again. */
    WRONG_ACCOUNT,
    INVALID_CREDENTIAL,
    CANCELLED,
    NETWORK,
    FAILED,
}

/** This device's registry status as the gate needs it. */
data class GateDeviceStatus(val status: DeviceStatusValue, val householdGone: Boolean)

/** The auth calls the gate makes; a seam over [FirebaseAuthSession] for tests. */
interface GateAuth {
    val usesEmailPassword: Boolean

    suspend fun current(): AuthState

    /** A forced token refresh: its claims' household, or the [AuthError] it failed with. */
    suspend fun refresh(): Result<HouseholdId?>

    suspend fun signInWithGoogle(activityContext: Context): Result<Unit>

    suspend fun signInWithEmailPassword(email: String, password: String): Result<Unit>

    /**
     * Re-authenticates the **signed-in** user (`FirebaseUser.reauthenticate`): never replaces it; another account
     * fails with [AuthError.UserMismatch].
     */
    suspend fun reauthenticateWithGoogle(activityContext: Context): Result<Unit>

    suspend fun reauthenticateWithEmailPassword(email: String, password: String): Result<Unit>

    suspend fun signOut()
}

class FirebaseGateAuth @Inject constructor(private val auth: FirebaseAuthSession, config: FirebaseConfig) : GateAuth {
    override val usesEmailPassword: Boolean = config.emulatorHost != null

    override suspend fun current(): AuthState = auth.current()

    override suspend fun refresh(): Result<HouseholdId?> = auth.refreshClaims().map { it.hid }

    override suspend fun signInWithGoogle(activityContext: Context): Result<Unit> =
        auth.signInWithGoogle(activityContext)

    override suspend fun signInWithEmailPassword(email: String, password: String): Result<Unit> =
        auth.signInWithEmailPassword(email, password)

    override suspend fun reauthenticateWithGoogle(activityContext: Context): Result<Unit> =
        auth.reauthenticateWithGoogle(activityContext)

    override suspend fun reauthenticateWithEmailPassword(email: String, password: String): Result<Unit> =
        auth.reauthenticateWithEmailPassword(email, password)

    override suspend fun signOut() = auth.signOut()
}

/**
 * The "Sign in again" gate (audit H2, D-M3-48): a real session whose credentials are gone (no Firebase user, or a
 * forced refresh refused with a credential error rather than a network error) is gated, and the device asks the
 * registry about itself with the token-less `deviceStatus` ([HouseholdAdminFunctions.deviceStatus]):
 * - REVOKED, or the household gone: removal, the phone wipes;
 * - ACTIVE or PENDING: sign in again with the **same** account, then one sync, and only then the app opens;
 * - UNKNOWN or unreachable: stays gated with Retry.
 *
 * The session cannot refresh, so there may be no current user to re-authenticate: the gate signs in, which replaces
 * the Firebase user. It does so under the [SyncPause] (audit M-1): no sync runs from the moment the sign-in starts
 * until the uid was compared, before anything else, and another account was signed out again; only then does sync
 * resume. The parent can also erase this phone's copy from the gate instead ([eraseInstead], after the confirmation
 * [SignInAgainViewModel][dev.kpetriv.sprout.navigation.SignInAgainViewModel] asks for), but not while a status check
 * or a sign-in is in flight. A phone that never comes online stays gated with its encrypted copy (THREAT_MODEL T7).
 *
 * Going behind the gate and leaving it ask the home screen widget to redraw ([WidgetRefresher], audit 2 N2), so it is
 * blank while the gate is up (ADR-0012). The gate is in memory only: after a process restart it is down until the
 * next credential check raises it again.
 */
@Singleton
@Suppress("TooManyFunctions") // One function per gate action.
class CredentialGate
@Suppress("LongParameterList") // Seams for each gate step; the injected constructor is the one the app uses.
internal constructor(
    private val scope: CoroutineScope,
    private val sessions: SessionStore,
    private val auth: GateAuth,
    private val deviceStatus: suspend () -> Result<GateDeviceStatus>,
    private val removals: HouseholdRemovals,
    private val syncNow: suspend () -> Unit,
    private val pause: SyncPause,
    private val widgets: WidgetRefresher = WidgetRefresher.None,
) : CredentialCheck {
    @Inject
    @Suppress("LongParameterList") // One collaborator per gate step.
    constructor(
        @ApplicationScope scope: CoroutineScope,
        sessions: SessionStore,
        auth: GateAuth,
        admin: Lazy<HouseholdAdminFunctions>,
        removals: HouseholdRemovals,
        sync: Lazy<SyncController>,
        pause: SyncPause,
        widgets: WidgetRefresher,
    ) : this(
        scope = scope,
        sessions = sessions,
        auth = auth,
        deviceStatus = { admin.get().deviceStatus().map { GateDeviceStatus(it.status, it.householdGone) } },
        removals = removals,
        syncNow = { sync.get().syncNow() },
        pause = pause,
        widgets = widgets,
    )

    private val mutableState = MutableStateFlow<GateState?>(null)
    private val verifying = AtomicBoolean(false)

    /** The gate, or null when the app is not gated. */
    val state: StateFlow<GateState?> = mutableState.asStateFlow()

    override fun verify() {
        if (mutableState.value != null || !verifying.compareAndSet(false, true)) return
        scope.launch {
            try {
                val session = realSession() ?: return@launch
                val lost = when (auth.current()) {
                    AuthState.SignedOut -> true
                    AuthState.UserGone -> true
                    is AuthState.SignedIn -> auth.refresh().exceptionOrNull().isCredentialError()
                }
                if (lost && session == realSession()) credentialsLost()
            } finally {
                verifying.set(false)
            }
        }
    }

    override fun credentialsLost() {
        var started = false
        change { current ->
            if (current == null || current == GateState.Unreachable) {
                started = true
                GateState.Checking
            } else {
                current
            }
        }
        if (started) scope.launch { check() }
    }

    /** Retry after [GateState.Unreachable]. */
    fun retry() {
        if (mutableState.value == GateState.Unreachable) credentialsLost()
    }

    /** True while "Erase this phone's copy instead" may be offered: the registry answered and no sign-in runs. */
    fun canEraseInstead(): Boolean = when (val gate = mutableState.value) {
        GateState.Unreachable -> true
        is GateState.SignInRequired -> !gate.busy
        GateState.Checking, null -> false
    }

    /** "Erase this phone's copy" instead of signing in (once confirmed); ignored while [canEraseInstead] is false. */
    fun eraseInstead() {
        if (canEraseInstead()) removals.remove(RemovalReason.SIGNED_OUT)
    }

    fun signInWithGoogle(activityContext: Context) = signIn { auth.signInWithGoogle(activityContext) }

    fun signInWithEmailPassword(email: String, password: String) =
        signIn { auth.signInWithEmailPassword(email, password) }

    fun dismissFailure() = change { (it as? GateState.SignInRequired)?.copy(failure = null) ?: it }

    /**
     * Moves the gate with [transform]; when that raises or lowers it, the widget redraws (blank while it is up, audit 2
     * N2). Every change of [mutableState] goes through here.
     */
    private inline fun change(transform: (GateState?) -> GateState?) {
        val previous = mutableState.getAndUpdate(transform)
        if ((previous == null) != (mutableState.value == null)) widgets.refresh()
    }

    private suspend fun check() {
        val session = realSession()
        if (session == null) {
            change { null }
            return
        }
        val answer = guarded { deviceStatus() }?.getOrNull()
        when {
            answer == null -> change { GateState.Unreachable }
            answer.householdGone -> removals.remove(RemovalReason.HOUSEHOLD_GONE)
            answer.status == DeviceStatusValue.REVOKED -> removals.remove(RemovalReason.DEVICE_REVOKED)
            answer.status == DeviceStatusValue.UNKNOWN -> change { GateState.Unreachable }
            else -> change { GateState.SignInRequired(auth.usesEmailPassword) }
        }
    }

    private fun signIn(block: suspend () -> Result<Unit>) {
        val current = mutableState.value as? GateState.SignInRequired ?: return
        if (current.busy) return
        change { current.copy(busy = true, failure = null) }
        scope.launch {
            val failure = attemptSignIn(block)
            change { state ->
                when {
                    failure == null -> null
                    state is GateState.SignInRequired -> state.copy(busy = false, failure = failure)
                    else -> state
                }
            }
        }
    }

    /** Null when signed in as the session's account in its household and synced once; the failure otherwise. */
    private suspend fun attemptSignIn(block: suspend () -> Result<Unit>): GateFailure? {
        val session = realSession() ?: return null
        val outcome = guarded { pause.pausing { signInAs(session, block) } }
            ?: SignInOutcome.Failed(GateFailure.FAILED)
        return when (outcome) {
            is SignInOutcome.Failed -> outcome.failure

            SignInOutcome.HouseholdGone -> {
                // The account no longer belongs to this household (deleted and recreated, or removed from it).
                removals.remove(RemovalReason.HOUSEHOLD_GONE)
                null
            }

            SignInOutcome.SignedIn -> {
                // Sync before anything is shown: a revocation or deletion the sync finds wipes instead of opening.
                guarded { syncNow() }
                null
            }
        }
    }

    /**
     * Runs under the [SyncPause]: signs in, then compares the uid before anything else; another account is signed out
     * again before sync can resume. Then the claims, with the session's account only.
     */
    @Suppress("ReturnCount") // One early return per way the sign-in can end.
    private suspend fun signInAs(session: Session, block: suspend () -> Result<Unit>): SignInOutcome {
        val signedIn = guarded { block() } ?: Result.failure(AuthException(AuthError.Unknown))
        val uid = (guarded { auth.current() } as? AuthState.SignedIn)?.uid
        if (uid != null && uid != session.uid) return SignInOutcome.Failed(wrongAccount())
        signedIn.exceptionOrNull()?.let { return SignInOutcome.Failed(it.toGateFailure()) }
        if (uid == null) return SignInOutcome.Failed(GateFailure.FAILED)
        val claims = guarded { auth.refresh() } ?: return SignInOutcome.Failed(GateFailure.FAILED)
        claims.exceptionOrNull()?.let { return SignInOutcome.Failed(it.toGateFailure()) }
        return if (claims.getOrNull() != session.householdId) SignInOutcome.HouseholdGone else SignInOutcome.SignedIn
    }

    private sealed interface SignInOutcome {
        data object SignedIn : SignInOutcome

        data object HouseholdGone : SignInOutcome

        data class Failed(val failure: GateFailure) : SignInOutcome
    }

    private suspend fun wrongAccount(): GateFailure {
        guarded { auth.signOut() }
        return GateFailure.WRONG_ACCOUNT
    }

    private suspend fun realSession(): Session? = guarded { sessions.get() }?.takeUnless { it.isLocalTrial }

    private fun Throwable?.isCredentialError(): Boolean = when ((this as? AuthException)?.error) {
        AuthError.UserDisabled, AuthError.InvalidCredential, AuthError.SignedOut -> true
        else -> false
    }

    private fun Throwable.toGateFailure(): GateFailure = when ((this as? AuthException)?.error) {
        AuthError.InvalidCredential, AuthError.UserDisabled -> GateFailure.INVALID_CREDENTIAL
        AuthError.Cancelled -> GateFailure.CANCELLED
        AuthError.Network, AuthError.TooManyRequests -> GateFailure.NETWORK
        else -> GateFailure.FAILED
    }

    // Nothing here may crash or log: a failure is "no answer". Dropped on purpose (it may carry personal data).
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun <T> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        null
    }
}

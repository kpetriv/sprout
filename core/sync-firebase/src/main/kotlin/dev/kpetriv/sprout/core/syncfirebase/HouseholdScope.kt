package dev.kpetriv.sprout.core.syncfirebase

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import javax.inject.Inject
import javax.inject.Qualifier

/**
 * The household [FirebaseSyncTransport] syncs. Implemented by `app` (from the session); [ClaimsHouseholdScope] reads
 * it straight from the ID token's claims.
 */
fun interface HouseholdScope {
    /** The household of the signed-in user, or null when signed out or not (yet) a member of one. */
    suspend fun currentHouseholdId(): HouseholdId?

    /**
     * This device's registry id (its key fingerprint), or null when unknown. The transport reads this device's own
     * `devices/{deviceId}` entry to confirm a revocation before reporting the household gone; without it that check
     * is skipped. `app` implements it from the session.
     */
    suspend fun currentDeviceId(): DeviceId? = null
}

/** [HouseholdScope] from the `hid` custom claim of the signed-in Firebase user (no device id). */
class ClaimsHouseholdScope @Inject constructor(private val session: FirebaseAuthSession) : HouseholdScope {
    override suspend fun currentHouseholdId(): HouseholdId? = (session.current() as? AuthState.SignedIn)?.claims?.hid
}

/**
 * What [FirebaseSyncTransport] asks the auth session before it reports the household gone: positive evidence only.
 * [FirebaseAuthSession] implements it.
 */
fun interface AuthProbe {
    /**
     * The session after a **forced** ID-token refresh: [AuthState.UserGone] when the account was deleted or disabled,
     * [AuthState.SignedIn] with fresh claims otherwise; null when the refresh could not be made (offline, a revoked
     * session that must sign in again), which is not evidence of anything.
     */
    suspend fun refreshedState(): AuthState?
}

/**
 * Qualifies the `Flow<Boolean>` that is true while the app is in the foreground (`ProcessLifecycleOwner`, provided by
 * `app`). [FirebaseSyncTransport.changes] keeps snapshot listeners open only while it is true.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppForeground

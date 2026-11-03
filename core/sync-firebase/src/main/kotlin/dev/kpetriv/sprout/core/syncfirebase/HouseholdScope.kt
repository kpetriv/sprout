package dev.kpetriv.sprout.core.syncfirebase

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
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

    /**
     * The account the synced session belongs to, or null when unknown. A forced token refresh's claims count as
     * evidence about the household only when they are this account's ([FirebaseSyncTransport]'s membership check):
     * another account's claims (one signed in over the session) say nothing about it. `app` implements it from the
     * session; without it the claims are never evidence.
     */
    suspend fun currentUserId(): UserId? = null

    /**
     * The kid a KID session belongs to (its `kidId` claim), or null for a parent session or when unknown. KID members
     * may read only their own kid's chat thread, and the rules refuse a query they cannot prove stays inside it, so
     * the transport filters its `messages` reads and listener on `threadId == kidId`. When this is null the transport
     * falls back to this device's own registry entry ([currentDeviceId]); a KID session with neither cannot pull
     * messages (the read is refused). `app` implements it from the session.
     */
    suspend fun currentKidId(): KidId? = null
}

/** [HouseholdScope] from the `hid` custom claim and uid of the signed-in Firebase user (no device id). */
class ClaimsHouseholdScope @Inject constructor(private val session: FirebaseAuthSession) : HouseholdScope {
    override suspend fun currentHouseholdId(): HouseholdId? = (session.current() as? AuthState.SignedIn)?.claims?.hid

    override suspend fun currentUserId(): UserId? = (session.current() as? AuthState.SignedIn)?.uid

    override suspend fun currentKidId(): KidId? =
        (session.current() as? AuthState.SignedIn)?.claims?.takeIf { it.role == Role.KID }?.kidId
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

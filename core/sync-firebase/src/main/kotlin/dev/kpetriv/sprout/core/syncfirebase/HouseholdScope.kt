package dev.kpetriv.sprout.core.syncfirebase

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
}

/** [HouseholdScope] from the `hid` custom claim of the signed-in Firebase user. */
class ClaimsHouseholdScope @Inject constructor(private val session: FirebaseAuthSession) : HouseholdScope {
    override suspend fun currentHouseholdId(): HouseholdId? = (session.current() as? AuthState.SignedIn)?.claims?.hid
}

/**
 * Qualifies the `Flow<Boolean>` that is true while the app is in the foreground (`ProcessLifecycleOwner`, provided by
 * `app`). [FirebaseSyncTransport.changes] keeps snapshot listeners open only while it is true.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppForeground

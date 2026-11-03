package dev.kpetriv.sprout.sync

import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.syncfirebase.HouseholdScope
import javax.inject.Inject

/**
 * [HouseholdScope] from the stored session: the household it belongs to, its account (only that account's claims are
 * evidence about the household) and this device's registry id, so the transport can confirm its own device's
 * revocation before reporting the household gone. Nothing for the trial
 * household, which never syncs.
 */
class SessionHouseholdScope @Inject constructor(private val sessions: SessionStore) : HouseholdScope {
    override suspend fun currentHouseholdId(): HouseholdId? = sessions.get()?.takeUnless {
        it.isLocalTrial
    }?.householdId

    override suspend fun currentDeviceId(): DeviceId? = sessions.get()?.takeUnless { it.isLocalTrial }?.deviceId

    override suspend fun currentUserId(): UserId? = sessions.get()?.takeUnless { it.isLocalTrial }?.uid
}

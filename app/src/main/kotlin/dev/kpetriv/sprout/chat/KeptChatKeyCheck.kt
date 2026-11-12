package dev.kpetriv.sprout.chat

import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.database.ChatIdentityKeeper
import dev.kpetriv.sprout.core.database.KeptChatIdentity
import dev.kpetriv.sprout.core.database.deviceId
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.syncfirebase.DeviceProof
import dev.kpetriv.sprout.core.syncfirebase.DeviceStatusAnswer
import dev.kpetriv.sprout.core.syncfirebase.DeviceStatusValue
import dev.kpetriv.sprout.core.syncfirebase.FunctionsError
import dev.kpetriv.sprout.core.syncfirebase.FunctionsException
import dev.kpetriv.sprout.core.syncfirebase.HouseholdFunctions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Keeps the privacy promise of a sign-out (D-REL-28, PRIVACY "Data on a phone"): a signed-out phone keeps its chat key
 * only until Sprout is set up again on it, it is removed from the household, or it is uninstalled. At a start with no
 * session, a kept key (its record names the household and the device, [ChatIdentityKeeper]) is checked with the
 * token-less, device-signed `deviceStatus`: REVOKED, UNKNOWN (the proof fails or there is no such device) or a
 * household that is gone deletes it, and a stash of the same device with it, so none of the four names is left. A
 * record of another device than this phone's current one can never be restored and goes at once. A stash is checked
 * the same way; one whose tag names no household (a key no sign-out kept: a registration that did not finish) or that
 * has no readable tag can never be checked or restored, and goes too (D-REL-32).
 *
 * The answers are bounded ([TIMEOUT_MS]) and best effort: no answer (offline, server trouble) keeps the key, and the
 * next start asks again. Onboarding may stash, restore or replace the key while the check waits (D-REL-30): every
 * deletion goes through [ChatIdentityKeeper.forgetIf] or [ChatIdentityKeeper.dropStashIf], which delete only while the
 * record or the stash is still the one that was checked, under the keeper's lock. Nothing is logged.
 */
class KeptChatKeyCheck internal constructor(
    private val identity: ChatIdentityKeeper,
    private val currentDevice: suspend () -> DeviceId,
    private val status: suspend (HouseholdId, DeviceId) -> Result<DeviceStatusAnswer>,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    @Inject
    constructor(identity: ChatIdentityKeeper, signer: DeviceSigner, functions: HouseholdFunctions) : this(
        identity = identity,
        currentDevice = { signer.deviceId() },
        status = { householdId, deviceId ->
            val signature = signer.sign(DeviceProof.deviceStatusPayload(householdId, deviceId))
            functions.deviceStatus(householdId, deviceId, signature)
        },
    )

    /** Runs the check once; returns what it deleted, for tests. */
    suspend operator fun invoke(): Outcome {
        val device = currentDevice()
        val record = identity.kept()
        val stashed = identity.hasStash()
        val tag = if (stashed) identity.stashed() else null
        // Only this device's households can be asked about: deviceStatus is signed with the current key.
        val households = listOfNotNull(record, tag).filter { it.deviceId == device }.mapNotNull { it.householdId }
        val gone = mutableMapOf<HouseholdId, Boolean>()
        withTimeoutOrNull(timeoutMs) {
            households.distinct().forEach { gone[it] = isGone(it, device) }
        }
        val recordGone = record != null && record.isGone(device, gone)
        val stashGone = stashed && (tag == null || tag.isGone(device, gone))
        return Outcome(
            liveForgotten = recordGone && record != null && identity.forgetIf(record),
            stashDropped = stashGone && identity.dropStashIf(tag),
        )
    }

    /** What one check deleted. */
    data class Outcome(val liveForgotten: Boolean, val stashDropped: Boolean)

    /** Another device's, without a household (nothing to ask), or answered gone; no answer is not gone. */
    private fun KeptChatIdentity.isGone(device: DeviceId, answers: Map<HouseholdId, Boolean>): Boolean =
        deviceId != device || householdId == null || answers[householdId] == true

    @Suppress("TooGenericExceptionCaught") // No answer is not an answer; nothing is logged.
    private suspend fun isGone(householdId: HouseholdId, device: DeviceId): Boolean {
        val result = try {
            status(householdId, device)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Result.failure(failure)
        }
        // A key the registry does not hold fails its proof: the device is not in the household (UNKNOWN).
        val refused = (result.exceptionOrNull() as? FunctionsException)?.error == FunctionsError.PermissionDenied
        val gone = result.getOrNull()?.let {
            it.householdGone || it.status == DeviceStatusValue.REVOKED || it.status == DeviceStatusValue.UNKNOWN
        }
        return refused || gone == true
    }

    companion object {
        /** How long the answers may take before the key is kept until the next start. */
        const val TIMEOUT_MS: Long = 20_000
    }
}

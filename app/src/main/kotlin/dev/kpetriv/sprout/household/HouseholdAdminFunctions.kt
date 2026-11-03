package dev.kpetriv.sprout.household

import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.database.HouseholdPolicyRepository
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.syncfirebase.DeviceProof
import dev.kpetriv.sprout.core.syncfirebase.FunctionsError
import dev.kpetriv.sprout.core.syncfirebase.FunctionsException
import dev.kpetriv.sprout.core.syncfirebase.HouseholdFunctions
import dev.kpetriv.sprout.core.syncfirebase.LinkCode
import dev.kpetriv.sprout.core.syncfirebase.ParentKeyRegistration
import dev.kpetriv.sprout.core.syncfirebase.RevokedDevice
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

/**
 * The parent callables that carry this device's proof (D-M3-34): each signs the exact bytes of core/sync-firebase's
 * [DeviceProof] for the signed-in household with this device's key ([DeviceSigner]) and passes the session's device
 * as `callerDeviceId`. Failures are [FunctionsException] codes only; the trial household (no backend) is
 * [FunctionsError.Unauthenticated]. Nothing here logs; the link code stays inside [LinkCode] (whose `toString`
 * hides it).
 */
class HouseholdAdminFunctions @Inject constructor(
    private val functions: HouseholdFunctions,
    private val signer: DeviceSigner,
    private val sessions: SessionStore,
    private val policies: HouseholdPolicyRepository,
) {
    suspend fun createLinkCode(kidId: KidId): Result<LinkCode> = withSession { session ->
        val signature = signer.sign(DeviceProof.createLinkCodePayload(session.householdId, kidId))
        functions.createLinkCode(kidId, session.deviceId, signature)
    }

    suspend fun revokeDevice(deviceId: DeviceId): Result<RevokedDevice> = withSession { session ->
        val signature = signer.sign(DeviceProof.revokeDevicePayload(session.householdId, deviceId))
        functions.revokeDevice(deviceId, session.deviceId, signature)
    }

    suspend fun exportHousehold(): Result<JsonObject> = withSession { session ->
        val signature = signer.sign(DeviceProof.exportHouseholdPayload(session.householdId))
        functions.exportHousehold(session.deviceId, signature)
    }

    /** Deletes the signed-in household; the confirmation is its id. */
    suspend fun deleteHousehold(): Result<Unit> = withSession { session ->
        val signature = signer.sign(DeviceProof.deleteHouseholdPayload(session.householdId))
        functions.deleteHousehold(session.householdId, session.deviceId, signature)
    }

    /**
     * Registers this device's parent authorisation key ([publicKey], X.509 SPKI DER) with the server's registry
     * (clients cannot write `parentKeys`, D-M3-34), then records the server's `validFromLamport` locally
     * ([HouseholdPolicyRepository.recordServerRegisteredKey]) so later parent signatures are covered on every device.
     */
    suspend fun registerParentKey(publicKey: ByteArray): Result<ParentKeyRegistration> = withSession { session ->
        val signature = signer.sign(DeviceProof.registerParentKeyPayload(session.householdId, publicKey))
        functions.registerParentKey(publicKey, attestationChain = null, deviceId = session.deviceId, signature)
            .onSuccess { policies.recordServerRegisteredKey(it.fingerprint, it.validFromLamport) }
    }

    private suspend fun <T> withSession(block: suspend (Session) -> Result<T>): Result<T> {
        val session = sessions.get()?.takeUnless { it.isLocalTrial }
            ?: return Result.failure(FunctionsException(FunctionsError.Unauthenticated))
        return block(session)
    }
}

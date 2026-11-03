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
import dev.kpetriv.sprout.feature.lock.ParentKeyChallengeSource
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
     *
     * [attestationChain] is the key's Keystore attestation (DER, leaf first) for the challenge [registrationChallenge]
     * issued; when it is empty (Keystore could not attest) no `attestationChain` field is sent, and the server applies
     * its rule for unattested keys (accepted as `NONE` only in the emulator).
     */
    suspend fun registerParentKey(
        publicKey: ByteArray,
        attestationChain: List<ByteArray>,
    ): Result<ParentKeyRegistration> = withSession { session ->
        val signature = signer.sign(DeviceProof.registerParentKeyPayload(session.householdId, publicKey))
        functions.registerParentKey(
            publicKey,
            attestationChain = attestationChain.takeIf { it.isNotEmpty() },
            deviceId = session.deviceId,
            signature,
        ).onSuccess { policies.recordServerRegisteredKey(it.fingerprint, it.validFromLamport) }
    }

    /**
     * A fresh attestation challenge for the parent key (`beginDeviceRegistration`; its only effect is replacing the
     * caller's `challenges/{uid}`, so no dedicated callable is needed). Null in the trial household, which has no
     * server: the key is then created without attestation.
     */
    suspend fun registrationChallenge(): Result<ByteArray>? {
        if (sessions.get()?.takeUnless { it.isLocalTrial } == null) return null
        return functions.beginDeviceRegistration()
    }

    private suspend fun <T> withSession(block: suspend (Session) -> Result<T>): Result<T> {
        val session = sessions.get()?.takeUnless { it.isLocalTrial }
            ?: return Result.failure(FunctionsException(FunctionsError.Unauthenticated))
        return block(session)
    }
}

/**
 * `feature/lock`'s [ParentKeyChallengeSource] over [HouseholdAdminFunctions.registrationChallenge]: null in the trial
 * household; a failed call throws its [FunctionsException], so the key change fails before any key is touched.
 */
class ServerParentKeyChallengeSource @Inject constructor(private val admin: HouseholdAdminFunctions) :
    ParentKeyChallengeSource {
    override suspend fun registrationChallenge(): ByteArray? = admin.registrationChallenge()?.getOrThrow()
}

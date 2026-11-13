package dev.kpetriv.sprout.household

import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.database.HouseholdPolicyRepository
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.sync.RecoveredParentDevice
import dev.kpetriv.sprout.core.sync.RecoveryStatus
import dev.kpetriv.sprout.core.sync.StoredRecoveryPin
import dev.kpetriv.sprout.core.syncfirebase.DeviceProof
import dev.kpetriv.sprout.core.syncfirebase.DeviceStatusAnswer
import dev.kpetriv.sprout.core.syncfirebase.FunctionsError
import dev.kpetriv.sprout.core.syncfirebase.FunctionsException
import dev.kpetriv.sprout.core.syncfirebase.HouseholdFunctions
import dev.kpetriv.sprout.core.syncfirebase.IdTokenHashSource
import dev.kpetriv.sprout.core.syncfirebase.LinkCode
import dev.kpetriv.sprout.core.syncfirebase.ParentKeyRegistration
import dev.kpetriv.sprout.core.syncfirebase.PrunedMessages
import dev.kpetriv.sprout.core.syncfirebase.RegisteredEncryptionKey
import dev.kpetriv.sprout.core.syncfirebase.RevokedDevice
import dev.kpetriv.sprout.feature.lock.ParentKeyChallengeSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import kotlin.time.Instant

/**
 * The parent callables that carry this device's proof (D-M3-34): each signs the exact bytes of core/sync-firebase's
 * [DeviceProof] for the signed-in household with this device's key ([DeviceSigner]) and passes the session's device
 * as `callerDeviceId` (`approverDeviceId`, `deviceId`). Every proof is bound to the ID token the call carries: the
 * hash comes from [IdTokenHashSource], which never hands out the token itself. Failures are [FunctionsException]
 * codes only; the trial household (no backend) is [FunctionsError.Unauthenticated]. Nothing here logs; the link code
 * stays inside [LinkCode] (whose `toString` hides it).
 *
 * The token is refreshed just before signing, so the SDK sends that token; should something else refresh it in
 * between, the server refuses the proof with `permission-denied`, and the call is signed and sent once more.
 */
@Suppress("TooManyFunctions") // One function per device-signed callable.
class HouseholdAdminFunctions @Inject constructor(
    private val functions: HouseholdFunctions,
    private val signer: DeviceSigner,
    private val sessions: SessionStore,
    private val policies: HouseholdPolicyRepository,
    private val idTokens: IdTokenHashSource,
) {
    suspend fun createLinkCode(kidId: KidId): Result<LinkCode> = withProof { session, tokenHash ->
        val signature = signer.sign(DeviceProof.createLinkCodePayload(session.householdId, tokenHash, kidId))
        functions.createLinkCode(kidId, session.deviceId, signature)
    }

    /** Approves the PENDING parent device [deviceId] from this ACTIVE parent device. */
    suspend fun approveDevice(deviceId: DeviceId): Result<Unit> = withProof { session, tokenHash ->
        val signature = signer.sign(DeviceProof.approveDevicePayload(session.householdId, tokenHash, deviceId))
        functions.approveDevice(deviceId, session.deviceId, signature)
    }

    suspend fun revokeDevice(deviceId: DeviceId): Result<RevokedDevice> = withProof { session, tokenHash ->
        val signature = signer.sign(DeviceProof.revokeDevicePayload(session.householdId, tokenHash, deviceId))
        functions.revokeDevice(deviceId, session.deviceId, signature)
    }

    suspend fun exportHousehold(): Result<JsonObject> = withProof { session, tokenHash ->
        val signature = signer.sign(DeviceProof.exportHouseholdPayload(session.householdId, tokenHash))
        functions.exportHousehold(session.deviceId, signature)
    }

    /**
     * Asks the server to delete the household's chat messages dated before [before], and the chat blob chunks only
     * they named (M7 §2, D-M7-8, ADR-0011): with [purge] false the household's retention (the server refuses a cut
     * later than its own policy allows, and answers [FunctionsError.FailedPrecondition] when the household keeps
     * messages); with [purge] true "Delete chat history now" at [before]. PARENT devices only. One call deletes at
     * most 5,000 documents and says [PrunedMessages.more]: this calls again, each time with a fresh proof, up to
     * [maxCalls] times (the server allows 10 calls an hour per account; the caller keeps the budget). [onCall] runs
     * before every request sent, a proof retry included. Returns the last answer with the totals.
     */
    @Suppress("ReturnCount") // A failed call, the last page, or the call budget used up.
    suspend fun pruneMessages(
        before: Instant,
        purge: Boolean,
        maxCalls: Int = MAX_PRUNE_CALLS,
        onCall: () -> Unit = {},
    ): Result<PrunedMessages> {
        var messages = 0
        var blobChunks = 0
        repeat(maxCalls) {
            val answer = withProof { session, tokenHash ->
                onCall()
                val payload = DeviceProof.pruneMessagesPayload(session.householdId, tokenHash, before, purge)
                val signature = signer.sign(payload)
                functions.pruneMessages(before, purge, session.deviceId, signature)
            }.getOrElse { return Result.failure(it) }
            messages += answer.messages
            blobChunks += answer.blobChunks
            if (!answer.more) return Result.success(answer.copy(messages = messages, blobChunks = blobChunks))
        }
        return Result.success(PrunedMessages(messages, blobChunks, more = true, before = before.toEpochMilliseconds()))
    }

    /**
     * Deletes the signed-in household; the confirmation is its id. The server requires a sign-in (ID token
     * `auth_time`) within the last 5 minutes and answers [FunctionsError.FailedPrecondition] otherwise: sign in again.
     */
    suspend fun deleteHousehold(): Result<Unit> = withProof { session, tokenHash ->
        val signature = signer.sign(DeviceProof.deleteHouseholdPayload(session.householdId, tokenHash))
        functions.deleteHousehold(session.householdId, session.deviceId, signature)
    }

    /**
     * This device's registry status, asked with its key's signature and no ID token
     * ([HouseholdFunctions.deviceStatus]), so it works when the account was disabled (a revoked kid device) or deleted
     * (with its household). Needs only the local session; [FunctionsError.Unauthenticated] in the trial household.
     */
    suspend fun deviceStatus(): Result<DeviceStatusAnswer> = withSession { session ->
        val signature = signer.sign(DeviceProof.deviceStatusPayload(session.householdId, session.deviceId))
        functions.deviceStatus(session.householdId, session.deviceId, signature)
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
    ): Result<ParentKeyRegistration> = withProof { session, tokenHash ->
        val signature = signer.sign(DeviceProof.registerParentKeyPayload(session.householdId, tokenHash, publicKey))
        functions.registerParentKey(
            publicKey,
            attestationChain = attestationChain.takeIf { it.isNotEmpty() },
            deviceId = session.deviceId,
            signature,
        ).onSuccess { policies.recordServerRegisteredKey(it.fingerprint, it.validFromLamport) }
    }

    /**
     * Registers this device's X25519 encryption key (M4 §1.1, D-M4-2; parent and kid devices alike): [publicKey] the
     * raw 32 bytes and [bindingSignature] this device key's signature over `EncryptionKeyBindingPayload` of the
     * session's household and device. The proof binds the call to the ID token. The same key again is a success with
     * `created = false`; a different key already registered for this device is [FunctionsError.FailedPrecondition]
     * (D-M4-14).
     */
    suspend fun registerEncryptionKey(
        publicKey: ByteArray,
        bindingSignature: ByteArray,
    ): Result<RegisteredEncryptionKey> = withProof { session, tokenHash ->
        val signature = signer.sign(DeviceProof.registerEncryptionKeyPayload(session.householdId, tokenHash, publicKey))
        functions.registerEncryptionKey(publicKey, bindingSignature, session.deviceId, signature)
    }

    /**
     * Stores the account's recovery PIN check value from this ACTIVE parent device (ADR-0013 §1): [salt], [kdf] and
     * [verifier] as `RecoveryPinVerifier` made them, [newPin] when the PIN was just chosen or changed. Answers the
     * attempts left. Never log the arguments.
     */
    suspend fun setRecoveryPin(
        salt: ByteArray,
        kdf: String,
        verifier: ByteArray,
        newPin: Boolean,
    ): Result<StoredRecoveryPin> = withProof { session, tokenHash ->
        // The signed bytes hold the verifier: zeroed once signed.
        val payload = DeviceProof.setRecoveryPinPayload(session.householdId, tokenHash, salt, kdf, verifier, newPin)
        val signature = try {
            signer.sign(payload)
        } finally {
            payload.fill(0)
        }
        functions.setRecoveryPin(salt, kdf, verifier, newPin, session.deviceId, signature)
    }

    /**
     * The recovery options of this PENDING parent device (ADR-0013 §2), signed by its own key: the session's device is
     * the PENDING one while it waits.
     */
    suspend fun recoveryStatus(): Result<RecoveryStatus> = withProof { session, tokenHash ->
        val signature = signer.sign(DeviceProof.recoveryStatusPayload(session.householdId, tokenHash))
        functions.recoveryStatus(session.deviceId, signature)
    }

    /**
     * Recovers this PENDING parent device with the PIN's [verifier], or by waiting when it is null (ADR-0013 §2). A PIN
     * attempt is sent once per call, never again here, not even after a bare `permission-denied`: one tap never spends
     * two attempts. A wait call is retried after a bare `permission-denied` (a proof bound to a token refreshed
     * meanwhile) as every proof is. An answer for another device than this one is a malformed answer.
     */
    suspend fun recoverParentDevice(verifier: ByteArray?): Result<RecoveredParentDevice> =
        withProof(retryRefusedProof = verifier == null) { session, tokenHash ->
            val payload = DeviceProof.recoverParentDevicePayload(session.householdId, tokenHash, verifier)
            val signature = try {
                signer.sign(payload)
            } finally {
                payload.fill(0)
            }
            functions.recoverParentDevice(verifier, session.deviceId, signature).mapCatching { recovered ->
                if (recovered.deviceId != session.deviceId) throw FunctionsException(FunctionsError.MalformedResponse)
                recovered
            }
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

    /**
     * [block] with the session and the hash of the ID token the call will carry; once more with a new hash when the
     * server refuses the proof (`permission-denied` changes nothing on the server). One proof call at a time, so two
     * of them never refresh the token under each other.
     */
    private suspend fun <T> withProof(
        retryRefusedProof: Boolean = true,
        block: suspend (Session, ByteArray) -> Result<T>,
    ): Result<T> = withSession { session ->
        proofCalls.withLock {
            val first = signedAttempt(session, block)
            if (retryRefusedProof && first.isPermissionDenied()) signedAttempt(session, block) else first
        }
    }

    private suspend fun <T> signedAttempt(
        session: Session,
        block: suspend (Session, ByteArray) -> Result<T>,
    ): Result<T> {
        val tokenHash = try {
            idTokens.currentIdTokenHash()
        } catch (failure: FunctionsException) {
            return Result.failure(failure)
        }
        return block(session, tokenHash)
    }

    private fun Result<*>.isPermissionDenied(): Boolean =
        (exceptionOrNull() as? FunctionsException)?.error == FunctionsError.PermissionDenied

    private companion object {
        val proofCalls = Mutex()

        /** Default calls of one [pruneMessages] (5,000 documents each); the rest goes at the next prune. */
        const val MAX_PRUNE_CALLS = 4
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

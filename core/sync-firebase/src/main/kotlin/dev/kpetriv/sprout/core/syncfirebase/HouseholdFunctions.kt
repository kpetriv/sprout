package dev.kpetriv.sprout.core.syncfirebase

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.sync.RecoveredParentDevice
import dev.kpetriv.sprout.core.sync.RecoveryDeviceStatus
import dev.kpetriv.sprout.core.sync.RecoveryMethod
import dev.kpetriv.sprout.core.sync.RecoveryPinRecord
import dev.kpetriv.sprout.core.sync.RecoveryRefusal
import dev.kpetriv.sprout.core.sync.RecoveryRefusedException
import dev.kpetriv.sprout.core.sync.RecoveryStatus
import dev.kpetriv.sprout.core.sync.StoredRecoveryPin
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.encoding.Base64
import kotlin.time.Instant

/**
 * Typed wrappers for the household callables (M3 §1, `server/src/index.ts`), at [FirebaseConfig.functionsEndpoint].
 * The privileged ones carry a device proof (`callerDeviceId`, `approverDeviceId` or `deviceId`, and `signature`): the
 * calling device's signature over the bytes [DeviceProof] builds (D-M3-34), bound to the ID token the call carries.
 * [deviceStatus] alone is sent without an ID token.
 *
 * Requests and responses are kotlinx-serialised data classes converted to plain maps at the SDK boundary. Every call
 * returns a [Result] whose failure is a [FunctionsException]; public keys, attestation certificates and signatures
 * travel as standard base64 (the backend decodes them strictly).
 */
@Suppress("TooManyFunctions") // One typed wrapper per callable of the server.
@Singleton
class HouseholdFunctions @Inject constructor(private val invoker: CallableInvoker) {

    /**
     * Creates a household with the caller as its first parent and this device ACTIVE; then refresh the claims.
     *
     * [encryptionKey] is optional: its binding names the household id, which the server only assigns here, so a
     * device normally registers its encryption key with [registerEncryptionKey] once it knows the id. One whose
     * binding does not verify for the new household is refused (`permission-denied`) and nothing is created.
     */
    suspend fun createHousehold(
        fiatCurrency: String,
        key: DeviceKeyRegistration,
        encryptionKey: EncryptionKeyUpload? = null,
    ): Result<CreatedHousehold> = call(
        CREATE_HOUSEHOLD,
        CreateHouseholdRequest(
            fiatCurrency,
            key.publicKeyBase64(),
            key.attestationChainBase64(),
            encryptionKey?.publicKeyBase64(),
            encryptionKey?.signatureBase64(),
        ),
        CreateHouseholdRequest.serializer(),
        CreatedHousehold.serializer(),
    )

    /** A fresh 32-byte challenge (10 minutes) for `setAttestationChallenge` on the key about to be registered. */
    suspend fun beginDeviceRegistration(): Result<ByteArray> =
        call(BEGIN_DEVICE_REGISTRATION, EmptyRequest(), EmptyRequest.serializer(), ChallengeResponse.serializer())
            .mapCatching { response ->
                decodeBase64(response.challenge) ?: throw FunctionsException(FunctionsError.MalformedResponse)
            }

    /**
     * Registers this device PENDING for a parent who already has a household (second phone, T8), optionally with its
     * [encryptionKey] (bound to the caller's household; verified against the submitted device key).
     */
    suspend fun registerParentDevice(
        key: DeviceKeyRegistration,
        encryptionKey: EncryptionKeyUpload? = null,
    ): Result<PendingDevice> = call(
        REGISTER_PARENT_DEVICE,
        RegisterDeviceRequest(
            key.publicKeyBase64(),
            key.attestationChainBase64(),
            encryptionKey?.publicKeyBase64(),
            encryptionKey?.signatureBase64(),
        ),
        RegisterDeviceRequest.serializer(),
        PendingDevice.serializer(),
    )

    /**
     * Approves a PENDING parent device from this ACTIVE parent device ([approverDeviceId]); [signature] is the
     * approver key's ECDSA signature over [DeviceProof.approveDevicePayload] of [deviceId].
     */
    suspend fun approveDevice(deviceId: DeviceId, approverDeviceId: DeviceId, signature: ByteArray): Result<Unit> =
        call(
            APPROVE_DEVICE,
            ApproveDeviceRequest(deviceId, approverDeviceId, Base64.Default.encode(signature)),
            ApproveDeviceRequest.serializer(),
            ApprovedDevice.serializer(),
        ).map { }

    /**
     * A single-use 8-character code (10 minutes) that links a kid device to [kidId]. Never log it. [signature] is
     * this ACTIVE parent device's ([callerDeviceId]) signature over [DeviceProof.createLinkCodePayload].
     */
    suspend fun createLinkCode(kidId: KidId, callerDeviceId: DeviceId, signature: ByteArray): Result<LinkCode> = call(
        CREATE_LINK_CODE,
        CreateLinkCodeRequest(kidId, callerDeviceId, Base64.Default.encode(signature)),
        CreateLinkCodeRequest.serializer(),
        LinkCode.serializer(),
    )

    /**
     * Redeems a link code from an anonymous kid session; then refresh the claims. [encryptionKey] is optional and,
     * as for [createHousehold], only verifies if its binding names the household the code belongs to; otherwise
     * register it with [registerEncryptionKey] after joining.
     */
    suspend fun redeemLinkCode(
        code: String,
        key: DeviceKeyRegistration,
        encryptionKey: EncryptionKeyUpload? = null,
    ): Result<RedeemedLink> = call(
        REDEEM_LINK_CODE,
        RedeemLinkCodeRequest(
            code,
            key.publicKeyBase64(),
            key.attestationChainBase64(),
            encryptionKey?.publicKeyBase64(),
            encryptionKey?.signatureBase64(),
        ),
        RedeemLinkCodeRequest.serializer(),
        RedeemedLink.serializer(),
    )

    /**
     * Registers this device's X25519 encryption key (M4 §1.1, D-M4-2), from a parent or a kid device that is ACTIVE.
     * [publicKey] is the raw 32-byte key, [bindingSignature] the device key's signature over core/domain-ledger
     * `EncryptionKeyBindingPayload.of(hid, deviceId, publicKey)`, [deviceId] this device and [proofSignature] its
     * signature over [DeviceProof.registerEncryptionKeyPayload]. Registering the same key again answers
     * `created = false`; a different key on the device is `failed-precondition` (a changed key needs a new device
     * registration).
     */
    suspend fun registerEncryptionKey(
        publicKey: ByteArray,
        bindingSignature: ByteArray,
        deviceId: DeviceId,
        proofSignature: ByteArray,
    ): Result<RegisteredEncryptionKey> = call(
        REGISTER_ENCRYPTION_KEY,
        RegisterEncryptionKeyRequest(
            Base64.Default.encode(publicKey),
            Base64.Default.encode(bindingSignature),
            deviceId,
            Base64.Default.encode(proofSignature),
        ),
        RegisterEncryptionKeyRequest.serializer(),
        RegisteredEncryptionKey.serializer(),
    )

    /**
     * Revokes [deviceId] (T7); [callerDeviceId] is this device, which cannot revoke itself, and [signature] its
     * signature over [DeviceProof.revokeDevicePayload] of [deviceId].
     */
    suspend fun revokeDevice(
        deviceId: DeviceId,
        callerDeviceId: DeviceId,
        signature: ByteArray,
    ): Result<RevokedDevice> = call(
        REVOKE_DEVICE,
        RevokeDeviceRequest(deviceId, callerDeviceId, Base64.Default.encode(signature)),
        RevokeDeviceRequest.serializer(),
        RevokedDevice.serializer(),
    )

    /**
     * The household's data as one JSON bundle (D-M3-7); `resource-exhausted` when it is too large. [signature] is
     * [callerDeviceId]'s signature over [DeviceProof.exportHouseholdPayload].
     */
    suspend fun exportHousehold(callerDeviceId: DeviceId, signature: ByteArray): Result<JsonObject> = call(
        EXPORT_HOUSEHOLD,
        ProvenRequest(callerDeviceId, Base64.Default.encode(signature)),
        ProvenRequest.serializer(),
        JsonObject.serializer(),
        BULK_TIMEOUT_MS,
    )

    /**
     * Deletes the household and every member's account; [confirmation] must be the household id, the caller must
     * have signed in within the last 5 minutes, and [signature] is [callerDeviceId]'s signature over
     * [DeviceProof.deleteHouseholdPayload].
     */
    suspend fun deleteHousehold(
        confirmation: HouseholdId,
        callerDeviceId: DeviceId,
        signature: ByteArray,
    ): Result<Unit> = call(
        DELETE_HOUSEHOLD,
        DeleteHouseholdRequest(confirmation, callerDeviceId, Base64.Default.encode(signature)),
        DeleteHouseholdRequest.serializer(),
        DeletedHousehold.serializer(),
        BULK_TIMEOUT_MS,
    ).map { }

    /**
     * Asks the server to delete the household's chat messages and chat blob chunks it stored before [before] (M7 §2,
     * D-M7-8, D-M7-52: the server's own stamp decides, not the author's date): the retention cut, which must be no
     * later than `now − chatRetentionDays` plus five minutes of clock skew (`invalid-argument` otherwise, D-M7-51; any
     * earlier cut is accepted, so a phone that just shortened the retention pushes the policy first), or with [purge]
     * any instant up to now ("Delete chat history now"). [signature] is [callerDeviceId]'s (an ACTIVE parent device)
     * signature over [DeviceProof.pruneMessagesPayload]. `failed-precondition` when the household keeps messages and
     * [purge] is false; [PrunedMessages.more] asks for another call. The server then raises the household's chat prune
     * watermark, which the transport pulls ([PullPage.chatPrunedBefore][dev.kpetriv.sprout.core.sync.PullPage]).
     */
    suspend fun pruneMessages(
        before: Instant,
        purge: Boolean,
        callerDeviceId: DeviceId,
        signature: ByteArray,
    ): Result<PrunedMessages> = call(
        PRUNE_MESSAGES,
        PruneMessagesRequest(before.toEpochMilliseconds(), purge, callerDeviceId, Base64.Default.encode(signature)),
        PruneMessagesRequest.serializer(),
        PrunedMessages.serializer(),
        BULK_TIMEOUT_MS,
    )

    /**
     * Registers a parent authorisation key with the server (D-M3-34; clients cannot write `parentKeys`): [publicKey]
     * is its X.509 SPKI DER, [attestationChain] its Keystore attestation (leaf first; null where the key has none,
     * accepted only by a backend that allows unattested keys), [deviceId] this ACTIVE parent device and [signature]
     * its signature over [DeviceProof.registerParentKeyPayload]. The server sets `validFromLamport`; pass the result
     * to `HouseholdPolicyRepository.recordServerRegisteredKey`. Registering the same key again from the same device
     * returns the stored registration.
     */
    suspend fun registerParentKey(
        publicKey: ByteArray,
        attestationChain: List<ByteArray>?,
        deviceId: DeviceId,
        signature: ByteArray,
    ): Result<ParentKeyRegistration> {
        val key = DeviceKeyRegistration(publicKey, attestationChain)
        return call(
            REGISTER_PARENT_KEY,
            RegisterParentKeyRequest(
                key.publicKeyBase64(),
                key.attestationChainBase64(),
                deviceId,
                Base64.Default.encode(signature),
            ),
            RegisterParentKeyRequest.serializer(),
            ParentKeyRegistration.serializer(),
        )
    }

    /**
     * This device's registry status, asked without an ID token (works signed out and after the account was disabled
     * or deleted): [signature] is the device key's signature over [DeviceProof.deviceStatusPayload] of
     * [householdId] and [deviceId]. `permission-denied` when the signature does not verify against the registered
     * key; [DeviceStatusValue.UNKNOWN] when the device or household does not exist, with
     * [DeviceStatusAnswer.householdGone] when the household was deleted.
     */
    suspend fun deviceStatus(
        householdId: HouseholdId,
        deviceId: DeviceId,
        signature: ByteArray,
    ): Result<DeviceStatusAnswer> = call(
        DEVICE_STATUS,
        DeviceStatusRequest(householdId, deviceId, Base64.Default.encode(signature)),
        DeviceStatusRequest.serializer(),
        DeviceStatusResponse.serializer(),
        authenticated = false,
    ).mapCatching { response ->
        val status = DeviceStatusValue.entries.firstOrNull { it.name == response.status }
            ?: throw FunctionsException(FunctionsError.MalformedResponse)
        DeviceStatusAnswer(status, response.householdGone)
    }

    /**
     * Stores the account's recovery PIN check value (ADR-0013 §1, §4) from this ACTIVE parent device
     * ([callerDeviceId]); [newPin] says the PIN was just chosen or changed (the server restarts the failed attempts);
     * [signature] is the device's signature over [DeviceProof.setRecoveryPinPayload]. Answers whether the value was
     * stored and the attempts left on the value the server holds (0..10). Never log the arguments.
     */
    @Suppress("LongParameterList") // The check value's four parts and the proof.
    suspend fun setRecoveryPin(
        salt: ByteArray,
        kdf: String,
        verifier: ByteArray,
        newPin: Boolean,
        callerDeviceId: DeviceId,
        signature: ByteArray,
    ): Result<StoredRecoveryPin> = call(
        SET_RECOVERY_PIN,
        SetRecoveryPinRequest(
            Base64.Default.encode(salt),
            kdf,
            Base64.Default.encode(verifier),
            newPin,
            callerDeviceId,
            Base64.Default.encode(signature),
        ),
        SetRecoveryPinRequest.serializer(),
        SetRecoveryPinResponse.serializer(),
    ).mapCatching { response ->
        if (!response.set || response.attemptsLeft !in 0..MAX_RECOVERY_ATTEMPTS) {
            throw FunctionsException(FunctionsError.MalformedResponse)
        }
        StoredRecoveryPin(response.stored, response.attemptsLeft)
    }

    /**
     * The recovery options of this PENDING parent device ([deviceId], ADR-0013 §2); [signature] is that device's own
     * signature over [DeviceProof.recoveryStatusPayload]. Refusals with a reason fail with [RecoveryRefusedException].
     */
    suspend fun recoveryStatus(deviceId: DeviceId, signature: ByteArray): Result<RecoveryStatus> = call(
        RECOVERY_STATUS,
        RecoveryRequestBody(deviceId, Base64.Default.encode(signature)),
        RecoveryRequestBody.serializer(),
        RecoveryStatusResponse.serializer(),
    ).mapCatching { response ->
        val pin = response.pin?.let { parameters ->
            val salt = decodeBase64(parameters.salt) ?: throw FunctionsException(FunctionsError.MalformedResponse)
            RecoveryPinRecord(salt, parameters.kdf)
        }
        if (response.attemptsLeft !in 0..MAX_RECOVERY_ATTEMPTS) {
            throw FunctionsException(FunctionsError.MalformedResponse)
        }
        RecoveryStatus(pin, response.attemptsLeft, response.waitEndsAt?.let(Instant::fromEpochMilliseconds))
    }.mapRecoveryRefusal()

    /**
     * Recovers this PENDING parent device ([deviceId], ADR-0013 §2) with the PIN's [verifier], or by waiting when it
     * is null; [signature] is that device's own signature over [DeviceProof.recoverParentDevicePayload] of the same
     * [verifier]. Refusals with a reason (`recent-sign-in`, `wrong-pin`, `pin-unavailable`, `wait`, `not-pending`)
     * fail with [RecoveryRefusedException]; anything else with [FunctionsException]. The first `WAIT` call starts the
     * wait and answers `wait` (a [RecoveryRefusal.Wait]). A success revokes every session of the account
     * ([RecoveredParentDevice.signInAgain]); `internal` may mean the revocation did not finish, and calling again
     * finishes it.
     */
    suspend fun recoverParentDevice(
        verifier: ByteArray?,
        deviceId: DeviceId,
        signature: ByteArray,
    ): Result<RecoveredParentDevice> = call(
        RECOVER_PARENT_DEVICE,
        RecoveryRequestBody(
            deviceId,
            Base64.Default.encode(signature),
            method = if (verifier != null) METHOD_PIN else METHOD_WAIT,
            verifier = verifier?.let { Base64.Default.encode(it) },
        ),
        RecoveryRequestBody.serializer(),
        RecoveredDeviceResponse.serializer(),
    ).mapCatching { response ->
        val method = RecoveryMethod.entries.firstOrNull { it.name == response.method }
            ?: throw FunctionsException(FunctionsError.MalformedResponse)
        val valid = response.status == DeviceStatusValue.ACTIVE.name &&
            response.revokedDevices >= 0 && response.attemptsLeft in 0..MAX_RECOVERY_ATTEMPTS
        if (!valid) throw FunctionsException(FunctionsError.MalformedResponse)
        RecoveredParentDevice(
            response.deviceId,
            response.revokedDevices,
            method,
            response.attemptsLeft,
            response.signInAgain,
        )
    }.mapRecoveryRefusal()

    @Suppress("SwallowedException") // A decoding message may quote response data; only the code is kept.
    private suspend fun <Req, Res> call(
        name: String,
        request: Req,
        requestSerializer: KSerializer<Req>,
        responseSerializer: KSerializer<Res>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        authenticated: Boolean = true,
    ): Result<Res> = try {
        val data = PlainValues.toPlainMap(json.encodeToJsonElement(requestSerializer, request) as JsonObject)
        val response = if (authenticated) {
            invoker.call(name, data, timeoutMs)
        } else {
            invoker.callWithoutAuth(name, data, timeoutMs)
        }
        Result.success(json.decodeFromJsonElement(responseSerializer, PlainValues.toJson(response, true)))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: FunctionsException) {
        Result.failure(failure)
    } catch (malformed: SerializationException) {
        Result.failure(FunctionsException(FunctionsError.MalformedResponse))
    } catch (malformed: IllegalArgumentException) {
        // An id in the response that fails its own validation (HouseholdId, DeviceId, ...).
        Result.failure(FunctionsException(FunctionsError.MalformedResponse))
    }

    companion object {
        const val CREATE_HOUSEHOLD: String = "createHousehold"
        const val BEGIN_DEVICE_REGISTRATION: String = "beginDeviceRegistration"
        const val REGISTER_PARENT_DEVICE: String = "registerParentDevice"
        const val APPROVE_DEVICE: String = "approveDevice"
        const val CREATE_LINK_CODE: String = "createLinkCode"
        const val REDEEM_LINK_CODE: String = "redeemLinkCode"
        const val REGISTER_PARENT_KEY: String = "registerParentKey"
        const val REGISTER_ENCRYPTION_KEY: String = "registerEncryptionKey"
        const val REVOKE_DEVICE: String = "revokeDevice"
        const val EXPORT_HOUSEHOLD: String = "exportHousehold"
        const val DELETE_HOUSEHOLD: String = "deleteHousehold"
        const val DEVICE_STATUS: String = "deviceStatus"
        const val PRUNE_MESSAGES: String = "pruneMessages"
        const val SET_RECOVERY_PIN: String = "setRecoveryPin"
        const val RECOVERY_STATUS: String = "recoveryStatus"
        const val RECOVER_PARENT_DEVICE: String = "recoverParentDevice"

        /** `recoverParentDevice`'s `method` values. */
        const val METHOD_PIN: String = "PIN"
        const val METHOD_WAIT: String = "WAIT"

        private const val MAX_RECOVERY_ATTEMPTS = 10

        private const val DEFAULT_TIMEOUT_MS = 30_000L

        /** exportHousehold, deleteHousehold and pruneMessages run up to 300 s on the server. */
        private const val BULK_TIMEOUT_MS = 300_000L

        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

        private fun decodeBase64(text: String): ByteArray? = try {
            Base64.Default.decode(text)
        } catch (expected: IllegalArgumentException) {
            null
        }
    }
}

/**
 * A [FunctionsException] whose `details.reason` is one of ADR-0013's (§4 "Errors") becomes a
 * [RecoveryRefusedException]; anything else stays as it is. A reason with a missing or malformed parameter stays a
 * [FunctionsException].
 */
internal fun <T> Result<T>.mapRecoveryRefusal(): Result<T> = recoverCatching { failure ->
    throw (failure as? FunctionsException)?.recoveryRefusal()?.let(::RecoveryRefusedException) ?: failure
}

@Suppress("CyclomaticComplexMethod") // One branch per reason of ADR-0013 §4.
internal fun FunctionsException.recoveryRefusal(): RecoveryRefusal? {
    val number = { name: String -> (details[name] as? Number)?.toDouble()?.takeIf { it % 1.0 == 0.0 }?.toLong() }
    return when {
        error == FunctionsError.FailedPrecondition && reason == "recent-sign-in" -> RecoveryRefusal.RecentSignIn

        error == FunctionsError.FailedPrecondition && reason == "pin-unavailable" -> RecoveryRefusal.PinUnavailable

        error == FunctionsError.FailedPrecondition && reason == "other-parent" -> RecoveryRefusal.OtherParent

        error == FunctionsError.PermissionDenied && reason == "wrong-pin" ->
            number("attemptsLeft")
                ?.takeIf { it in 0..MAX_RECOVERY_ATTEMPTS_LEFT }
                ?.let { RecoveryRefusal.WrongPin(it.toInt()) }

        error == FunctionsError.FailedPrecondition && reason == "wait" ->
            number("waitEndsAt")?.let { RecoveryRefusal.Wait(Instant.fromEpochMilliseconds(it)) }

        error == FunctionsError.FailedPrecondition && reason == "not-pending" -> RecoveryRefusal.NotPending(
            when (details["status"]) {
                "ACTIVE" -> RecoveryDeviceStatus.ACTIVE
                "REVOKED" -> RecoveryDeviceStatus.REVOKED
                else -> RecoveryDeviceStatus.OTHER
            },
        )

        else -> null
    }
}

/** The most attempts a recovery PIN has (ADR-0013 §2). */
private const val MAX_RECOVERY_ATTEMPTS_LEFT = 10L

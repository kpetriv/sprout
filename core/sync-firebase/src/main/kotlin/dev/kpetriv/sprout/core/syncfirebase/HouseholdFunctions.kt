package dev.kpetriv.sprout.core.syncfirebase

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.encoding.Base64

/**
 * Typed wrappers for the household callables (M3 §1, `functions/src/index.ts`), all in [FirebaseConfig.region].
 *
 * Requests and responses are kotlinx-serialised data classes converted to plain maps at the SDK boundary. Every call
 * returns a [Result] whose failure is a [FunctionsException]; public keys, attestation certificates and signatures
 * travel as standard base64 (the backend decodes them strictly).
 */
@Singleton
class HouseholdFunctions @Inject constructor(private val invoker: CallableInvoker) {

    /** Creates a household with the caller as its first parent and this device ACTIVE; then refresh the claims. */
    suspend fun createHousehold(fiatCurrency: String, key: DeviceKeyRegistration): Result<CreatedHousehold> = call(
        CREATE_HOUSEHOLD,
        CreateHouseholdRequest(fiatCurrency, key.publicKeyBase64(), key.attestationChainBase64()),
        CreateHouseholdRequest.serializer(),
        CreatedHousehold.serializer(),
    )

    /** A fresh 32-byte challenge (10 minutes) for `setAttestationChallenge` on the key about to be registered. */
    suspend fun beginDeviceRegistration(): Result<ByteArray> =
        call(BEGIN_DEVICE_REGISTRATION, EmptyRequest(), EmptyRequest.serializer(), ChallengeResponse.serializer())
            .mapCatching { response ->
                decodeBase64(response.challenge) ?: throw FunctionsException(FunctionsError.MalformedResponse)
            }

    /** Registers this device PENDING for a parent who already has a household (second phone, T8). */
    suspend fun registerParentDevice(key: DeviceKeyRegistration): Result<PendingDevice> = call(
        REGISTER_PARENT_DEVICE,
        RegisterDeviceRequest(key.publicKeyBase64(), key.attestationChainBase64()),
        RegisterDeviceRequest.serializer(),
        PendingDevice.serializer(),
    )

    /**
     * Approves a PENDING parent device from this ACTIVE parent device ([approverDeviceId]); [signature] is the
     * approver key's ECDSA signature over [approvalPayload] of [deviceId].
     */
    suspend fun approveDevice(deviceId: DeviceId, approverDeviceId: DeviceId, signature: ByteArray): Result<Unit> =
        call(
            APPROVE_DEVICE,
            ApproveDeviceRequest(deviceId, approverDeviceId, Base64.Default.encode(signature)),
            ApproveDeviceRequest.serializer(),
            ApprovedDevice.serializer(),
        ).map { }

    /** A single-use 8-character code (10 minutes) that links a kid device to [kidId]. Never log it. */
    suspend fun createLinkCode(kidId: KidId): Result<LinkCode> =
        call(CREATE_LINK_CODE, CreateLinkCodeRequest(kidId), CreateLinkCodeRequest.serializer(), LinkCode.serializer())

    /** Redeems a link code from an anonymous kid session; then refresh the claims. */
    suspend fun redeemLinkCode(code: String, key: DeviceKeyRegistration): Result<RedeemedLink> = call(
        REDEEM_LINK_CODE,
        RedeemLinkCodeRequest(code, key.publicKeyBase64(), key.attestationChainBase64()),
        RedeemLinkCodeRequest.serializer(),
        RedeemedLink.serializer(),
    )

    /** Revokes [deviceId] (T7); [callerDeviceId] is this device, which cannot revoke itself. */
    suspend fun revokeDevice(deviceId: DeviceId, callerDeviceId: DeviceId): Result<RevokedDevice> = call(
        REVOKE_DEVICE,
        RevokeDeviceRequest(deviceId, callerDeviceId),
        RevokeDeviceRequest.serializer(),
        RevokedDevice.serializer(),
    )

    /** The household's data as one JSON bundle (D-M3-7); `resource-exhausted` when it is too large. */
    suspend fun exportHousehold(): Result<JsonObject> =
        call(EXPORT_HOUSEHOLD, EmptyRequest(), EmptyRequest.serializer(), JsonObject.serializer(), BULK_TIMEOUT_MS)

    /** Deletes the household and every member's account; [confirmation] must be the household id. */
    suspend fun deleteHousehold(confirmation: HouseholdId): Result<Unit> = call(
        DELETE_HOUSEHOLD,
        DeleteHouseholdRequest(confirmation),
        DeleteHouseholdRequest.serializer(),
        DeletedHousehold.serializer(),
        BULK_TIMEOUT_MS,
    ).map { }

    @Suppress("SwallowedException") // A decoding message may quote response data; only the code is kept.
    private suspend fun <Req, Res> call(
        name: String,
        request: Req,
        requestSerializer: KSerializer<Req>,
        responseSerializer: KSerializer<Res>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Result<Res> = try {
        val data = json.encodeToJsonElement(requestSerializer, request) as JsonObject
        val response = invoker.call(name, PlainValues.toPlainMap(data), timeoutMs)
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
        const val REVOKE_DEVICE: String = "revokeDevice"
        const val EXPORT_HOUSEHOLD: String = "exportHousehold"
        const val DELETE_HOUSEHOLD: String = "deleteHousehold"

        /** Domain of the device-approval signature (`functions/src/crypto.ts` `APPROVE_DEVICE_DOMAIN`). */
        const val APPROVE_DEVICE_DOMAIN: String = "sprout/approve-device/v1"

        /** The bytes an approving parent device signs: `"sprout/approve-device/v1" || 0x00 || deviceId` (UTF-8). */
        fun approvalPayload(deviceId: DeviceId): ByteArray =
            APPROVE_DEVICE_DOMAIN.encodeToByteArray() + 0.toByte() + deviceId.value.encodeToByteArray()

        private const val DEFAULT_TIMEOUT_MS = 30_000L

        /** exportHousehold and deleteHousehold run up to 300 s on the server. */
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

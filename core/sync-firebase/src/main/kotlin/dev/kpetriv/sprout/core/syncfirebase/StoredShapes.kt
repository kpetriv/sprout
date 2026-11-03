package dev.kpetriv.sprout.core.syncfirebase

import dev.kpetriv.sprout.core.domainledger.AttestationLevel
import dev.kpetriv.sprout.core.domainledger.BoundEncryptionKey
import dev.kpetriv.sprout.core.domainledger.DeviceOwner
import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.RegisteredParentKey
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.ID_PATTERN
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.sync.BlobChunkJson
import dev.kpetriv.sprout.core.sync.DocumentEnvelope
import dev.kpetriv.sprout.core.sync.DocumentJsonCodec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import kotlin.io.encoding.Base64

/**
 * The stored form of everything the transport writes and reads (`firestore/firestore.rules` is the contract).
 *
 * - **Events**: the `LedgerJson` tree with its `envelope` object flattened to the top level, next to `type` and the
 *   event fields, plus `serverReceivedAt`. On the way back the envelope keys are nested again, so the tree is the one
 *   the author hashed and signed.
 * - **Documents**: the [DocumentEnvelope] fields at the top level plus `serverReceivedAt`.
 * - **Parent keys** (`parentKeys/{fingerprint}`): written by the server only (`registerParentKey`, D-M3-34):
 *   `{ publicKey, uid, deviceId, validFromLamport, registeredAt, attestation }`, `revokedAtLamport` once revoked;
 *   read into [RegisteredParentKey].
 * - **Devices**: written by the server only; read into [RegisteredDevice], with the device's encryption key
 *   (`encryptionKey`, `encryptionKeySignature`, base64; M4 §1.1) as an unverified [BoundEncryptionKey] when both are
 *   present (receivers verify the binding themselves).
 * - **Messages** (`messages/{messageId}`): the `MessageJson` fields at the top level plus `serverReceivedAt`, with
 *   `ciphertext` stored as Firestore bytes (so the rules can check its decoded size); read back as base64, which gives
 *   the tree the author signed.
 * - **Blob chunks** (`blobs/{blobId}.{chunk}`): the `BlobJson` header fields at the top level, `data` as Firestore
 *   bytes, plus `serverReceivedAt`.
 */
@Suppress("TooManyFunctions") // One reader and one writer per stored shape.
internal object StoredShapes {
    /** The fields of the ledger `Envelope` (core/domain-ledger), schema 1 and 2. */
    val EVENT_ENVELOPE_KEYS: Set<String> = setOf(
        "schemaVersion",
        "eventId",
        "householdId",
        "kidId",
        "accountId",
        "authorUid",
        "authorRole",
        "authorDeviceId",
        "createdAt",
        "lamport",
        "prevHash",
        "hash",
        "signature",
        "parentSignature",
    )

    private const val ENVELOPE = "envelope"
    private const val MESSAGE_ID = "messageId"
    private const val CIPHERTEXT = "ciphertext"
    private const val DATA = "data"
    private const val ENCRYPTION_KEY = "encryptionKey"
    private const val ENCRYPTION_KEY_SIGNATURE = "encryptionKeySignature"
    private val HEX_SHA256 = Regex("[0-9a-f]{64}")

    /** An event ready to store: its id, hash and flattened tree. */
    class FlatEvent(val eventId: String, val hash: String, val fields: JsonObject)

    /**
     * Flattens a `LedgerJson` event tree, or returns null when it cannot be stored losslessly: no `envelope` object,
     * an invalid `eventId` or `hash`, an envelope key this client does not know (it would come back as an event
     * field), or an event field that collides with an envelope key.
     */
    @Suppress("ReturnCount")
    fun flattenEvent(event: JsonObject): FlatEvent? {
        val envelope = event[ENVELOPE] as? JsonObject ?: return null
        val body = event - ENVELOPE
        val eventId = envelope.string("eventId")?.takeIf(ID_PATTERN::matches) ?: return null
        val hash = envelope.string("hash")?.takeIf(HEX_SHA256::matches) ?: return null
        val lossless = envelope.keys.all { it in EVENT_ENVELOPE_KEYS } && body.keys.none { it in EVENT_ENVELOPE_KEYS }
        return if (lossless) FlatEvent(eventId, hash, JsonObject(envelope + body)) else null
    }

    /** The `LedgerJson` tree of a stored event (without `serverReceivedAt`), or null without an `eventId`. */
    fun nestEvent(stored: Map<String, Any?>): JsonObject? {
        val fields = PlainValues.toJsonObject(stored - FirestoreFacade.SERVER_RECEIVED_AT)
        if (fields.string("eventId") == null) return null
        val (envelope, body) = fields.entries.partition { it.key in EVENT_ENVELOPE_KEYS }
        return JsonObject(
            body.associate {
                it.key to it.value
            } + (ENVELOPE to JsonObject(envelope.associate { it.key to it.value })),
        )
    }

    fun eventHashOf(stored: Map<String, Any?>): String? = stored["hash"] as? String

    /** A document's stored fields (envelope at the top level) without the server stamp. */
    fun documentFields(envelope: DocumentEnvelope): Map<String, Any?> =
        PlainValues.toPlainMap(DocumentJsonCodec.toJsonObject(envelope))

    /** The envelope stored in a document collection, or null when it does not decode. */
    fun documentOf(stored: Map<String, Any?>): DocumentEnvelope? =
        DocumentJsonCodec.decodeOrNull(PlainValues.toJsonObject(stored - FirestoreFacade.SERVER_RECEIVED_AT))

    /** A message ready to store: its id and stored fields (`ciphertext` as bytes, no server stamp). */
    class StoredMessage(val messageId: String, val fields: Map<String, Any?>)

    /**
     * The stored form of a `MessageJson` tree, or null when it cannot be stored: no valid `messageId`, or a
     * `ciphertext` that is not a base64 string. Everything else is the rules' and the receivers' to judge.
     */
    @Suppress("ReturnCount")
    fun messageFields(message: JsonObject): StoredMessage? {
        val messageId = message.string(MESSAGE_ID)?.takeIf(ID_PATTERN::matches) ?: return null
        val ciphertext = message.string(CIPHERTEXT)?.let(::decodeBase64OrNull) ?: return null
        return StoredMessage(messageId, PlainValues.toPlainMap(message) + (CIPHERTEXT to FacadeBytes(ciphertext)))
    }

    /** The `MessageJson` tree of a stored message (without `serverReceivedAt`; `ciphertext` as base64). */
    fun messageOf(stored: Map<String, Any?>): JsonObject =
        PlainValues.toJsonObject(stored - FirestoreFacade.SERVER_RECEIVED_AT)

    /** A blob chunk ready to store: its document id (`"<blobId>.<chunk>"`) and fields (`data` as bytes). */
    class StoredBlobChunk(val blobId: String, val chunk: Int, val fields: Map<String, Any?>)

    /** The stored form of [chunk], or null when its header has no valid `blobId` and integer `chunk`. */
    @Suppress("ReturnCount")
    fun blobChunkFields(chunk: BlobChunkJson): StoredBlobChunk? {
        val blobId = chunk.blobId?.takeIf(ID_PATTERN::matches) ?: return null
        val index = chunk.chunk?.takeIf { ID_PATTERN.matches(chunk.docId) } ?: return null
        val fields = PlainValues.toPlainMap(chunk.header) + (DATA to FacadeBytes(chunk.data))
        return StoredBlobChunk(blobId, index, fields)
    }

    /** A stored blob chunk, or null when it has no `data` bytes. */
    fun blobChunkOf(stored: Map<String, Any?>): BlobChunkJson? {
        val data = stored[DATA] as? FacadeBytes ?: return null
        val header = PlainValues.toJsonObject(stored - DATA - FirestoreFacade.SERVER_RECEIVED_AT)
        return BlobChunkJson(header, data.toByteArray())
    }

    /**
     * A `devices/{deviceId}` document of household [hid] as a registry entry, or null when any field is missing or
     * malformed. A malformed or half-present encryption key is left out (the device has none for receivers).
     */
    @Suppress("ReturnCount")
    fun deviceOf(document: FacadeDocument, hid: HouseholdId): RegisteredDevice? {
        val data = document.data
        val deviceId = document.id.takeIf(ID_PATTERN::matches)?.let(::DeviceId) ?: return null
        val uid = data.id("uid")?.let(::UserId) ?: return null
        val role =
            (data["role"] as? String)?.let { name -> Role.entries.firstOrNull { it.name == name } } ?: return null
        val kidId = data.id("kidId")?.let(::KidId)
        if (role == Role.KID && kidId == null) return null
        val publicKey = (data["publicKey"] as? String)?.let(::decodeBase64OrNull) ?: return null
        val status = statusOf(data) ?: return null
        val attestation = (data["attestation"] as? String)
            ?.let { name -> AttestationLevel.entries.firstOrNull { it.name == name } }
            ?: AttestationLevel.NONE
        val owner = DeviceOwner(uid, role, kidId.takeIf { role == Role.KID })
        val encryptionKey = BoundEncryptionKey.fromBase64(
            hid,
            data[ENCRYPTION_KEY] as? String,
            data[ENCRYPTION_KEY_SIGNATURE] as? String,
        )
        return RegisteredDevice(deviceId, owner, publicKey, status, attestation, encryptionKey)
    }

    /** A REVOKED device without a `revokedAtLamport` is revoked from 0: none of its writes is accepted. */
    private fun statusOf(data: Map<String, Any?>): DeviceStatus? = when (data["status"]) {
        "PENDING" -> DeviceStatus.Pending
        "ACTIVE" -> DeviceStatus.Active
        "REVOKED" -> DeviceStatus.Revoked((data["revokedAtLamport"] as? Long)?.coerceAtLeast(0) ?: 0)
        else -> null
    }

    /** A `parentKeys/{fingerprint}` document as a registry entry, or null when malformed. */
    @Suppress("ReturnCount")
    fun parentKeyOf(document: FacadeDocument): RegisteredParentKey? {
        val data = document.data
        val fingerprint = document.id.takeIf(HEX_SHA256::matches) ?: return null
        val publicKey = (data["publicKey"] as? String)?.let(::decodeBase64OrNull) ?: return null
        val uid = data.id("uid")?.let(::UserId) ?: return null
        val deviceId = data.id("deviceId")?.let(::DeviceId) ?: return null
        val validFrom = data["validFromLamport"] as? Long ?: return null
        val revokedAt = data["revokedAtLamport"]
        if (revokedAt != null && revokedAt !is Long) return null
        return RegisteredParentKey(fingerprint, publicKey, uid, deviceId, validFrom, revokedAt)
    }

    private fun Map<String, Any?>.id(key: String): String? = (this[key] as? String)?.takeIf(ID_PATTERN::matches)

    /**
     * A digest of a whole collection's content (ids and every field, keys sorted), used as the cursor of the
     * registry collections: they are small and change in place without a fresh server stamp (approve, revoke).
     */
    fun digestOf(documents: List<FacadeDocument>): String {
        val text = buildString {
            documents.sortedBy { it.id }.forEach { document ->
                append(document.id).append('\u0000')
                appendStable(PlainValues.toJson(document.data))
                append('\u0000')
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun StringBuilder.appendStable(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                append('{')
                element.entries.sortedBy { it.key }.forEach { (key, value) ->
                    append(JsonPrimitive(key)).append(':')
                    appendStable(value)
                    append(',')
                }
                append('}')
            }

            is kotlinx.serialization.json.JsonArray -> {
                append('[')
                element.forEach {
                    appendStable(it)
                    append(',')
                }
                append(']')
            }

            else -> append(element.toString())
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun decodeBase64OrNull(text: String): ByteArray? = try {
        Base64.Default.decode(text)
    } catch (expected: IllegalArgumentException) {
        null
    }
}

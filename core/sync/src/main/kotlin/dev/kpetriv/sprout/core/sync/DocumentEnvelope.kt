package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.domainledger.CanonicalEncoder
import dev.kpetriv.sprout.core.domainledger.SignatureCheck
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.ID_PATTERN
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.io.encoding.Base64

/**
 * Domain-separation prefix of [DocumentEnvelope.signedPayload]. Different from the ledger event domains, so a device
 * signature over a document can never be replayed as a signature over an event, or the other way round.
 */
const val DOCUMENT_SIGNATURE_DOMAIN: String = "sprout/document/v1"

private val DOCUMENT_SIGNATURE_PREFIX = DOCUMENT_SIGNATURE_DOMAIN.encodeToByteArray() + 0.toByte()

/**
 * One synced document (M3 §3): the latest state of [docType]/[docId], signed by the authoring device.
 *
 * The envelope's top level is closed: a field added here needs a new [schemaVersion], because a client that drops
 * an unknown top-level field on decoding can no longer verify the signature (it fails as `BAD_SIGNATURE`). New
 * document fields go into [payload], which is kept as a JSON tree and so is always covered by the signature.
 *
 * @property docType one of [DocTypes]; unknown types are rejected by receivers.
 * @property docId the document's id within [docType] (same alphabet as every other id).
 * @property authorUid the uid the author claims; receivers check it against the registered device.
 * @property authorDeviceId the device whose key signed the envelope.
 * @property lamport the author device's Lamport value at the write; the last-writer-wins key with [authorDeviceId].
 * @property deleted a tombstone: the document is gone and can never come back (see [ConflictResolver]).
 * @property signature base64 ECDSA P-256 signature over [signedPayload] by the author device key; null until
 *   signed. Unsigned envelopes are never accepted from a remote.
 */
@Serializable
data class DocumentEnvelope(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val householdId: HouseholdId,
    val docType: String,
    val docId: String,
    val authorUid: UserId,
    val authorDeviceId: DeviceId,
    val lamport: Long,
    val deleted: Boolean = false,
    val payload: JsonObject,
    val signature: String? = null,
) {
    init {
        require(schemaVersion >= 1) { "schemaVersion must be at least 1" }
        require(ID_PATTERN.matches(docType)) { "docType must be a valid id" }
        require(ID_PATTERN.matches(docId)) { "docId must be a valid id" }
        require(lamport >= 0) { "lamport must not be negative" }
    }

    /**
     * The bytes the device key signs: `UTF-8("sprout/document/v1") || 0x00 || canonical(envelope minus signature)`,
     * where canonical is [CanonicalEncoder]'s form (sorted keys, compact, integers only, strict UTF-8).
     *
     * @throws IllegalArgumentException if [payload] holds a non-integer number.
     * @throws CharacterCodingException if a string holds an unpaired surrogate.
     */
    fun signedPayload(): ByteArray = documentSignedPayloadOf(DocumentJsonCodec.toJsonObject(this))

    /** Signs [signedPayload] with [sign] (the device key) and attaches the base64 signature. */
    fun signWith(sign: (ByteArray) -> ByteArray): DocumentEnvelope =
        copy(signature = Base64.Default.encode(sign(signedPayload())))

    /**
     * Whether [signature] is a valid signature over [signedPayload] by [publicKey] (SPKI DER). False, never an
     * exception, for an unsigned envelope, malformed base64, a payload that cannot be canonicalised, or a bad
     * signature.
     */
    fun verifyWith(check: SignatureCheck, publicKey: ByteArray): Boolean {
        val signatureBytes = signature?.let(::decodeBase64OrNull)
        val payloadBytes = signedPayloadOrNull()
        return signatureBytes != null && payloadBytes != null && check.verify(publicKey, payloadBytes, signatureBytes)
    }

    private fun signedPayloadOrNull(): ByteArray? = try {
        signedPayload()
    } catch (expected: IllegalArgumentException) {
        null
    } catch (expected: CharacterCodingException) {
        null
    }

    override fun toString(): String =
        "DocumentEnvelope(docType=$docType, docId=$docId, authorDeviceId=$authorDeviceId, lamport=$lamport, " +
            "deleted=$deleted, signed=${signature != null})"

    companion object {
        /** The envelope schema new documents are written with. */
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/**
 * [DocumentEnvelope.signedPayload] for an envelope JSON tree as stored or pulled; `signature` is removed before
 * canonicalising, every other field (including ones this client does not know) is covered.
 */
fun documentSignedPayloadOf(tree: JsonObject): ByteArray =
    DOCUMENT_SIGNATURE_PREFIX + canonicalDocumentString(tree).encodeToByteArray(throwOnInvalidSequence = true)

/**
 * The canonical form of an envelope tree without its signature, as a string (for golden tests and debugging).
 *
 * [CanonicalEncoder] only takes ledger-event documents (it requires and strips an `envelope` object), so the tree is
 * wrapped as `{"d": tree, "envelope": {}}`; with keys sorted that encodes as `{"d":` + canonical(tree) +
 * `,"envelope":{}}`, and the middle is exactly the canonical form of the tree under the same rules.
 */
internal fun canonicalDocumentString(tree: JsonObject): String {
    val wrapped = JsonObject(
        mapOf(WRAP_KEY to JsonObject(tree - SIGNATURE_KEY), "envelope" to JsonObject(emptyMap())),
    )
    val encoded = CanonicalEncoder.encodeToString(wrapped)
    check(encoded.startsWith(WRAP_PREFIX) && encoded.endsWith(WRAP_SUFFIX)) { "unexpected canonical wrapper" }
    return encoded.substring(WRAP_PREFIX.length, encoded.length - WRAP_SUFFIX.length)
}

private fun decodeBase64OrNull(encoded: String): ByteArray? = try {
    Base64.Default.decode(encoded)
} catch (expected: IllegalArgumentException) {
    null
}

private const val SIGNATURE_KEY = "signature"
private const val WRAP_KEY = "d"
private const val WRAP_PREFIX = "{\"d\":"
private const val WRAP_SUFFIX = ",\"envelope\":{}}"

/** Storage and wire form of [DocumentEnvelope]s. Not canonical: signatures cover [documentSignedPayloadOf]. */
object DocumentJsonCodec {
    /** Defaults and explicit nulls are always written; unknown keys are ignored on decoding. */
    val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }

    fun encode(envelope: DocumentEnvelope): String = json.encodeToString(DocumentEnvelope.serializer(), envelope)

    fun toJsonObject(envelope: DocumentEnvelope): JsonObject =
        json.encodeToJsonElement(DocumentEnvelope.serializer(), envelope) as? JsonObject
            ?: error("Document envelopes must serialise to JSON objects")

    /** Decodes one envelope, or null if [text] is not a valid envelope (malformed JSON, bad id, missing field). */
    fun decodeOrNull(text: String): DocumentEnvelope? = try {
        json.decodeFromString(DocumentEnvelope.serializer(), text)
    } catch (expected: IllegalArgumentException) {
        null
    }

    /** [decodeOrNull] for an already parsed tree. */
    fun decodeOrNull(element: JsonElement): DocumentEnvelope? = try {
        json.decodeFromJsonElement(DocumentEnvelope.serializer(), element)
    } catch (expected: IllegalArgumentException) {
        null
    }
}

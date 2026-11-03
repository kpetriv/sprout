package dev.kpetriv.sprout.core.domainledger.chat

import dev.kpetriv.sprout.core.domainledger.CanonicalEncoder
import dev.kpetriv.sprout.core.domainledger.EpochMillisInstantSerializer
import dev.kpetriv.sprout.core.domainledger.SignatureCheck
import dev.kpetriv.sprout.core.model.BlobId
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.MessageId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.ThreadId
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.io.encoding.Base64
import kotlin.time.Instant

/** Domain-separation label of message signatures and message AEAD associated data (M4 §1.3). */
const val MESSAGE_DOMAIN: String = "sprout/message/v1"

private val MESSAGE_PREFIX = MESSAGE_DOMAIN.encodeToByteArray() + 0.toByte()

/**
 * One chat message as it travels (M4 §1.3): immutable, signed by the author device, its content encrypted with the
 * thread key of [epoch]. Not a [dev.kpetriv.sprout.core.domainledger.Envelope] and not a document envelope.
 *
 * **Closed top level.** [MessageJson] refuses unknown keys, so any new field needs a new [schemaVersion]; the
 * signature is computed over this client's own encoding of the decoded envelope ([signedPayload]), which therefore
 * covers every field there is. A missing `blobId`/`signature` and an explicit `null` decode and canonicalise the same.
 *
 * Wire form (JSON, also the canonical form's keys): `schemaVersion` int, `householdId`, `threadId`, `messageId`,
 * `authorUid`, `authorDeviceId`, `authorRole` (`PARENT`|`KID`), `seq` int, `lamport` int, `createdAt` int (epoch
 * milliseconds), `epoch` int, `kind` (`TEXT`|`PRESET`|`VOICE`|`PHOTO`), `ciphertext` (standard base64 with padding of
 * the AEAD output, 1 to [ChatLimits.MAX_MESSAGE_CIPHERTEXT_BYTES] bytes once decoded), `blobId` (string or null; set
 * exactly for VOICE and PHOTO), `signature` (base64 DER ECDSA, or null before signing).
 *
 * @property seq per author device per thread, 1, 2, 3, ... with no gaps ([MessageVerifier] holds gaps).
 * @property lamport the author device's Lamport value at the write, shared with its events and documents.
 * @property createdAt wall-clock time for display only, millisecond precision (truncate the clock's value).
 */
@Serializable
data class MessageEnvelope(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val householdId: HouseholdId,
    val threadId: ThreadId,
    val messageId: MessageId,
    val authorUid: UserId,
    val authorDeviceId: DeviceId,
    val authorRole: Role,
    val seq: Long,
    val lamport: Long,
    @Serializable(with = EpochMillisInstantSerializer::class)
    val createdAt: Instant,
    val epoch: Long,
    val kind: MessageKind,
    val ciphertext: String,
    val blobId: BlobId? = null,
    val signature: String? = null,
) {
    init {
        require(schemaVersion >= 1) { "schemaVersion must be at least 1" }
        require(seq >= 1) { "seq starts at 1" }
        require(lamport >= 0) { "lamport must not be negative" }
        require(epoch >= 0) { "epoch must not be negative" }
        require(createdAt.nanosecondsOfSecond % NANOS_PER_MILLI == 0) { "createdAt must have millisecond precision" }
        require((blobId != null) == kind.hasBlob) { "blobId is set exactly for VOICE and PHOTO" }
        require(ciphertextBytes().size in 1..ChatLimits.MAX_MESSAGE_CIPHERTEXT_BYTES) {
            "ciphertext must be 1 to ${ChatLimits.MAX_MESSAGE_CIPHERTEXT_BYTES} bytes"
        }
    }

    /** The fields bound into the AEAD associated data ([MessageHeader]). */
    val header: MessageHeader
        get() = MessageHeader(
            schemaVersion = schemaVersion,
            householdId = householdId,
            threadId = threadId,
            messageId = messageId,
            authorUid = authorUid,
            authorDeviceId = authorDeviceId,
            authorRole = authorRole,
            epoch = epoch,
            kind = kind,
            blobId = blobId,
        )

    /** The decoded [ciphertext]. @throws IllegalArgumentException if it is not valid base64 (never after `init`). */
    fun ciphertextBytes(): ByteArray = Base64.Default.decode(ciphertext)

    /**
     * The bytes the device key signs: `UTF-8("sprout/message/v1") || 0x00 || canonical(envelope minus signature)`,
     * canonical as in [CanonicalEncoder.encodeTree].
     *
     * @throws CharacterCodingException never for a decoded envelope (ids are ASCII and nothing else is free text).
     */
    fun signedPayload(): ByteArray =
        MESSAGE_PREFIX + CanonicalEncoder.encodeTree(JsonObject(MessageJson.toJsonObject(this) - SIGNATURE_KEY))

    /** This envelope carrying [signature] (DER) as base64. */
    fun withSignature(signature: ByteArray): MessageEnvelope = copy(signature = Base64.Default.encode(signature))

    /** Signs [signedPayload] with [sign] (the device key) and attaches the signature. */
    fun signWith(sign: (ByteArray) -> ByteArray): MessageEnvelope = withSignature(sign(signedPayload()))

    /**
     * Whether [signature] verifies over [signedPayload] against [publicKey] (SPKI DER). False, never an exception, for
     * an unsigned envelope, malformed base64 or a [SignatureCheck] that throws.
     */
    @Suppress("TooGenericExceptionCaught") // A third-party SignatureCheck must not be able to crash ingest.
    fun verifyWith(check: SignatureCheck, publicKey: ByteArray): Boolean {
        val signatureBytes = signature?.let {
            try {
                Base64.Default.decode(it)
            } catch (expected: IllegalArgumentException) {
                null
            }
        }
        if (signatureBytes == null || signatureBytes.isEmpty()) return false
        return try {
            check.verify(publicKey, signedPayload(), signatureBytes)
        } catch (expected: Exception) {
            false
        }
    }

    override fun toString(): String =
        "MessageEnvelope(messageId=$messageId, threadId=$threadId, authorDeviceId=$authorDeviceId, seq=$seq, " +
            "lamport=$lamport, epoch=$epoch, kind=$kind, signed=${signature != null})"

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1

        /** An unsigned envelope for [header] with [ciphertext] (the AEAD output of [MessageHeader.associatedData]). */
        fun of(header: MessageHeader, seq: Long, lamport: Long, createdAt: Instant, ciphertext: ByteArray) =
            MessageEnvelope(
                schemaVersion = header.schemaVersion,
                householdId = header.householdId,
                threadId = header.threadId,
                messageId = header.messageId,
                authorUid = header.authorUid,
                authorDeviceId = header.authorDeviceId,
                authorRole = header.authorRole,
                seq = seq,
                lamport = lamport,
                createdAt = createdAt,
                epoch = header.epoch,
                kind = header.kind,
                ciphertext = Base64.Default.encode(ciphertext),
                blobId = header.blobId,
            )

        private const val SIGNATURE_KEY = "signature"
        private const val NANOS_PER_MILLI = 1_000_000
    }
}

/**
 * The envelope fields bound into a message's AEAD associated data:
 * `UTF-8("sprout/message/v1") || 0x00 || canonical({schemaVersion, householdId, threadId, messageId, authorUid,
 * authorDeviceId, authorRole, epoch, kind, blobId})` ([associatedData], `blobId` written as `null` when absent).
 *
 * Every field that says *what* the ciphertext is and *who* wrote it is bound, so a ciphertext copied into another
 * envelope (another message id, thread, author, epoch, kind or blob) fails authentication. `seq`, `lamport` and
 * `createdAt` are deliberately not bound: they are ordering metadata allocated when the message is queued, and the
 * device signature covers them (and the ciphertext) anyway. Encrypting therefore needs only what is known when the
 * user presses send.
 */
@Serializable
data class MessageHeader(
    val schemaVersion: Int = MessageEnvelope.CURRENT_SCHEMA_VERSION,
    val householdId: HouseholdId,
    val threadId: ThreadId,
    val messageId: MessageId,
    val authorUid: UserId,
    val authorDeviceId: DeviceId,
    val authorRole: Role,
    val epoch: Long,
    val kind: MessageKind,
    val blobId: BlobId? = null,
) {
    init {
        require(schemaVersion >= 1) { "schemaVersion must be at least 1" }
        require(epoch >= 0) { "epoch must not be negative" }
        require((blobId != null) == kind.hasBlob) { "blobId is set exactly for VOICE and PHOTO" }
    }

    /** The AEAD associated data (see the class documentation). */
    fun associatedData(): ByteArray = MESSAGE_PREFIX +
        CanonicalEncoder.encodeTree(MessageJson.json.encodeToJsonElement(serializer(), this) as JsonObject)
}

/**
 * Storage and wire form of [MessageEnvelope]: defaults and explicit nulls are written, and unknown keys are an error
 * (closed top level). Not canonical: signatures cover [MessageEnvelope.signedPayload].
 */
object MessageJson {
    val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun encode(envelope: MessageEnvelope): String = json.encodeToString(MessageEnvelope.serializer(), envelope)

    fun toJsonObject(envelope: MessageEnvelope): JsonObject =
        json.encodeToJsonElement(MessageEnvelope.serializer(), envelope) as JsonObject

    /** Decodes one envelope, or null when [text] is not a valid envelope (malformed, unknown key, failed check). */
    fun decodeOrNull(text: String): MessageEnvelope? = try {
        json.decodeFromString(MessageEnvelope.serializer(), text)
    } catch (expected: IllegalArgumentException) {
        null
    }

    /** [decodeOrNull] for an already parsed tree. */
    fun decodeOrNull(element: JsonElement): MessageEnvelope? = try {
        json.decodeFromJsonElement(MessageEnvelope.serializer(), element)
    } catch (expected: IllegalArgumentException) {
        null
    }
}

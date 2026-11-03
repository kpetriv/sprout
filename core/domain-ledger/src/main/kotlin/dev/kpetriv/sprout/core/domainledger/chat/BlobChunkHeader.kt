package dev.kpetriv.sprout.core.domainledger.chat

import dev.kpetriv.sprout.core.domainledger.CanonicalEncoder
import dev.kpetriv.sprout.core.domainledger.Hasher
import dev.kpetriv.sprout.core.domainledger.JvmSha256Hasher
import dev.kpetriv.sprout.core.domainledger.SignatureCheck
import dev.kpetriv.sprout.core.model.BlobId
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.ID_PATTERN
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.SubmissionId
import dev.kpetriv.sprout.core.model.ThreadId
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.io.encoding.Base64

/** Domain-separation label of blob chunk signatures and blob AEAD associated data (M4 §2). */
const val BLOB_DOMAIN: String = "sprout/blob/v1"

private val BLOB_PREFIX = BLOB_DOMAIN.encodeToByteArray() + 0.toByte()

/**
 * Everything about one chunk of a blob except its bytes (M4 §2, D-M4-5): document `blobs/{blobId}.{chunk}` holds these
 * fields plus `data` (Firestore bytes, at most [ChatLimits.MAX_BLOB_CHUNK_BYTES]). A blob is the AEAD output of
 * [BlobContext.associatedData] under the thread key of [epoch], split into [chunks] consecutive chunks.
 *
 * A chat blob sets [threadId] (and [kidId] is that thread's kid); a chore photo sets [submissionId] instead and is
 * encrypted with its kid's thread key. Closed top level, like [MessageEnvelope].
 *
 * @property sha256 lower-case hex SHA-256 of the **whole** blob ciphertext (all chunks joined), the same in every
 *   chunk.
 * @property signature base64 DER ECDSA by the author device over [signedPayload]; null before signing.
 */
@Serializable
data class BlobChunkHeader(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val householdId: HouseholdId,
    val blobId: BlobId,
    val threadId: ThreadId? = null,
    val kidId: KidId,
    val submissionId: SubmissionId? = null,
    val authorUid: UserId,
    val authorDeviceId: DeviceId,
    val lamport: Long,
    val chunk: Int,
    val chunks: Int,
    val sha256: String,
    val epoch: Long,
    val signature: String? = null,
) {
    init {
        require(schemaVersion >= 1) { "schemaVersion must be at least 1" }
        require((threadId == null) != (submissionId == null)) { "exactly one of threadId and submissionId is set" }
        require(threadId == null || threadId.value == kidId.value) { "a chat blob's kidId is its thread's kid" }
        require(chunks in 1..ChatLimits.MAX_BLOB_CHUNKS && chunk in 0 until chunks) {
            "chunk must be in 0 until chunks"
        }
        require(SHA256_HEX.matches(sha256)) { "sha256 must be 64 lower-case hex characters" }
        require(lamport >= 0) { "lamport must not be negative" }
        require(epoch >= 0) { "epoch must not be negative" }
        require(ID_PATTERN.matches(docId)) { "blobId and chunk must form a valid document id" }
    }

    /** The document id, `"<blobId>.<chunk>"`. */
    val docId: String get() = "${blobId.value}.$chunk"

    /** The AEAD context of the whole blob (the same for every chunk). */
    val context: BlobContext
        get() = BlobContext(householdId, blobId, threadId, kidId, submissionId, authorDeviceId, epoch)

    /**
     * The bytes the device key signs for this chunk: `UTF-8("sprout/blob/v1") || 0x00 || canonical(header minus
     * signature) || 0x00 || SHA-256(chunkData)` (32 raw bytes).
     */
    fun signedPayload(chunkData: ByteArray, hasher: Hasher = JvmSha256Hasher): ByteArray =
        BLOB_PREFIX + CanonicalEncoder.encodeTree(JsonObject(BlobJson.toJsonObject(this) - SIGNATURE_KEY)) +
            0.toByte() + hasher.sha256(chunkData)

    /** This header signed by [sign] (the device key) over [signedPayload] of [chunkData]. */
    fun signWith(chunkData: ByteArray, sign: (ByteArray) -> ByteArray): BlobChunkHeader =
        withSignature(sign(signedPayload(chunkData)))

    fun withSignature(signature: ByteArray): BlobChunkHeader = copy(signature = Base64.Default.encode(signature))

    /** Whether [signature] verifies for [chunkData] against [publicKey] (SPKI DER). Never throws. */
    @Suppress("TooGenericExceptionCaught") // A third-party SignatureCheck must not be able to crash a download.
    fun verifyWith(check: SignatureCheck, publicKey: ByteArray, chunkData: ByteArray): Boolean {
        if (chunkData.size > ChatLimits.MAX_BLOB_CHUNK_BYTES) return false
        return try {
            val signatureBytes = signature?.let(Base64.Default::decode)
            signatureBytes != null && signatureBytes.isNotEmpty() &&
                check.verify(publicKey, signedPayload(chunkData), signatureBytes)
        } catch (expected: Exception) {
            false
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        private const val SIGNATURE_KEY = "signature"
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}

/**
 * What a blob's AEAD associated data binds:
 * `UTF-8("sprout/blob/v1") || 0x00 || canonical({householdId, blobId, threadId, kidId, submissionId, authorDeviceId,
 * epoch})` (absent ids written as `null`). A blob ciphertext therefore cannot be replayed under another blob id,
 * thread, submission, author or epoch.
 */
@Serializable
data class BlobContext(
    val householdId: HouseholdId,
    val blobId: BlobId,
    val threadId: ThreadId?,
    val kidId: KidId,
    val submissionId: SubmissionId?,
    val authorDeviceId: DeviceId,
    val epoch: Long,
) {
    init {
        require((threadId == null) != (submissionId == null)) { "exactly one of threadId and submissionId is set" }
        require(threadId == null || threadId.value == kidId.value) { "a chat blob's kidId is its thread's kid" }
        require(epoch >= 0) { "epoch must not be negative" }
    }

    fun associatedData(): ByteArray =
        BLOB_PREFIX + CanonicalEncoder.encodeTree(BlobJson.json.encodeToJsonElement(serializer(), this) as JsonObject)
}

/** Wire form of [BlobChunkHeader] (without `data`): explicit nulls, unknown keys refused. */
object BlobJson {
    val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun toJsonObject(header: BlobChunkHeader): JsonObject =
        json.encodeToJsonElement(BlobChunkHeader.serializer(), header) as JsonObject

    /** Decodes a header tree (the document minus `data`), or null when it is not a valid header. */
    fun decodeOrNull(element: JsonElement): BlobChunkHeader? = try {
        json.decodeFromJsonElement(BlobChunkHeader.serializer(), element)
    } catch (expected: IllegalArgumentException) {
        null
    }
}

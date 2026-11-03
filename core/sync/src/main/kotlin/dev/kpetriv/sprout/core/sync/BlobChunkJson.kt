package dev.kpetriv.sprout.core.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * One chunk of an encrypted blob as it travels (M4 §2, D-M4-5): [header] is the core/domain-ledger `BlobJson` tree of
 * a `BlobChunkHeader` (`schemaVersion`, `householdId`, `blobId`, `threadId`, `kidId`, `submissionId`, `authorUid`,
 * `authorDeviceId`, `lamport`, `chunk`, `chunks`, `sha256`, `epoch`, `signature`) and [data] the chunk's ciphertext
 * bytes (Firestore `bytes` field `data`, at most 716,800 bytes). Stored as `blobs/{blobId}.{chunk}`.
 *
 * The bytes are copied in and out, so an instance is immutable. [toString] shows ids and sizes only.
 */
class BlobChunkJson(val header: JsonObject, data: ByteArray) {
    private val bytes: ByteArray = data.copyOf()

    /** A copy of the chunk's ciphertext. */
    val data: ByteArray get() = bytes.copyOf()

    /** Size of [data] in bytes. */
    val size: Int get() = bytes.size

    /** `blobId` of the header, or null when it is missing or not a string. */
    val blobId: String? get() = header.string(BLOB_ID)

    /** `chunk` of the header, or null when it is missing or not an integer. */
    val chunk: Int? get() = (header[CHUNK] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

    /** The document id `"<blobId>.<chunk>"` (empty parts when the header lacks them). */
    val docId: String get() = "${blobId.orEmpty()}.${chunk?.toString().orEmpty()}"

    override fun equals(other: Any?): Boolean =
        other is BlobChunkJson && header == other.header && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * header.hashCode() + bytes.contentHashCode()

    override fun toString(): String = "BlobChunkJson(docId=$docId, size=${bytes.size})"

    private companion object {
        const val BLOB_ID = "blobId"
        const val CHUNK = "chunk"

        fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

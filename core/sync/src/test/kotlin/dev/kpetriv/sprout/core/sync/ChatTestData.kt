package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.domainledger.chat.BlobChunkHeader
import dev.kpetriv.sprout.core.domainledger.chat.BlobJson
import dev.kpetriv.sprout.core.domainledger.chat.MessageEnvelope
import dev.kpetriv.sprout.core.domainledger.chat.MessageJson
import dev.kpetriv.sprout.core.domainledger.chat.MessageKind
import dev.kpetriv.sprout.core.model.BlobId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.MessageId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.SubmissionId
import dev.kpetriv.sprout.core.model.ThreadId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.time.Instant

internal val CREATED_AT: Instant = Instant.fromEpochMilliseconds(1_800_000_000_123)

/** A signed message envelope by this identity (ciphertext is opaque filler: core/sync never decrypts). */
@Suppress("LongParameterList")
internal fun TestIdentity.messageEnvelope(
    messageId: String,
    seq: Long = 1,
    lamport: Long = seq,
    thread: KidId = kidId ?: KID_1,
    kind: MessageKind = MessageKind.TEXT,
    ciphertextBytes: Int = 48,
    blobId: String? = if (kind.hasBlob) "blob-$messageId" else null,
    authorRole: Role = role,
): MessageEnvelope = MessageEnvelope(
    householdId = HOUSEHOLD,
    threadId = ThreadId.of(thread),
    messageId = MessageId(messageId),
    authorUid = uid,
    authorDeviceId = deviceId,
    authorRole = authorRole,
    seq = seq,
    lamport = lamport,
    createdAt = CREATED_AT,
    epoch = 0,
    kind = kind,
    ciphertext = Base64.Default.encode(ByteArray(ciphertextBytes) { (it % 251).toByte() }),
    blobId = blobId?.let(::BlobId),
).signWith(key::sign)

/** [messageEnvelope] as the `MessageJson` tree that travels. */
@Suppress("LongParameterList")
internal fun TestIdentity.message(
    messageId: String,
    seq: Long = 1,
    lamport: Long = seq,
    thread: KidId = kidId ?: KID_1,
    kind: MessageKind = MessageKind.TEXT,
    ciphertextBytes: Int = 48,
    authorRole: Role = role,
): JsonObject = MessageJson.toJsonObject(
    messageEnvelope(messageId, seq, lamport, thread, kind, ciphertextBytes, authorRole = authorRole),
)

/** A signed chunk of a chat blob in [thread] (or of a chore photo for [submission]) with [size] bytes of data. */
@Suppress("LongParameterList")
internal fun TestIdentity.blobChunk(
    blobId: String,
    chunk: Int = 0,
    chunks: Int = 1,
    size: Int = 64,
    thread: KidId? = kidId ?: KID_1,
    kid: KidId = thread ?: kidId ?: KID_1,
    submission: String? = null,
    lamport: Long = 1,
): BlobChunkJson {
    val data = ByteArray(size) { (it % 253).toByte() }
    val header = BlobChunkHeader(
        householdId = HOUSEHOLD,
        blobId = BlobId(blobId),
        threadId = thread?.let(ThreadId::of),
        kidId = kid,
        submissionId = submission?.let(::SubmissionId),
        authorUid = uid,
        authorDeviceId = deviceId,
        lamport = lamport,
        chunk = chunk,
        chunks = chunks,
        sha256 = "ab".repeat(32),
        epoch = 0,
    ).signWith(data, key::sign)
    return BlobChunkJson(BlobJson.toJsonObject(header), data)
}

/** [this] tree with [key] set to [value] (bypassing the models' own checks, to test the backend's). */
internal fun JsonObject.with(key: String, value: JsonElement): JsonObject = JsonObject(this + (key to value))

internal fun JsonObject.with(key: String, value: String): JsonObject = with(key, JsonPrimitive(value))

internal fun JsonObject.with(key: String, value: Long): JsonObject = with(key, JsonPrimitive(value))

internal fun base64Of(size: Int): String = Base64.Default.encode(ByteArray(size))

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

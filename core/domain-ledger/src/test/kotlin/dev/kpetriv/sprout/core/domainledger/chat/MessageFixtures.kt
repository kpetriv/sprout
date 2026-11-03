package dev.kpetriv.sprout.core.domainledger.chat

import dev.kpetriv.sprout.core.model.BlobId
import dev.kpetriv.sprout.core.model.MessageId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.ThreadId
import kotlin.time.Instant

internal val sentAt: Instant = Instant.fromEpochMilliseconds(1_793_000_000_000)

internal fun ChatDevice.header(
    messageId: String = "m-1",
    threadId: ThreadId = thread,
    kind: MessageKind = MessageKind.TEXT,
    epoch: Long = 0,
    authorRole: Role = role,
): MessageHeader = MessageHeader(
    householdId = household,
    threadId = threadId,
    messageId = MessageId(messageId),
    authorUid = uid,
    authorDeviceId = deviceId,
    authorRole = authorRole,
    epoch = epoch,
    kind = kind,
    blobId = if (kind.hasBlob) BlobId("b-$messageId") else null,
)

/** A signed envelope with a stand-in ciphertext (the domain never decrypts). */
internal fun ChatDevice.message(
    seq: Long = 1,
    lamport: Long = seq,
    header: MessageHeader = header(messageId = "m-$seq"),
    ciphertext: ByteArray = ByteArray(40) { seq.toByte() },
): MessageEnvelope = MessageEnvelope.of(header, seq, lamport, sentAt, ciphertext).signWith(signingKey::sign)

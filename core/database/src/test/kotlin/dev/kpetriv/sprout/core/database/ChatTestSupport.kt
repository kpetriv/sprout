package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.MessageEntity
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.domainledger.AttestationLevel
import dev.kpetriv.sprout.core.domainledger.DeviceOwner
import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.chat.MessageEnvelope
import dev.kpetriv.sprout.core.domainledger.chat.MessageJson
import dev.kpetriv.sprout.core.domainledger.chat.MessagePlaintext
import dev.kpetriv.sprout.core.domainledger.chat.MessagePlaintextCodec
import dev.kpetriv.sprout.core.model.ThreadId
import dev.kpetriv.sprout.core.sync.BlobChunkJson
import kotlinx.serialization.json.JsonObject

/** The thread of [KID_X], the kid the kid device is linked to. */
internal val THREAD_X = ThreadId(KID_X.value)

/** [this] device as the registry lists it, with its encryption key bound by its device key (M4 §1.1). */
internal suspend fun TestHousehold.registeredWithKey(status: DeviceStatus = DeviceStatus.Active): RegisteredDevice =
    RegisteredDevice(
        deviceId = deviceId,
        owner = DeviceOwner(session.uid, session.role, session.kidId),
        publicKey = keyStore.publicKey().encoded,
        status = status,
        attestation = AttestationLevel.NONE,
        encryptionKey = boundEncryptionKey(),
    )

/** Registers every device of [devices] (ACTIVE, with encryption keys) in each one's registry mirror. */
internal suspend fun registerChatDevices(vararg devices: TestHousehold): List<RegisteredDevice> {
    val registered = devices.map { it.registeredWithKey() }
    devices.forEach { it.applier.applyRegistry(registered, emptyList()) }
    return registered
}

/** The signed message trees queued in this device's outbox, oldest first. */
internal suspend fun TestHousehold.queuedMessages(): List<JsonObject> = database.outboxDao().all()
    .filter { it.kind == OutboxKind.MESSAGE }
    .map { LedgerJson.json.parseToJsonElement(it.payload) as JsonObject }

/** The blob chunks queued in this device's outbox, oldest first. */
internal suspend fun TestHousehold.queuedBlobChunks(): List<BlobChunkJson> = database.outboxDao().all()
    .filter { it.kind == OutboxKind.BLOB_CHUNK }
    .map { BlobChunkJson(LedgerJson.json.parseToJsonElement(it.payload) as JsonObject, requireNotNull(it.data)) }

/** What a pull from [from] brings for chat: its thread key documents, then its messages. */
internal suspend fun TestHousehold.pullChatFrom(from: TestHousehold) {
    applier.applyDocuments(from.queuedDocuments())
    applier.applyMessages(from.queuedMessages())
}

/** The stored row of [envelope]'s message. */
internal suspend fun TestHousehold.row(messageId: String): MessageEntity? = database.messageDao().get(messageId)

/** The decoded content of a stored row, or null while locked. */
internal fun MessageEntity.content(): MessagePlaintext? = plaintext?.let { MessagePlaintextCodec.decode(kind, it) }

/** Decodes a queued tree. */
internal fun JsonObject.envelope(): MessageEnvelope = requireNotNull(MessageJson.decodeOrNull(this))

/** [envelope] re-signed by [device] after [change] (a crafted or tampered message). */
internal suspend fun TestHousehold.resign(envelope: MessageEnvelope, change: (MessageEnvelope) -> MessageEnvelope) =
    change(envelope).copy(signature = null).let { it.withSignature(signer.sign(it.signedPayload())) }

/** A text plaintext. */
internal fun text(value: String) = MessagePlaintext.Text(value)

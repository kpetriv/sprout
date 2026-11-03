package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.domainledger.DeviceRegistry
import dev.kpetriv.sprout.core.domainledger.LedgerPolicy
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.RegisteredParentKey
import dev.kpetriv.sprout.core.domainledger.SignatureCheck
import dev.kpetriv.sprout.core.domainledger.chat.MessageEnvelope
import dev.kpetriv.sprout.core.domainledger.chat.MessageJson
import dev.kpetriv.sprout.core.domainledger.chat.MessageVerifier
import dev.kpetriv.sprout.core.domainledger.chat.VerifiedMessage
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.ThreadId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant

/**
 * A [RemoteApplier] over in-memory maps, and the [DeviceRegistry] mirror it verifies against. Documents go through
 * the real [RemoteDocumentVerifier] and [ConflictResolver]; events are only recorded (deduplicated by
 * `(eventId, hash)`), since ledger verification and ingest belong to core/domain-ledger and core/database.
 *
 * Messages go through the real `MessageVerifier` (registry, signature, thread, `seq` continuity) and are stored as
 * envelopes (ciphertext only: nothing is decrypted here); messages held for a `seq` gap or an unknown author are
 * retried after every apply and by [retryHeld].
 *
 * [calls] records the order of applier calls (`registry`, `documents`, `events`, `messages`, `retryHeld`,
 * `saveCursor`) so tests can assert the engine's sequence. Documents the verifier holds (unknown or pending author) are kept and re-verified
 * by [retryHeld]. Local writes go through [writeLocal], which takes the next lamport.
 */
class RecordingApplier(
    check: SignatureCheck,
    householdId: HouseholdId,
    maxLamportJump: Long = LedgerPolicy.DEFAULT_MAX_LAMPORT_JUMP,
) : RemoteApplier,
    DeviceRegistry {
    private val devices = mutableMapOf<DeviceId, RegisteredDevice>()
    private val parentKeys = mutableMapOf<String, RegisteredParentKey>()
    private val documents = sortedMapOf<String, DocumentEnvelope>()
    private val events = LinkedHashMap<String, JsonObject>()
    private val eventStamps = LinkedHashMap<String, Instant>()
    private val rejections = mutableListOf<Pair<DocumentEnvelope, DocumentRejection>>()
    private val heldDocuments = LinkedHashMap<String, DocumentEnvelope>()
    private val messages = LinkedHashMap<String, MessageEnvelope>()
    private val heldMessages = LinkedHashMap<String, JsonObject>()
    private val nextSeqs = mutableMapOf<Pair<DeviceId, ThreadId>, Long>()
    private val messageVerifier = MessageVerifier(this, check, maxLamportJump, householdId)
    private val callLog = mutableListOf<String>()
    private var savedCursor = SyncCursor.START
    private var lamport = 0L
    private val verifier = RemoteDocumentVerifier(this, check, maxLamportJump, householdId)

    /** Applier calls in order. */
    val calls: List<String> get() = synchronized(this) { callLog.toList() }

    /** Documents keyed `docType/docId`. */
    fun documents(): Map<String, DocumentEnvelope> = synchronized(this) { documents.toMap() }

    fun document(docType: String, docId: String): DocumentEnvelope? = synchronized(this) {
        documents["$docType/$docId"]
    }

    /** Recorded events in arrival order. */
    fun events(): List<JsonObject> = synchronized(this) { events.values.toList() }

    /** The server stamps pulled events arrived with, by `eventId:hash` ([PushItemId.ofEvent]). */
    fun eventStamps(): Map<String, Instant> = synchronized(this) { eventStamps.toMap() }

    /** Documents the verifier held (unknown or pending author), keyed `docType/docId`; retried by [retryHeld]. */
    fun heldDocuments(): List<DocumentEnvelope> = synchronized(this) { heldDocuments.values.toList() }

    /** Applied messages in arrival order. */
    fun messages(): List<MessageEnvelope> = synchronized(this) { messages.values.toList() }

    /** Messages held for a `seq` gap or an unknown author, in arrival order. */
    fun heldMessages(): List<JsonObject> = synchronized(this) { heldMessages.values.toList() }

    /** Records a local message (the device's own write) as applied. */
    fun recordLocalMessage(envelope: MessageEnvelope) = synchronized(this) {
        observe(envelope.lamport)
        messages.putIfAbsent(envelope.messageId.value, envelope)
        val key = envelope.authorDeviceId to envelope.threadId
        nextSeqs[key] = maxOf(nextSeqs[key] ?: 1, envelope.seq + 1)
    }

    /** Documents the verifier refused, with the reason. */
    fun rejections(): List<Pair<DocumentEnvelope, DocumentRejection>> = synchronized(this) { rejections.toList() }

    /** The lamport the next local write takes (the clock observes every verified remote lamport). */
    fun nextLamport(): Long = synchronized(this) { ++lamport }

    /** Stores a local write as the local copy, through [ConflictResolver] like any other copy. */
    fun writeLocal(envelope: DocumentEnvelope): ConflictResolver.Decision = synchronized(this) {
        observe(envelope.lamport)
        store(envelope)
    }

    /** Records a local event (the device's own ledger write). */
    fun recordLocalEvent(event: JsonObject) = synchronized(this) {
        observe(lamportOf(event) ?: 0)
        events.putIfAbsent(PushItemId.ofEvent(event).value, event)
    }

    override suspend fun device(deviceId: DeviceId): RegisteredDevice? = synchronized(this) { devices[deviceId] }

    override suspend fun parentKeys(): List<RegisteredParentKey> = synchronized(this) { parentKeys.values.toList() }

    override suspend fun applyRegistry(devices: List<RegisteredDevice>, parentKeys: List<RegisteredParentKey>) {
        synchronized(this) {
            callLog += "registry"
            devices.forEach { this.devices[it.deviceId] = it }
            parentKeys.forEach { this.parentKeys[it.fingerprint] = it }
        }
    }

    override suspend fun applyDocuments(documents: List<DocumentEnvelope>): DocumentApplyReport {
        synchronized(this) { callLog += "documents" }
        return verifyAndStore(documents)
    }

    private suspend fun verifyAndStore(documents: List<DocumentEnvelope>): DocumentApplyReport {
        var report = DocumentApplyReport()
        for (envelope in documents) {
            val key = "${envelope.docType}/${envelope.docId}"
            report += when (val verified = verifier.verify(envelope, localMaxLamport())) {
                is VerifiedDocument.Held -> {
                    synchronized(this) { heldDocuments[key] = envelope }
                    DocumentApplyReport(held = 1)
                }

                is VerifiedDocument.Rejected -> {
                    synchronized(this) {
                        heldDocuments.remove(key)
                        rejections += envelope to verified.reason
                    }
                    DocumentApplyReport(rejected = mapOf(verified.reason to 1))
                }

                is VerifiedDocument.Ok -> synchronized(this) {
                    heldDocuments.remove(key)
                    observe(envelope.lamport)
                    when (store(envelope)) {
                        ConflictResolver.Decision.Apply -> DocumentApplyReport(applied = 1)
                        ConflictResolver.Decision.Ignore -> DocumentApplyReport(ignored = 1)
                    }
                }
            }
        }
        return report
    }

    override suspend fun applyEvents(events: List<PulledEvent>): EventApplyReport = synchronized(this) {
        callLog += "events"
        var applied = 0
        var duplicates = 0
        for ((event, stamp) in events) {
            val key = PushItemId.ofEvent(event).value
            if (this.events.putIfAbsent(key, event) == null) applied++ else duplicates++
            stamp?.let { eventStamps.putIfAbsent(key, it) }
            lamportOf(event)?.let(::observe)
        }
        EventApplyReport(applied = applied, duplicates = duplicates)
    }

    override suspend fun applyMessages(messages: List<JsonObject>): MessageApplyReport {
        synchronized(this) { callLog += "messages" }
        val report = verifyMessages(messages) + retryHeldMessages()
        val keys = messages.map { (it["messageId"] as? JsonPrimitive)?.content.orEmpty() }.toSet()
        return report.copy(held = synchronized(this) { heldMessages.keys.count { it in keys } })
    }

    override suspend fun expectedSeq(deviceId: DeviceId, threadId: ThreadId): Long =
        synchronized(this) { nextSeqs[deviceId to threadId] ?: 1 }

    private suspend fun verifyMessages(messages: List<JsonObject>): MessageApplyReport {
        var report = MessageApplyReport()
        for (json in messages) {
            val key = (json["messageId"] as? JsonPrimitive)?.content.orEmpty()
            report += when (val verified = messageVerifier.verify(json, localMaxLamport(), ::expectedSeq)) {
                is VerifiedMessage.Ok -> synchronized(this) {
                    heldMessages.remove(key)
                    recordLocalMessage(verified.envelope)
                    MessageApplyReport(applied = 1)
                }

                is VerifiedMessage.Duplicate -> synchronized(this) {
                    heldMessages.remove(key)
                    MessageApplyReport(duplicates = 1)
                }

                is VerifiedMessage.Held -> synchronized(this) {
                    heldMessages[key] = json
                    MessageApplyReport(held = 1)
                }

                is VerifiedMessage.Quarantined -> synchronized(this) {
                    heldMessages.remove(key)
                    MessageApplyReport(quarantined = 1)
                }
            }
        }
        return report
    }

    /**
     * Retries held messages until a pass applies nothing more (a gap filled by one message may release the next).
     * Messages released here count as applied; those still held are not counted again.
     */
    private suspend fun retryHeldMessages(): MessageApplyReport {
        var released = MessageApplyReport()
        while (true) {
            val held = synchronized(this) { heldMessages.values.sortedBy { MessageJson.decodeOrNull(it)?.seq } }
            if (held.isEmpty()) break
            val pass = verifyMessages(held)
            released += pass.copy(held = 0)
            if (pass.applied == 0) break
        }
        return released
    }

    /** Re-verifies held documents and messages (the registry may have caught up); events are never held here. */
    override suspend fun retryHeld(): EventApplyReport {
        val held = synchronized(this) {
            callLog += "retryHeld"
            heldDocuments.values.toList()
        }
        if (held.isNotEmpty()) verifyAndStore(held)
        retryHeldMessages()
        return EventApplyReport()
    }

    override suspend fun cursor(): SyncCursor = synchronized(this) { savedCursor }

    override suspend fun saveCursor(cursor: SyncCursor) {
        synchronized(this) {
            callLog += "saveCursor"
            savedCursor = cursor
        }
    }

    override suspend fun localMaxLamport(): Long = synchronized(this) { lamport }

    private fun store(envelope: DocumentEnvelope): ConflictResolver.Decision {
        val key = "${envelope.docType}/${envelope.docId}"
        val decision = ConflictResolver.resolve(documents[key]?.let(StoredDocument::of), envelope)
        if (decision == ConflictResolver.Decision.Apply) documents[key] = envelope
        return decision
    }

    private fun observe(remote: Long) {
        if (remote > lamport) lamport = remote
    }

    private fun lamportOf(event: JsonObject): Long? =
        ((event["envelope"] as? JsonObject)?.get("lamport") as? JsonPrimitive)?.longOrNull
}

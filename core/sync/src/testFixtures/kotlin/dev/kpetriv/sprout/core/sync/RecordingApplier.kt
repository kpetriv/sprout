package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.domainledger.DeviceRegistry
import dev.kpetriv.sprout.core.domainledger.LedgerPolicy
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.RegisteredParentKey
import dev.kpetriv.sprout.core.domainledger.SignatureCheck
import dev.kpetriv.sprout.core.model.DeviceId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A [RemoteApplier] over in-memory maps, and the [DeviceRegistry] mirror it verifies against. Documents go through
 * the real [RemoteDocumentVerifier] and [ConflictResolver]; events are only recorded (deduplicated by
 * `(eventId, hash)`), since ledger verification and ingest belong to core/domain-ledger and core/database.
 *
 * [calls] records the order of applier calls (`registry`, `documents`, `events`, `retryHeld`, `saveCursor`) so tests
 * can assert the engine's sequence. Local writes go through [writeLocal], which takes the next lamport.
 */
class RecordingApplier(check: SignatureCheck, maxLamportJump: Long = LedgerPolicy.DEFAULT_MAX_LAMPORT_JUMP) :
    RemoteApplier,
    DeviceRegistry {
    private val devices = mutableMapOf<DeviceId, RegisteredDevice>()
    private val parentKeys = mutableMapOf<String, RegisteredParentKey>()
    private val documents = sortedMapOf<String, DocumentEnvelope>()
    private val events = LinkedHashMap<String, JsonObject>()
    private val rejections = mutableListOf<Pair<DocumentEnvelope, DocumentRejection>>()
    private val callLog = mutableListOf<String>()
    private var savedCursor = SyncCursor.START
    private var lamport = 0L
    private val verifier = RemoteDocumentVerifier(this, check, maxLamportJump)

    /** Applier calls in order. */
    val calls: List<String> get() = synchronized(this) { callLog.toList() }

    /** Documents keyed `docType/docId`. */
    fun documents(): Map<String, DocumentEnvelope> = synchronized(this) { documents.toMap() }

    fun document(docType: String, docId: String): DocumentEnvelope? = synchronized(this) {
        documents["$docType/$docId"]
    }

    /** Recorded events in arrival order. */
    fun events(): List<JsonObject> = synchronized(this) { events.values.toList() }

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
        var report = DocumentApplyReport()
        for (envelope in documents) {
            report += when (val verified = verifier.verify(envelope, localMaxLamport())) {
                is VerifiedDocument.Rejected -> {
                    synchronized(this) { rejections += envelope to verified.reason }
                    DocumentApplyReport(rejected = mapOf(verified.reason to 1))
                }

                is VerifiedDocument.Ok -> synchronized(this) {
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

    override suspend fun applyEvents(events: List<JsonObject>): EventApplyReport = synchronized(this) {
        callLog += "events"
        var applied = 0
        var duplicates = 0
        for (event in events) {
            if (this.events.putIfAbsent(PushItemId.ofEvent(event).value, event) == null) applied++ else duplicates++
            lamportOf(event)?.let(::observe)
        }
        EventApplyReport(applied = applied, duplicates = duplicates)
    }

    override suspend fun retryHeld(): EventApplyReport = synchronized(this) {
        callLog += "retryHeld"
        EventApplyReport()
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

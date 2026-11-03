package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.RegisteredParentKey
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * An in-memory "server" shared by any number of [FakeSyncTransport]s, emulating what the Firestore rules and
 * Cloud Functions enforce (M3 §4):
 * - devices and parent keys are written by the test (standing in for the registration functions);
 * - events are create-only, keyed by `(eventId, hash)`: a repeat is accepted, nothing is ever overwritten;
 * - documents keep one copy per `docType/docId`, replaced only when [ConflictResolver] says the incoming copy wins
 *   (LWW on `(lamport, authorDeviceId)`, submission precedence, tombstones final); a losing write is accepted;
 * - every write must come from the device it names (`authorDeviceId` and `authorUid` equal the caller's), from a
 *   registered device that accepts the write's lamport, and a KID device may only write its own `SUBMITTED`
 *   submissions ([DocTypes.kidMayWrite]). Signatures are not checked here, as Firestore rules cannot; receivers do.
 *
 * Each collection has its own monotonic sequence; a pull returns what was written after the cursor's position in
 * each collection, at most [pageSize] per collection per page.
 */
class FakeBackend(val householdId: HouseholdId, private val pageSize: Int = DEFAULT_PAGE_SIZE) {
    init {
        require(pageSize > 0) { "pageSize must be positive" }
    }

    /** A server-side write; [author] is null for writes made by the test itself (registry, injections). */
    data class Write(val collection: String, val author: DeviceId?)

    private class Stored<T>(val seq: Long, val value: T)

    private val sequences = mutableMapOf<String, Long>()
    private val devices = LinkedHashMap<DeviceId, Stored<RegisteredDevice>>()
    private val parentKeys = LinkedHashMap<String, Stored<RegisteredParentKey>>()
    private val documents = LinkedHashMap<String, Stored<DocumentEnvelope>>()
    private val events = LinkedHashMap<String, Stored<JsonObject>>()
    private val partitioned = mutableSetOf<DeviceId>()
    private val signedOut = mutableSetOf<DeviceId>()
    private var gone = false
    private val writeFlow = MutableSharedFlow<Write>(
        extraBufferCapacity = WRITE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every server write, as it happens. */
    val writes: SharedFlow<Write> = writeFlow.asSharedFlow()

    // ---- test controls ----

    @Synchronized
    fun registerDevice(device: RegisteredDevice) {
        devices.remove(device.deviceId)
        devices[device.deviceId] = Stored(nextSeq(DEVICES), device)
        writeFlow.tryEmit(Write(DEVICES, null))
    }

    @Synchronized
    fun addParentKey(key: RegisteredParentKey) {
        parentKeys.remove(key.fingerprint)
        parentKeys[key.fingerprint] = Stored(nextSeq(PARENT_KEYS), key)
        writeFlow.tryEmit(Write(PARENT_KEYS, null))
    }

    /** Revokes [deviceId] from [atLamport]: its writes at or above it are refused, here and by receivers. */
    @Synchronized
    fun revoke(deviceId: DeviceId, atLamport: Long) {
        val device = requireNotNull(devices[deviceId]) { "unknown device" }.value
        registerDevice(
            RegisteredDevice(
                device.deviceId,
                device.owner,
                device.publicKey,
                DeviceStatus.Revoked(atLamport),
                device.attestation,
            ),
        )
    }

    /** [deviceId]'s transport can no longer reach the server: push and pull throw a transient error. */
    @Synchronized
    fun partition(deviceId: DeviceId) {
        partitioned += deviceId
    }

    @Synchronized
    fun heal(deviceId: DeviceId) {
        partitioned -= deviceId
    }

    /** [deviceId]'s session is gone: push and pull throw [SyncError.Unauthenticated]. */
    @Synchronized
    fun signOut(deviceId: DeviceId) {
        signedOut += deviceId
    }

    @Synchronized
    fun signIn(deviceId: DeviceId) {
        signedOut -= deviceId
    }

    /** Every call from now on throws [SyncError.HouseholdGone]. */
    @Synchronized
    fun deleteHousehold() {
        gone = true
    }

    /** Stores [envelope] bypassing every rule (a compromised server or a rules bug), for receiver-side tests. */
    @Synchronized
    fun injectDocument(envelope: DocumentEnvelope) {
        storeDocument(envelope, author = null)
    }

    /** Stores [event] bypassing every rule, for receiver-side tests. */
    @Synchronized
    fun injectEvent(event: JsonObject) {
        storeEvent(event, author = null)
    }

    @Synchronized
    fun documents(): List<DocumentEnvelope> = documents.values.map { it.value }

    @Synchronized
    fun document(docType: String, docId: String): DocumentEnvelope? = documents["$docType/$docId"]?.value

    @Synchronized
    fun events(): List<JsonObject> = events.values.map { it.value }

    @Synchronized
    fun isPartitioned(deviceId: DeviceId): Boolean = deviceId in partitioned

    // ---- what transports call ----

    @Synchronized
    internal fun isAvailable(caller: DeviceId): Boolean = !gone && caller !in partitioned

    @Synchronized
    internal fun push(caller: Caller, events: List<JsonObject>, documents: List<DocumentEnvelope>): PushResult {
        checkReachable(caller.deviceId)
        val accepted = mutableListOf<PushItemId>()
        val rejected = mutableListOf<Pair<PushItemId, SyncError>>()
        for (event in events) {
            val id = PushItemId.ofEvent(event)
            when (val code = eventRuleViolation(caller, event)) {
                null -> {
                    storeEvent(event, caller.deviceId)
                    accepted += id
                }

                else -> rejected += id to SyncError.Permanent(code)
            }
        }
        for (document in documents) {
            val id = PushItemId.ofDocument(document)
            when (val code = documentRuleViolation(caller, document)) {
                null -> {
                    val stored = this.documents[key(document)]?.value?.let(StoredDocument::of)
                    if (ConflictResolver.resolve(stored, document) == ConflictResolver.Decision.Apply) {
                        storeDocument(document, caller.deviceId)
                    }
                    accepted += id
                }

                else -> rejected += id to SyncError.Permanent(code)
            }
        }
        return PushResult(accepted, rejected)
    }

    @Synchronized
    internal fun pull(caller: DeviceId, cursor: SyncCursor): PullPage {
        checkReachable(caller)
        var hasMore = false
        val next = cursor.perCollection.toMutableMap()
        fun <T> page(collection: String, source: Map<*, Stored<T>>): List<T> {
            val after = cursor.perCollection[collection]?.toLongOrNull() ?: 0
            val newer = source.values.filter { it.seq > after }.sortedBy { it.seq }
            if (newer.size > pageSize) hasMore = true
            val taken = newer.take(pageSize)
            taken.lastOrNull()?.let { next[collection] = it.seq.toString() }
            return taken.map { it.value }
        }
        return PullPage(
            devices = page(DEVICES, devices),
            parentKeys = page(PARENT_KEYS, parentKeys),
            documents = page(DOCUMENTS, documents),
            events = page(EVENTS, events),
            next = SyncCursor(next),
            hasMore = hasMore,
        )
    }

    internal class Caller(val deviceId: DeviceId, val uid: UserId, val role: Role)

    private fun checkReachable(caller: DeviceId) {
        when {
            gone -> throw SyncException(SyncError.HouseholdGone)
            caller in partitioned -> throw SyncException(SyncError.Transient())
            caller in signedOut -> throw SyncException(SyncError.Unauthenticated)
        }
    }

    private fun eventRuleViolation(caller: Caller, event: JsonObject): String? {
        val envelope = event["envelope"] as? JsonObject ?: return MALFORMED
        fun string(name: String) = (envelope[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val lamport = (envelope["lamport"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        val device = devices[caller.deviceId]?.value
        return when {
            string("eventId") == null || string("hash") == null || lamport == null -> MALFORMED

            string("householdId") != householdId.value -> WRONG_HOUSEHOLD

            string("authorDeviceId") != caller.deviceId.value || string("authorUid") != caller.uid.value ->
                AUTHOR_MISMATCH

            device == null || !device.acceptsLamport(lamport) -> DEVICE_NOT_ACTIVE

            else -> null
        }
    }

    private fun documentRuleViolation(caller: Caller, document: DocumentEnvelope): String? {
        val device = devices[caller.deviceId]?.value
        return when {
            document.householdId != householdId -> WRONG_HOUSEHOLD
            !DocTypes.isKnown(document.docType) -> UNKNOWN_DOC_TYPE
            document.signature == null -> UNSIGNED
            document.authorDeviceId != caller.deviceId || document.authorUid != caller.uid -> AUTHOR_MISMATCH
            device == null || !device.acceptsLamport(document.lamport) -> DEVICE_NOT_ACTIVE
            caller.role == Role.PARENT && device.role == Role.PARENT -> null
            caller.role == Role.KID && DocTypes.kidMayWrite(document, device) -> null
            else -> PERMISSION_DENIED
        }
    }

    private fun storeEvent(event: JsonObject, author: DeviceId?) {
        val key = PushItemId.ofEvent(event).value
        if (key in events) return
        events[key] = Stored(nextSeq(EVENTS), event)
        writeFlow.tryEmit(Write(EVENTS, author))
    }

    private fun storeDocument(document: DocumentEnvelope, author: DeviceId?) {
        val key = key(document)
        documents.remove(key)
        documents[key] = Stored(nextSeq(DOCUMENTS), document)
        writeFlow.tryEmit(Write(DOCUMENTS, author))
    }

    private fun key(document: DocumentEnvelope) = "${document.docType}/${document.docId}"

    private fun nextSeq(collection: String): Long =
        (sequences.getOrDefault(collection, 0) + 1).also { sequences[collection] = it }

    companion object {
        const val DEVICES: String = "devices"
        const val PARENT_KEYS: String = "parentKeys"
        const val DOCUMENTS: String = "documents"
        const val EVENTS: String = "events"
        const val DEFAULT_PAGE_SIZE: Int = 50

        const val MALFORMED: String = "malformed"
        const val WRONG_HOUSEHOLD: String = "wrong-household"
        const val UNKNOWN_DOC_TYPE: String = "unknown-doc-type"
        const val UNSIGNED: String = "unsigned"
        const val AUTHOR_MISMATCH: String = "author-mismatch"
        const val DEVICE_NOT_ACTIVE: String = "device-not-active"
        const val PERMISSION_DENIED: String = "permission-denied"

        private const val WRITE_BUFFER = 256
    }
}

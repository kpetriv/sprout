package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.domainhousehold.SubmissionStatusPrecedence
import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.RegisteredParentKey
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.SubmissionStatus
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * An in-memory "server" shared by any number of [FakeSyncTransport]s. It mirrors the **backend rules**
 * (`firestore/firestore.rules`, M3 §7) plus what `FirebaseSyncTransport` does around them, not the client's
 * [ConflictResolver]:
 *
 * - **Client-side checks** (as the real transport does before writing): a malformed event is refused
 *   [MALFORMED], a write for another household [WRONG_HOUSEHOLD], an unknown document type [UNKNOWN_DOC_TYPE], an
 *   unsigned document [UNSIGNED].
 * - **Author**: `authorUid` must be the caller's uid and `authorDeviceId` an **ACTIVE** device registered to that
 *   uid. A PENDING or REVOKED device writes nothing: [PERMISSION_DENIED].
 * - **Events** are create-only under their `eventId`. Re-sending a stored event (same hash) is accepted (the
 *   transport reads the stored copy). A different copy under a taken id is kept as evidence under `eventId__hash`,
 *   but only from a PARENT member; a KID member's conflicting copy is refused. A KID member writes only KID-role
 *   events for its own kid; a PARENT-role event needs a PARENT member.
 * - **Server-only collections**: a document of a registry type (`parent_key`, `device`, `member`) or naming a
 *   registry collection (`parentKeys`, `devices`, `members`) is [PERMISSION_DENIED] from every member, as the rules
 *   let only the server write those (D-M3-34); only the [DocTypes.all] client types are writable at all.
 * - **Documents**: a PARENT member writes every client type; a KID member only its own kid's `chore_submission` in
 *   status `SUBMITTED`, under the submission's own id, never a tombstone, never with changed identity (`id`, `choreId`,
 *   `dueOn`, `kidId`), and never over a stored copy that is decided, tombstoned or another kid's.
 *   An update must also pass the update rule: a tombstone is never overwritten by a live copy; a tombstone replaces a
 *   live copy regardless of lamport; between two live submissions the higher status rank wins and equal ranks fall
 *   back to last-writer-wins; everything else is last-writer-wins on `(lamport, authorDeviceId)`.
 *   A refused update is then read back, like the real transport does: when the stored copy beats the write under
 *   [ConflictResolver] the push counts as accepted (nothing is stored), otherwise it is [PERMISSION_DENIED].
 *
 * Every rules refusal is [PERMISSION_DENIED], as Firestore reports it. Signatures are not checked here, as rules
 * cannot; receivers do. [injectEvent] and [injectDocument] bypass every rule (a compromised server or a rules bug).
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

    /** The envelope fields of a pushed event the rules look at. */
    private class EventFields(
        val eventId: String,
        val hash: String,
        val householdId: String,
        val authorUid: String,
        val authorDeviceId: String,
        val authorRole: String,
        val kidId: String,
    )

    private val sequences = mutableMapOf<String, Long>()
    private val devices = LinkedHashMap<DeviceId, Stored<RegisteredDevice>>()
    private val parentKeys = LinkedHashMap<String, Stored<RegisteredParentKey>>()
    private val documents = LinkedHashMap<String, Stored<DocumentEnvelope>>()

    /** Keyed by Firestore document id: `eventId`, or `eventId__hash` for a conflicting copy. */
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

    /** Sets [deviceId]'s status, as the approve and revoke functions do. */
    @Synchronized
    fun setStatus(deviceId: DeviceId, status: DeviceStatus) {
        val device = requireNotNull(devices[deviceId]) { "unknown device" }.value
        registerDevice(RegisteredDevice(device.deviceId, device.owner, device.publicKey, status, device.attestation))
    }

    /** Approves a PENDING [deviceId] (`approveDevice`). */
    fun approve(deviceId: DeviceId) = setStatus(deviceId, DeviceStatus.Active)

    /**
     * Revokes [deviceId] (`revokeDevice`): it writes nothing from now on; receivers still accept what it wrote below
     * [atLamport].
     */
    fun revoke(deviceId: DeviceId, atLamport: Long) = setStatus(deviceId, DeviceStatus.Revoked(atLamport))

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

    /**
     * Stores [event] bypassing every rule, for receiver-side tests: under its `eventId`, or `eventId__hash` when the
     * id is taken by another copy.
     */
    @Synchronized
    fun injectEvent(event: JsonObject) {
        val fields = eventFieldsOf(event)
        val eventId = fields?.eventId ?: envelopeString(event, "eventId").orEmpty()
        val hash = fields?.hash ?: envelopeString(event, "hash").orEmpty()
        val existing = events[eventId]?.value
        when {
            existing == null -> storeEvent(eventId, event, author = null)
            envelopeString(existing, "hash") != hash -> storeEvent(conflictId(eventId, hash), event, author = null)
        }
    }

    @Synchronized
    fun documents(): List<DocumentEnvelope> = documents.values.map { it.value }

    @Synchronized
    fun document(docType: String, docId: String): DocumentEnvelope? = documents["$docType/$docId"]?.value

    /** Every stored event, conflicting copies (`eventId__hash`) included, in write order. */
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
            when (val code = pushEvent(caller, event)) {
                null -> accepted += id
                else -> rejected += id to SyncError.Permanent(code)
            }
        }
        for (document in documents) {
            val id = PushItemId.ofDocument(document)
            when (val code = pushDocument(caller, document)) {
                null -> accepted += id
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

    /** Who calls: the uid and role claim of the signed-in user, and the device its transport runs on. */
    internal class Caller(val deviceId: DeviceId, val uid: UserId, val role: Role)

    private fun checkReachable(caller: DeviceId) {
        when {
            gone -> throw SyncException(SyncError.HouseholdGone)
            caller in partitioned -> throw SyncException(SyncError.Transient())
            caller in signedOut -> throw SyncException(SyncError.Unauthenticated)
        }
    }

    // ---- events ----

    /** Null when accepted (stored, or already there); otherwise the refusal code. */
    private fun pushEvent(caller: Caller, event: JsonObject): String? {
        val fields = eventFieldsOf(event) ?: return MALFORMED
        if (fields.householdId != householdId.value) return WRONG_HOUSEHOLD
        val stored = events[fields.eventId]?.value
        return when {
            stored == null -> if (eventCreateAllowed(caller, fields)) {
                storeEvent(fields.eventId, event, caller.deviceId)
                null
            } else {
                PERMISSION_DENIED
            }

            // Create-only: the write is refused, and the transport finds the same event already stored.
            envelopeString(stored, "hash") == fields.hash -> null

            else -> pushConflictingCopy(caller, fields, event)
        }
    }

    /** A different copy under a taken eventId: evidence under `eventId__hash`, from a PARENT member only. */
    private fun pushConflictingCopy(caller: Caller, fields: EventFields, event: JsonObject): String? {
        val id = conflictId(fields.eventId, fields.hash)
        val stored = events[id]?.value
        return when {
            stored != null -> if (envelopeString(stored, "hash") == fields.hash) null else PERMISSION_DENIED

            caller.role == Role.PARENT && eventCreateAllowed(caller, fields) -> {
                storeEvent(id, event, caller.deviceId)
                null
            }

            else -> PERMISSION_DENIED
        }
    }

    private fun eventCreateAllowed(caller: Caller, fields: EventFields): Boolean {
        val roleAllowed = fields.authorRole == Role.KID.name ||
            (fields.authorRole == Role.PARENT.name && caller.role == Role.PARENT)
        val kidAllowed = caller.role == Role.PARENT ||
            (fields.authorRole == Role.KID.name && fields.kidId == memberKidIdOf(caller))
        return fields.authorUid == caller.uid.value &&
            isActiveDeviceOf(DeviceId(fields.authorDeviceId), caller.uid) &&
            roleAllowed &&
            kidAllowed
    }

    // ---- documents ----

    /** Null when accepted (stored, or beaten by the stored copy); otherwise the refusal code. */
    private fun pushDocument(caller: Caller, document: DocumentEnvelope): String? {
        when {
            // The rules: parentKeys, devices and members are written by the server only, whoever the caller is.
            document.docType in SERVER_ONLY -> return PERMISSION_DENIED

            !DocTypes.isKnown(document.docType) -> return UNKNOWN_DOC_TYPE

            document.householdId != householdId -> return WRONG_HOUSEHOLD

            document.signature == null -> return UNSIGNED
        }
        val stored = documents[key(document)]?.value
        return when {
            documentWriteAllowed(caller, document, stored) -> {
                storeDocument(document, caller.deviceId)
                null
            }

            // The transport reads the refused document back: a stored copy that wins makes the push a success.
            stored != null && ConflictResolver.resolve(StoredDocument.of(stored), document) ==
                ConflictResolver.Decision.Ignore -> null

            else -> PERMISSION_DENIED
        }
    }

    private fun documentWriteAllowed(caller: Caller, document: DocumentEnvelope, stored: DocumentEnvelope?): Boolean {
        val roleMayWrite = when (caller.role) {
            Role.PARENT -> true
            Role.KID -> kidMayWrite(caller, document, stored)
        }
        return document.authorUid == caller.uid &&
            isActiveDeviceOf(document.authorDeviceId, caller.uid) &&
            roleMayWrite &&
            (stored == null || updateAllowed(stored, document))
    }

    /**
     * The kid gate: an undecided submission of the member's own kid under its own id, never a tombstone, and never
     * over a stored copy that is decided, tombstoned, another kid's or of another identity.
     */
    private fun kidMayWrite(caller: Caller, document: DocumentEnvelope, stored: DocumentEnvelope?): Boolean {
        val kidId = memberKidIdOf(caller) ?: return false
        val payload = document.payload
        val ownSubmitted = document.docType == DocTypes.CHORE_SUBMISSION &&
            !document.deleted &&
            payload.string("kidId") == kidId &&
            payload.string("status") == SubmissionStatus.SUBMITTED.name &&
            payload.string("id") == document.docId
        val overUndecided = stored == null || (
            !stored.deleted &&
                stored.payload.string("kidId") == kidId &&
                stored.payload.string("status") == SubmissionStatus.SUBMITTED.name &&
                IDENTITY.all { stored.payload.string(it) == payload.string(it) }
            )
        return ownSubmitted && overUndecided
    }

    /**
     * The document update rule: (tombstone never overwritten by live) ∧ (tombstone over live regardless of lamport)
     * ∧ (submissions: higher status rank wins, equal → LWW) ∧ (otherwise LWW on `(lamport, authorDeviceId)`).
     */
    private fun updateAllowed(stored: DocumentEnvelope, incoming: DocumentEnvelope): Boolean = when {
        stored.deleted && !incoming.deleted -> false

        !stored.deleted && incoming.deleted -> true

        !stored.deleted && incoming.docType == DocTypes.CHORE_SUBMISSION -> {
            val newRank = rankOf(incoming.payload)
            val oldRank = rankOf(stored.payload)
            if (newRank != oldRank) newRank > oldRank else newerThan(stored, incoming)
        }

        else -> newerThan(stored, incoming)
    }

    private fun newerThan(stored: DocumentEnvelope, incoming: DocumentEnvelope): Boolean =
        incoming.lamport > stored.lamport ||
            (incoming.lamport == stored.lamport && incoming.authorDeviceId.value > stored.authorDeviceId.value)

    private fun rankOf(payload: JsonObject): Int =
        SubmissionStatus.entries.firstOrNull { it.name == payload.string("status") }
            ?.let(SubmissionStatusPrecedence::rank)
            ?: UNKNOWN_RANK

    // ---- shared ----

    private fun isActiveDeviceOf(deviceId: DeviceId, uid: UserId): Boolean {
        val device = devices[deviceId]?.value ?: return false
        return device.uid == uid && device.status == DeviceStatus.Active
    }

    /** The member's kid (`members/{uid}.kidId`), mirrored on the devices the functions registered for that uid. */
    private fun memberKidIdOf(caller: Caller): String? =
        devices.values.firstOrNull { it.value.uid == caller.uid && it.value.kidId != null }?.value?.kidId?.value

    private fun storeEvent(id: String, event: JsonObject, author: DeviceId?) {
        if (id in events) return
        events[id] = Stored(nextSeq(EVENTS), event)
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
        const val PERMISSION_DENIED: String = "permission-denied"

        private const val WRITE_BUFFER = 256
        private const val UNKNOWN_RANK = -1
        private val IDENTITY = listOf("id", "choreId", "dueOn", "kidId")

        /**
         * Registry types and collections only the server writes (`firestore/firestore.rules`): a client document
         * naming one is refused by the rules, never stored.
         */
        val SERVER_ONLY: Set<String> = setOf("parent_key", "device", "member", PARENT_KEYS, DEVICES, "members")

        private fun conflictId(eventId: String, hash: String) = "${eventId}__$hash"

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun envelopeString(event: JsonObject, name: String): String? =
            (event["envelope"] as? JsonObject)?.string(name)

        private fun eventFieldsOf(event: JsonObject): EventFields? {
            val envelope = event["envelope"] as? JsonObject ?: return null
            val lamport = (envelope["lamport"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            if (lamport == null || lamport < 0) return null
            return EventFields(
                eventId = envelope.string("eventId") ?: return null,
                hash = envelope.string("hash") ?: return null,
                householdId = envelope.string("householdId") ?: return null,
                authorUid = envelope.string("authorUid") ?: return null,
                authorDeviceId = envelope.string("authorDeviceId") ?: return null,
                authorRole = envelope.string("authorRole") ?: return null,
                kidId = envelope.string("kidId") ?: return null,
            )
        }
    }
}

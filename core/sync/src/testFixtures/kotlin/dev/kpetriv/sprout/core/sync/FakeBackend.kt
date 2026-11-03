package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.domainhousehold.SubmissionStatusPrecedence
import dev.kpetriv.sprout.core.domainledger.BoundEncryptionKey
import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.RegisteredParentKey
import dev.kpetriv.sprout.core.domainledger.SignatureCheck
import dev.kpetriv.sprout.core.model.BlobId
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.ID_PATTERN
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.SubmissionStatus
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64

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
 * - **Messages** (`messages/{messageId}`, M4 §3) are create-only: a closed key set, the author an ACTIVE device of
 *   the caller, a PARENT-role message only from a PARENT member (a KID-role one from any member: kid mode on a parent
 *   phone, D-M3-12), a KID member only KID-role messages in its own thread (`threadId` = its kid),
 *   `seq >= 1`, `epoch >= 0`, a known `kind`, `blobId` set exactly for VOICE and PHOTO, and a ciphertext of 1 to
 *   [MAX_MESSAGE_CIPHERTEXT_BYTES] bytes **once base64-decoded** (Firestore stores bytes). Re-sending the identical
 *   message is accepted; another message under a taken id is [PERMISSION_DENIED].
 * - **Blob chunks** (`blobs/{blobId}.{chunk}`) are create-only: a closed key set, an ACTIVE author device of the
 *   caller, exactly one of `threadId` (equal to `kidId`) and `submissionId`, a KID member only for its own `kidId`,
 *   a `kidId` with a stored `kid` document, `chunk` in 0..1, `chunks` in 1..2 (`chunk < chunks`), a hex `sha256`, and
 *   1 to [MAX_BLOB_CHUNK_BYTES] bytes of data. Identical re-sends are accepted. At most [DAILY_BLOB_CHUNKS] new chunks
 *   per household per UTC day ([nowEpochMs]), as the rules' `quotas/{yyyymmdd}` counter allows. As the transport
 *   reports it (D-M4-22), a chunk the rules refuse is [SyncError.Transient], never permanent: with a retry at the next
 *   UTC midnight when the day's budget is used up, otherwise with the default backoff. Only a chunk the transport
 *   refuses before writing (malformed, foreign household, unsigned) is permanent.
 * - **Thread keys** (`thread_key` documents, PARENT only like every non-submission type): never a tombstone; docId
 *   `"<threadId>.<epoch>.<createdByDeviceId>"`; `createdByDeviceId` is the author device; `lamport` at most
 *   [MAX_THREAD_KEY_LAMPORT]; an update only from the same author device (one creator per document, D-M4-3).
 * - **Read scoping**: a PARENT member pulls every message; a KID member only those of its own thread, and fetches
 *   only blobs of its own kid ([PERMISSION_DENIED] otherwise). Blobs are never pulled, only fetched by id.
 * - **Encryption keys** (M4 §1.1): [registerEncryptionKey] mirrors the `registerEncryptionKey` callable (binding
 *   verified against the device key, idempotent for the same key, refused for a different one); a device registered
 *   with [RegisteredDevice.encryptionKey] already set mirrors the registration callables' optional key.
 *
 * Every rules refusal is [PERMISSION_DENIED], as Firestore reports it. Signatures are not checked here, as rules
 * cannot; receivers do.
 *
 * **Push order** (like `FirebaseSyncTransport`): items are processed in the order given. After a permanent refusal of
 * an item whose kind [blocks on refusal][OutboxItem.Kind.blocksOnRefusal] (event, message, blob chunk), or a transient
 * one of any item, the rest of that item's lane ([OutboxItem.Lane], D-M4-22) is left unprocessed (in neither result
 * list); the other lane goes on. [injectEvent], [injectDocument], [injectMessage] and [injectBlobChunk] bypass every
 * rule (a compromised server or a rules bug).
 *
 * Each collection has its own monotonic sequence; a pull returns what was written after the cursor's position in
 * each collection, at most [pageSize] per collection per page.
 */
class FakeBackend(val householdId: HouseholdId, private val pageSize: Int = DEFAULT_PAGE_SIZE) {
    init {
        require(pageSize > 0) { "pageSize must be positive" }
    }

    /** The server clock (epoch ms) that decides the blob budget's UTC day; tests may replace it. */
    @Volatile
    var nowEpochMs: () -> Long = System::currentTimeMillis

    /** New blob chunks stored per UTC day (epoch day), as `quotas/{yyyymmdd}.chunks`. */
    private val blobChunksPerDay = mutableMapOf<Long, Int>()

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

    /** Keyed by `messageId`. */
    private val messages = LinkedHashMap<String, Stored<JsonObject>>()

    /** Keyed by `"<blobId>.<chunk>"`. */
    private val blobs = LinkedHashMap<String, Stored<BlobChunkJson>>()
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

    /** Sets [deviceId]'s status, as the approve and revoke functions do. Its encryption key stays. */
    @Synchronized
    fun setStatus(deviceId: DeviceId, status: DeviceStatus) {
        val device = requireNotNull(devices[deviceId]) { "unknown device" }.value
        registerDevice(
            RegisteredDevice(
                device.deviceId,
                device.owner,
                device.publicKey,
                status,
                device.attestation,
                device.encryptionKey,
            ),
        )
    }

    /** What [registerEncryptionKey] did, as the `registerEncryptionKey` callable answers. */
    enum class EncryptionKeyRegistration {
        /** Stored on the device row. */
        REGISTERED,

        /** The same key was already registered (idempotent retry). */
        UNCHANGED,

        /** The binding does not verify against the device key, or names another household (`permission-denied`). */
        INVALID_BINDING,

        /** The device is not ACTIVE (`permission-denied`). */
        NOT_ACTIVE,

        /** A different key is already registered (`failed-precondition`; a changed key needs re-registration). */
        DIFFERENT_KEY,
    }

    /**
     * Registers [key] as [deviceId]'s encryption key, as the `registerEncryptionKey` callable does: the binding must
     * verify with [check] against the device's registered key, the device must be ACTIVE, and a different key already
     * on the row is refused.
     */
    @Synchronized
    fun registerEncryptionKey(
        deviceId: DeviceId,
        key: BoundEncryptionKey,
        check: SignatureCheck,
    ): EncryptionKeyRegistration {
        val device = requireNotNull(devices[deviceId]) { "unknown device" }.value
        val existing = device.encryptionKey
        return when {
            device.status != DeviceStatus.Active -> EncryptionKeyRegistration.NOT_ACTIVE

            key.householdId != householdId || !key.verify(check, deviceId, device.publicKey) ->
                EncryptionKeyRegistration.INVALID_BINDING

            existing != null && existing.publicKey.contentEquals(key.publicKey) -> EncryptionKeyRegistration.UNCHANGED

            existing != null -> EncryptionKeyRegistration.DIFFERENT_KEY

            else -> {
                registerDevice(
                    RegisteredDevice(
                        device.deviceId,
                        device.owner,
                        device.publicKey,
                        device.status,
                        device.attestation,
                        key,
                    ),
                )
                EncryptionKeyRegistration.REGISTERED
            }
        }
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

    /** Stores [message] (a `MessageJson` tree) bypassing every rule, under its `messageId`, replacing any copy. */
    @Synchronized
    fun injectMessage(message: JsonObject) {
        storeMessage(message.string(MESSAGE_ID).orEmpty(), message, author = null, replace = true)
    }

    /** Stores [chunk] bypassing every rule, under its document id, replacing any copy. */
    @Synchronized
    fun injectBlobChunk(chunk: BlobChunkJson) {
        storeBlobChunk(chunk, author = null, replace = true)
    }

    /** Every stored message (`MessageJson` trees: ciphertext only), in write order. */
    @Synchronized
    fun messages(): List<JsonObject> = messages.values.map { it.value }

    /** Every stored blob chunk (ciphertext only), in write order. */
    @Synchronized
    fun blobs(): List<BlobChunkJson> = blobs.values.map { it.value }

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
    internal fun push(caller: Caller, items: List<PushItem>): PushResult {
        checkReachable(caller.deviceId)
        val accepted = mutableListOf<PushItemId>()
        val rejected = mutableListOf<Pair<PushItemId, SyncError>>()
        val stopped = mutableSetOf<OutboxItem.Lane>()
        for (item in items) {
            if (item.kind.lane in stopped) continue
            val error = when (item) {
                is PushItem.Event -> pushEvent(caller, item.json)?.let(SyncError::Permanent)
                is PushItem.Document -> pushDocument(caller, item.envelope)?.let(SyncError::Permanent)
                is PushItem.Message -> pushMessage(caller, item.json)?.let(SyncError::Permanent)
                is PushItem.BlobChunk -> pushBlobChunk(caller, item.chunk)
            }
            if (error == null) {
                accepted += item.id
            } else {
                rejected += item.id to error
                // Head of line per lane: the rest of the lane waits behind a blocking or transient refusal.
                if (error is SyncError.Transient || item.kind.blocksOnRefusal) stopped += item.kind.lane
            }
        }
        return PushResult(accepted, rejected)
    }

    @Synchronized
    internal fun pull(caller: Caller, cursor: SyncCursor): PullPage {
        checkReachable(caller.deviceId)
        var hasMore = false
        val next = cursor.perCollection.toMutableMap()
        fun <T> page(collection: String, source: Map<*, Stored<T>>, readable: (T) -> Boolean = { true }): List<T> {
            val after = cursor.perCollection[collection]?.toLongOrNull() ?: 0
            val newer = source.values.filter { it.seq > after && readable(it.value) }.sortedBy { it.seq }
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
            messages = page(MESSAGES, messages) { mayReadThread(caller, it.string(THREAD_ID)) },
            next = SyncCursor(next),
            hasMore = hasMore,
        )
    }

    /**
     * Every stored chunk of [blobId] in chunk order, as the transport's `fetchBlob` reads them: empty when chunk 0
     * does not exist; [BlobIncompleteException] when fewer than chunk 0's `chunks` are stored; [PERMISSION_DENIED]
     * when a KID member asks for another kid's blob.
     */
    @Synchronized
    internal fun fetchBlob(caller: Caller, blobId: BlobId): Result<List<BlobChunkJson>> {
        try {
            checkReachable(caller.deviceId)
        } catch (failure: SyncException) {
            return Result.failure(failure)
        }
        val first = blobs["${blobId.value}.0"]?.value ?: return Result.success(emptyList())
        if (!mayReadKid(caller, first.header.string(KID_ID))) {
            return Result.failure(SyncException(SyncError.Permanent(PERMISSION_DENIED)))
        }
        val expected = first.header.long(CHUNKS)?.toInt()?.coerceIn(1, MAX_BLOB_CHUNKS) ?: 1
        val chunks = (0 until expected).mapNotNull { blobs["${blobId.value}.$it"]?.value }
        return when {
            chunks.size < expected -> Result.failure(BlobIncompleteException(chunks.size, expected))
            chunks.all { mayReadKid(caller, it.header.string(KID_ID)) } -> Result.success(chunks)
            else -> Result.failure(SyncException(SyncError.Permanent(PERMISSION_DENIED)))
        }
    }

    /** A PARENT member reads every thread; a KID member only its own kid's. */
    private fun mayReadThread(caller: Caller, threadId: String?): Boolean = mayReadKid(caller, threadId)

    private fun mayReadKid(caller: Caller, kidId: String?): Boolean =
        caller.role == Role.PARENT || (kidId != null && kidId == memberKidIdOf(caller))

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
            (document.docType != DocTypes.THREAD_KEY || threadKeyAllowed(document, stored)) &&
            (stored == null || updateAllowed(stored, document))
    }

    /**
     * The `threadKeys` hardening (audit M2): no tombstone, docId `"<threadId>.<epoch>.<createdByDeviceId>"`, created
     * by the author device, a sane lamport, and an update only from the same author device.
     */
    private fun threadKeyAllowed(document: DocumentEnvelope, stored: DocumentEnvelope?): Boolean {
        val payload = document.payload
        val creator = payload.string("createdByDeviceId")
        val epoch = payload.long("epoch")
        return !document.deleted &&
            creator == document.authorDeviceId.value &&
            epoch != null && epoch >= 0 &&
            document.docId == "${payload.string(THREAD_ID)}.$epoch.$creator" &&
            document.lamport <= MAX_THREAD_KEY_LAMPORT &&
            (stored == null || stored.authorDeviceId == document.authorDeviceId)
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

    // ---- messages ----

    /** Null when accepted (stored, or the identical message already there); otherwise the refusal code. */
    private fun pushMessage(caller: Caller, message: JsonObject): String? {
        val messageId = message.string(MESSAGE_ID)
        when {
            messageId == null || !ID_PATTERN.matches(messageId) || decodeBase64(message[CIPHERTEXT]) == null ->
                return MALFORMED

            message.string(HOUSEHOLD_ID) != householdId.value -> return WRONG_HOUSEHOLD

            message.string(SIGNATURE) == null -> return UNSIGNED
        }
        val stored = messages[messageId]?.value
        return when {
            // Create-only: the transport reads the refused write back and accepts an identical copy.
            stored != null -> if (stored == message) null else PERMISSION_DENIED

            messageCreateAllowed(caller, message) -> {
                storeMessage(requireNotNull(messageId), message, caller.deviceId, replace = false)
                null
            }

            else -> PERMISSION_DENIED
        }
    }

    /** The `messages/{messageId}` create rule (M4 §3). */
    private fun messageCreateAllowed(caller: Caller, m: JsonObject): Boolean {
        val kind = m.string(KIND)
        val blobId = m[BLOB_ID]
        val blobIdOk = blobId == null || blobId == JsonNull || m.string(BLOB_ID)?.let { it.length in 1..MAX_ID } == true
        val hasBlob = m.string(BLOB_ID) != null
        val ciphertext = decodeBase64(m[CIPHERTEXT])
        val threadId = m.string(THREAD_ID)
        return MESSAGE_KEYS.containsAll(m.keys) &&
            m.keys.containsAll(MESSAGE_KEYS - BLOB_ID) &&
            m.long(SCHEMA_VERSION) == 1L &&
            threadId != null && threadId.length in 1..MAX_ID &&
            m.string(AUTHOR_UID) == caller.uid.value &&
            m.string(AUTHOR_DEVICE_ID)?.let { isActiveDeviceOf(DeviceId(it), caller.uid) } == true &&
            roleAllowed(caller, m.string(AUTHOR_ROLE), threadId) &&
            (m.long(SEQ) ?: 0) >= 1 &&
            (m.long(LAMPORT) ?: -1) >= 0 &&
            m.long(CREATED_AT) != null &&
            (m.long(EPOCH) ?: -1) >= 0 &&
            kind in MESSAGE_KINDS &&
            blobIdOk && hasBlob == (kind in BLOB_KINDS) &&
            ciphertext != null && ciphertext.size in 1..MAX_MESSAGE_CIPHERTEXT_BYTES &&
            m.string(SIGNATURE)?.length in 1..MAX_SIGNATURE
    }

    /** As for events: KID-role from any member, PARENT-role from a PARENT member; a KID member in its own thread. */
    private fun roleAllowed(caller: Caller, authorRole: String?, threadId: String?): Boolean {
        val claimed = authorRole == Role.KID.name || (authorRole == Role.PARENT.name && caller.role == Role.PARENT)
        val scoped = caller.role == Role.PARENT || (authorRole == Role.KID.name && threadId == memberKidIdOf(caller))
        return claimed && scoped
    }

    private fun storeMessage(id: String, message: JsonObject, author: DeviceId?, replace: Boolean) {
        if (!replace && id in messages) return
        messages.remove(id)
        messages[id] = Stored(nextSeq(MESSAGES), message)
        writeFlow.tryEmit(Write(MESSAGES, author))
    }

    // ---- blobs ----

    /**
     * Null when accepted (stored, or the identical chunk already there); otherwise the refusal: permanent for what the
     * transport refuses unsent; [SyncError.Transient] for a rules refusal (D-M4-22), until the next UTC midnight when
     * the household's daily blob budget is used up.
     */
    private fun pushBlobChunk(caller: Caller, chunk: BlobChunkJson): SyncError? {
        val header = chunk.header
        when {
            chunk.blobId == null || chunk.chunk == null || !ID_PATTERN.matches(chunk.docId) ->
                return SyncError.Permanent(MALFORMED)

            header.string(HOUSEHOLD_ID) != householdId.value -> return SyncError.Permanent(WRONG_HOUSEHOLD)

            header.string(SIGNATURE) == null -> return SyncError.Permanent(UNSIGNED)
        }
        val stored = blobs[chunk.docId]?.value
        val now = nowEpochMs()
        val day = Math.floorDiv(now, DAY_MS)
        return when {
            stored != null -> if (stored == chunk) null else SyncError.Transient()

            !blobCreateAllowed(caller, chunk) -> SyncError.Transient()

            blobChunksPerDay.getOrDefault(day, 0) >= DAILY_BLOB_CHUNKS ->
                SyncError.Transient(retryAfterMs = (day + 1) * DAY_MS - now)

            else -> {
                blobChunksPerDay[day] = blobChunksPerDay.getOrDefault(day, 0) + 1
                storeBlobChunk(chunk, caller.deviceId, replace = false)
                null
            }
        }
    }

    /** The `blobs/{blobId}.{chunk}` create rule (M4 §2, §3). */
    private fun blobCreateAllowed(caller: Caller, chunk: BlobChunkJson): Boolean {
        val h = chunk.header
        val kidId = h.string(KID_ID)
        val threadId = h.string(THREAD_ID)
        val submissionId = h.string(SUBMISSION_ID)
        val index = h.long(CHUNK)
        val count = h.long(CHUNKS)
        return BLOB_KEYS.containsAll(h.keys) &&
            h.keys.containsAll(BLOB_KEYS - THREAD_ID - SUBMISSION_ID) &&
            h.long(SCHEMA_VERSION) == 1L &&
            kidId != null && kidId.length in 1..MAX_ID &&
            documents.containsKey("${DocTypes.KID}/$kidId") &&
            ((threadId == null) != (submissionId == null)) &&
            (threadId == null || threadId == kidId) &&
            (submissionId == null || submissionId.length in 1..MAX_ID) &&
            h.string(AUTHOR_UID) == caller.uid.value &&
            h.string(AUTHOR_DEVICE_ID)?.let { isActiveDeviceOf(DeviceId(it), caller.uid) } == true &&
            (caller.role == Role.PARENT || kidId == memberKidIdOf(caller)) &&
            (h.long(LAMPORT) ?: -1) >= 0 &&
            index != null && count != null && count in 1..MAX_BLOB_CHUNKS.toLong() && index in 0 until count &&
            h.string(SHA256)?.let(SHA256_HEX::matches) == true &&
            (h.long(EPOCH) ?: -1) >= 0 &&
            h.string(SIGNATURE)?.length in 1..MAX_SIGNATURE &&
            chunk.size in 1..MAX_BLOB_CHUNK_BYTES
    }

    private fun storeBlobChunk(chunk: BlobChunkJson, author: DeviceId?, replace: Boolean) {
        val id = chunk.docId
        if (!replace && id in blobs) return
        blobs.remove(id)
        blobs[id] = Stored(nextSeq(BLOBS), chunk)
        writeFlow.tryEmit(Write(BLOBS, author))
    }

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
        const val MESSAGES: String = "messages"
        const val BLOBS: String = "blobs"
        const val DEFAULT_PAGE_SIZE: Int = 50

        /** The rules' size limits on decoded bytes (M4 §3; core/domain-ledger `ChatLimits`). */
        const val MAX_MESSAGE_CIPHERTEXT_BYTES: Int = 8192
        const val MAX_BLOB_CHUNK_BYTES: Int = 716_800
        const val MAX_BLOB_CHUNKS: Int = 2

        /** New blob chunks a household may store per UTC day (`quotas/{yyyymmdd}`, audit H2). */
        const val DAILY_BLOB_CHUNKS: Int = 60

        /** The rules' sanity bound on a thread key document's lamport (2^50). */
        const val MAX_THREAD_KEY_LAMPORT: Long = 1L shl 50

        private const val DAY_MS: Long = 86_400_000

        const val MALFORMED: String = "malformed"
        const val WRONG_HOUSEHOLD: String = "wrong-household"
        const val UNKNOWN_DOC_TYPE: String = "unknown-doc-type"
        const val UNSIGNED: String = "unsigned"
        const val PERMISSION_DENIED: String = "permission-denied"

        private const val WRITE_BUFFER = 256
        private const val MAX_ID = 128
        private const val MAX_SIGNATURE = 200

        private const val SCHEMA_VERSION = "schemaVersion"
        private const val HOUSEHOLD_ID = "householdId"
        private const val THREAD_ID = "threadId"
        private const val MESSAGE_ID = "messageId"
        private const val AUTHOR_UID = "authorUid"
        private const val AUTHOR_DEVICE_ID = "authorDeviceId"
        private const val AUTHOR_ROLE = "authorRole"
        private const val SEQ = "seq"
        private const val LAMPORT = "lamport"
        private const val CREATED_AT = "createdAt"
        private const val EPOCH = "epoch"
        private const val KIND = "kind"
        private const val CIPHERTEXT = "ciphertext"
        private const val BLOB_ID = "blobId"
        private const val SIGNATURE = "signature"
        private const val KID_ID = "kidId"
        private const val SUBMISSION_ID = "submissionId"
        private const val CHUNK = "chunk"
        private const val CHUNKS = "chunks"
        private const val SHA256 = "sha256"

        /** The closed key set of a message (core/domain-ledger `MessageJson`); every key but `blobId` is required. */
        private val MESSAGE_KEYS = setOf(
            SCHEMA_VERSION, HOUSEHOLD_ID, THREAD_ID, MESSAGE_ID, AUTHOR_UID, AUTHOR_DEVICE_ID, AUTHOR_ROLE, SEQ,
            LAMPORT, CREATED_AT, EPOCH, KIND, CIPHERTEXT, BLOB_ID, SIGNATURE,
        )

        /** The closed key set of a blob chunk header (`BlobJson`); `threadId`/`submissionId` may be absent. */
        private val BLOB_KEYS = setOf(
            SCHEMA_VERSION, HOUSEHOLD_ID, BLOB_ID, THREAD_ID, KID_ID, SUBMISSION_ID, AUTHOR_UID, AUTHOR_DEVICE_ID,
            LAMPORT, CHUNK, CHUNKS, SHA256, EPOCH, SIGNATURE,
        )
        private val MESSAGE_KINDS = setOf("TEXT", "PRESET", "VOICE", "PHOTO")
        private val BLOB_KINDS = setOf("VOICE", "PHOTO")
        private val SHA256_HEX = Regex("[0-9a-f]{64}")

        private fun JsonObject.long(key: String): Long? =
            (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

        /** Strict standard base64 of a JSON string, or null. */
        private fun decodeBase64(element: kotlinx.serialization.json.JsonElement?): ByteArray? {
            val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            return try {
                Base64.Default.decode(text)
            } catch (expected: IllegalArgumentException) {
                null
            }
        }
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

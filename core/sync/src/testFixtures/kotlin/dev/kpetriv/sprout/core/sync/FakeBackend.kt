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
import kotlin.time.Instant

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
 * - **Household policy** (`household_policy`, PARENT only): the closed payload key set of policy v2, with
 *   `chatRetentionDays` 1 to 3650 and `dailyGameCapMinor` 0 to 10,000 when set (M7 §1); `requireParentSignature`
 *   never goes from true to false over a stored copy (D-REL-23).
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

    /**
     * The server clock (epoch ms): it decides the blob budget's UTC day and stamps every stored event's
     * `serverReceivedAt`. Fixed at [DEFAULT_NOW_EPOCH_MS] (the ledger fixtures' day) so game events dated on the
     * fixtures' day stay inside their played-on window whenever the tests run; tests may replace it.
     */
    @Volatile
    var nowEpochMs: () -> Long = { DEFAULT_NOW_EPOCH_MS }

    /** New blob chunks stored per UTC day (epoch day), as `quotas/{yyyymmdd}.chunks`. */
    private val blobChunksPerDay = mutableMapOf<Long, Int>()

    /** A server-side write; [author] is null for writes made by the test itself (registry, injections). */
    data class Write(val collection: String, val author: DeviceId?)

    /** A stored row: its collection sequence, the value and the server time it was stored at ([nowEpochMs]). */
    private class Stored<T>(val seq: Long, val value: T, val at: Long = 0)

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

    /**
     * Keyed by Firestore document id: `eventId`, or `eventId__hash` for a conflicting copy. Each with its
     * `serverReceivedAt` stamp ([nowEpochMs] when stored), pulled with it as a [PulledEvent].
     */
    private val events = LinkedHashMap<String, Stored<PulledEvent>>()

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
        // For the recent-sign-in check, the first registration of an id is the device's sign-in time: a phone
        // registers right after signing in.
        signedInAtMs.getOrPut(device.deviceId) { nowEpochMs() }
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
     * id is taken by another copy. [serverReceivedAt] is its stamp (default: [nowEpochMs]).
     */
    @Synchronized
    fun injectEvent(event: JsonObject, serverReceivedAt: Instant = Instant.fromEpochMilliseconds(nowEpochMs())) {
        val fields = eventFieldsOf(event)
        val eventId = fields?.eventId ?: envelopeString(event, "eventId").orEmpty()
        val hash = fields?.hash ?: envelopeString(event, "hash").orEmpty()
        val existing = events[eventId]?.value?.json
        when {
            existing == null -> storeEvent(eventId, event, author = null, serverReceivedAt)

            envelopeString(existing, "hash") != hash ->
                storeEvent(conflictId(eventId, hash), event, author = null, serverReceivedAt)
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
    fun events(): List<JsonObject> = events.values.map { it.value.json }

    @Synchronized
    fun isPartitioned(deviceId: DeviceId): Boolean = deviceId in partitioned

    /** What [pruneMessages] deleted: messages and chat blob chunks. */
    data class PruneCounts(val messages: Int, val blobChunks: Int)

    /** A callable refusal of [pruneMessages], with the Firebase error code (`permission-denied`, ...). */
    class CallableRefusedException(val code: String) : Exception(code)

    /**
     * The household's chat prune watermark (server `households/{hid}.chatPrune.prunedBefore`, D-M7-53): the latest cut
     * any [pruneMessages] applied, never moved back; null before the first prune. Every pull page carries it here;
     * the Firebase transport reads it only for pages that carry messages (audit 2 I7), which is all the engine needs:
     * a gap can only show when messages come.
     */
    @get:Synchronized
    var chatPrunedBefore: Instant? = null
        private set

    /**
     * Mirrors the server's `pruneMessages` callable (M7 §2, D-M7-8), minus the device proof, the rate limit, the
     * per-call limits and the audit record: the [caller] must be an ACTIVE PARENT device (`permission-denied`);
     * [before] at most [PRUNE_CLOCK_SKEW_MS] ahead of [nowEpochMs] (`invalid-argument`, clamped to now); without
     * [purge], the household's synced `chatRetentionDays` must be set (`failed-precondition`) and [before] no later
     * than that many days before now, plus the skew (`invalid-argument`, D-M7-51: a later cut deletes more). Deletes
     * every message the server stored before the cut (its own stamp, never the author's `createdAt`, D-M7-52), then
     * every chat blob chunk (never a chore photo's) stored more than [PRUNE_BLOB_GRACE_MS] before the cut that no
     * remaining message names, and raises [chatPrunedBefore].
     */
    @Synchronized
    fun pruneMessages(caller: DeviceId, before: Instant, purge: Boolean): Result<PruneCounts> {
        val device = devices[caller]?.value
        val now = nowEpochMs()
        val requested = before.toEpochMilliseconds()
        val days = (document(DocTypes.HOUSEHOLD_POLICY, householdId.value)?.takeUnless { it.deleted }?.payload)
            ?.long(CHAT_RETENTION_DAYS)?.takeIf { it in POLICY_RETENTION_DAYS }
        val refusal = when {
            gone -> "not-found"
            device == null || device.role != Role.PARENT || device.status != DeviceStatus.Active -> PERMISSION_DENIED
            requested > now + PRUNE_CLOCK_SKEW_MS -> "invalid-argument"
            !purge && days == null -> "failed-precondition"
            !purge && requested > now - requireNotNull(days) * DAY_MS + PRUNE_CLOCK_SKEW_MS -> "invalid-argument"
            else -> null
        }
        if (refusal != null) return Result.failure(CallableRefusedException(refusal))
        val cut = minOf(requested, now)
        val doomed = messages.filterValues { it.at < cut }.keys
        doomed.forEach { messages.remove(it) }
        val named = messages.values.mapNotNull { it.value.string(BLOB_ID) }.toSet()
        val chunks = blobs.filterValues { stored ->
            val header = stored.value.header
            stored.at < cut - PRUNE_BLOB_GRACE_MS && header.string(THREAD_ID) != null &&
                header.string(BLOB_ID) !in named
        }.keys
        chunks.forEach { blobs.remove(it) }
        val cutInstant = Instant.fromEpochMilliseconds(cut)
        chatPrunedBefore = maxOf(chatPrunedBefore ?: cutInstant, cutInstant)
        return Result.success(PruneCounts(doomed.size, chunks.size))
    }

    /**
     * The currency `createHousehold` stored on the household root document (server `households/{hid}.fiatCurrency`);
     * null for a root without one. Read by [householdRoot] (D-REL-14).
     */
    @Volatile
    var fiatCurrency: String? = null

    /**
     * False models a household root document that does not exist (`households/{hid}` missing): [householdRoot] then
     * fails [SyncError.Transient], as `FirebaseSyncTransport` reports it, never an answer without a currency.
     */
    @Volatile
    var householdRootStored: Boolean = true

    /**
     * A read of [householdRoot] the rules refuse on one path ([RootPath]): it fails [PERMISSION_DENIED] permanently,
     * as `FirebaseSyncTransport` reports a denial for a member whose household is still readable.
     */
    @Volatile
    var householdRootDenied: RootPath? = null

    /** The two reads behind [SyncTransport.householdRoot]: the root document and the `householdPolicy` document. */
    enum class RootPath { ROOT, POLICY }

    // ---- account recovery (ADR-0013) ----

    /** When the session of each device last signed in (`auth_time`), epoch ms; see [recordSignIn]. */
    private val signedInAtMs = mutableMapOf<DeviceId, Long>()

    /** `devices/{id}.recoveryRequestedAt`: the first `WAIT` call of each PENDING device, epoch ms. */
    private val recoveryRequestedAtMs = mutableMapOf<DeviceId, Long>()

    /** `recoveryPins/{uid}`: the check value (kept as the verifier here; the server keeps a MAC of it). */
    private val recoveryPins = mutableMapOf<UserId, RecoveryPinRow>()

    private val recoveryLog = mutableListOf<RecoveryRecord>()

    /** Devices this recovery admitted whose session revocation has not finished (`sessionsRevocationPending`). */
    private val revocationPending = mutableMapOf<DeviceId, RecoveredParentDevice>()

    /**
     * True makes the next recovery's session revocation fail (Auth unreachable): the device is ACTIVE, the call answers
     * `internal`, and calling again finishes the revocation and answers as the first call would have.
     */
    @Volatile
    var failNextSessionRevocation: Boolean = false

    /** A stored check value as the tests may inspect it. */
    data class RecoveryPinRow(
        val salt: ByteArray,
        val kdf: String,
        val verifier: ByteArray,
        val failedAttempts: Int,
        val setByDeviceId: DeviceId,
    ) {
        override fun toString(): String = "RecoveryPinRow(kdf=$kdf, failedAttempts=$failedAttempts, <redacted>)"
    }

    /** One `recoveries/{autoId}` record. */
    data class RecoveryRecord(val deviceId: DeviceId, val method: RecoveryMethod, val revokedDevices: Int, val at: Long)

    /** The session of [deviceId] signed in again now (Credential Manager), as `auth_time`; a revoked one comes back. */
    @Synchronized
    fun recordSignIn(deviceId: DeviceId) {
        signedInAtMs[deviceId] = nowEpochMs()
        signedOut -= deviceId
    }

    /** The check value stored for [uid], or null. */
    @Synchronized
    fun recoveryPinOf(uid: UserId): RecoveryPinRow? = recoveryPins[uid]

    /** Every recovery recorded, in order. */
    @Synchronized
    fun recoveries(): List<RecoveryRecord> = recoveryLog.toList()

    /** The registry row of [deviceId], or null. */
    @Synchronized
    fun device(deviceId: DeviceId): RegisteredDevice? = devices[deviceId]?.value

    /** Every parent key row, as the server holds it. */
    @Synchronized
    fun parentKeys(): List<RegisteredParentKey> = parentKeys.values.map { it.value }

    /** True while [deviceId]'s session is refused (signed out or revoked). */
    @Synchronized
    fun isSignedOut(deviceId: DeviceId): Boolean = deviceId in signedOut

    /**
     * Mirrors `setRecoveryPin` (ADR-0013 §1) minus the proof's signature and the rate limit: the caller must be an
     * ACTIVE PARENT device of its uid (`permission-denied`); [salt] 16 bytes, [verifier] 32 bytes and a [kdf] in the
     * server's shape (`invalid-argument`). With [newPin] the uid's row is replaced and its failed attempts start at 0.
     * Without it (an unchanged PIN registered again) the count is kept, and a row another device set that is still
     * ACTIVE is kept as it is: the answer then says `stored: false` (one value per account: the PIN set or changed
     * last on any of its phones). Answers the attempts left of the row the server holds afterwards.
     */
    @Synchronized
    internal fun setRecoveryPin(
        caller: Caller,
        salt: ByteArray,
        kdf: String,
        verifier: ByteArray,
        newPin: Boolean,
    ): Result<StoredRecoveryPin> {
        recoveryReachable(caller)?.let { return Result.failure(it) }
        val device = devices[caller.deviceId]?.value
        val refusal = when {
            device == null || device.uid != caller.uid || device.role != Role.PARENT ||
                device.status != DeviceStatus.Active -> PERMISSION_DENIED

            salt.size != RECOVERY_SALT_BYTES || verifier.size != RECOVERY_VERIFIER_BYTES ||
                !RECOVERY_KDF_SHAPES.any { it.matches(kdf) } -> INVALID_ARGUMENT

            else -> null
        }
        if (refusal != null) return Result.failure(SyncException(SyncError.Permanent(refusal)))
        val existing = recoveryPins[caller.uid]
        val keptOther = !newPin && existing != null && existing.setByDeviceId != caller.deviceId &&
            devices[existing.setByDeviceId]?.value?.status == DeviceStatus.Active
        if (keptOther) {
            return Result.success(StoredRecoveryPin(stored = false, RECOVERY_MAX_ATTEMPTS - existing.failedAttempts))
        }
        val failed = if (newPin) 0 else existing?.failedAttempts ?: 0
        recoveryPins[caller.uid] = RecoveryPinRow(salt.copyOf(), kdf, verifier.copyOf(), failed, caller.deviceId)
        return Result.success(StoredRecoveryPin(stored = true, RECOVERY_MAX_ATTEMPTS - failed))
    }

    /**
     * Mirrors `recoveryStatus` (ADR-0013 §2): the caller's own PENDING PARENT device (`not-pending` with its status
     * otherwise; `permission-denied` for a row that is missing or another account's). `pin` is null without a row or
     * once ten attempts failed; `waitEndsAt` is null until the first `WAIT` call, then that time plus
     * [RECOVERY_WAIT_MS]. Reading it never starts the wait.
     */
    @Synchronized
    internal fun recoveryStatus(caller: Caller): Result<RecoveryStatus> {
        recoveryReachable(caller)?.let { return Result.failure(it) }
        pendingProof(caller)?.let { return Result.failure(it) }
        val row = recoveryPins[caller.uid]?.takeIf { it.failedAttempts < RECOVERY_MAX_ATTEMPTS }
        return Result.success(
            RecoveryStatus(
                pin = row?.let { RecoveryPinRecord(it.salt, it.kdf) },
                attemptsLeft = row?.let { RECOVERY_MAX_ATTEMPTS - it.failedAttempts } ?: 0,
                waitEndsAt = recoveryRequestedAtMs[caller.deviceId]
                    ?.let { Instant.fromEpochMilliseconds(it + RECOVERY_WAIT_MS) },
            ),
        )
    }

    /**
     * Mirrors `recoverParentDevice` (ADR-0013 §2) minus the signature and the rate limits: the caller's own PENDING
     * PARENT device (an ACTIVE one whose session revocation is still pending finishes it and answers as before); a
     * sign-in within [RECENT_SIGN_IN_MS] ([recordSignIn]); no ACTIVE parent device of another account
     * (`other-parent`). `PIN`: no row or ten failures is `pin-unavailable`; a wrong verifier counts a failure and
     * answers `wrong-pin` with the attempts left; a match keeps the count. `WAIT`: the first call starts the wait and
     * answers `wait`, as does every call before its end; after it, it deletes the row. Then the device becomes ACTIVE,
     * every other non-revoked PARENT device of the uid is revoked at one above its highest stored lamport with its
     * parent keys stamped, every session of the account is refused (this phone's too: `signInAgain`), and a recovery
     * is recorded.
     */
    @Synchronized
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount") // The callable's steps, in the server's order.
    internal fun recoverParentDevice(caller: Caller, request: RecoveryRequest): Result<RecoveredParentDevice> {
        recoveryReachable(caller)?.let { return Result.failure(it) }
        revocationPending[caller.deviceId]?.let { first ->
            if (devices[caller.deviceId]?.value?.uid == caller.uid) {
                revocationPending.remove(caller.deviceId)
                revokeSessions(caller.uid)
                return Result.success(first)
            }
        }
        pendingProof(caller)?.let { return Result.failure(it) }
        val now = nowEpochMs()
        if (now - (signedInAtMs[caller.deviceId] ?: 0) > RECENT_SIGN_IN_MS) {
            return Result.failure(RecoveryRefusedException(RecoveryRefusal.RecentSignIn))
        }
        val otherAccountActive = devices.values.any {
            it.value.role == Role.PARENT && it.value.uid != caller.uid && it.value.status == DeviceStatus.Active
        }
        if (otherAccountActive) return Result.failure(RecoveryRefusedException(RecoveryRefusal.OtherParent))
        when (request) {
            is RecoveryRequest.Pin -> {
                val row = recoveryPins[caller.uid]?.takeIf { it.failedAttempts < RECOVERY_MAX_ATTEMPTS }
                    ?: return Result.failure(RecoveryRefusedException(RecoveryRefusal.PinUnavailable))
                val candidate = request.verifier()
                val matches = java.security.MessageDigest.isEqual(candidate, row.verifier)
                candidate.fill(0)
                if (!matches) {
                    val failed = row.failedAttempts + 1
                    recoveryPins[caller.uid] = row.copy(failedAttempts = failed)
                    val left = RECOVERY_MAX_ATTEMPTS - failed
                    return Result.failure(RecoveryRefusedException(RecoveryRefusal.WrongPin(left)))
                }
            }

            RecoveryRequest.Wait -> {
                val endsAt = recoveryRequestedAtMs.getOrPut(caller.deviceId) { now } + RECOVERY_WAIT_MS
                if (now < endsAt) {
                    return Result.failure(
                        RecoveryRefusedException(RecoveryRefusal.Wait(Instant.fromEpochMilliseconds(endsAt))),
                    )
                }
                recoveryPins.remove(caller.uid)
            }
        }
        val others = devices.values.map { it.value }.filter {
            it.deviceId != caller.deviceId && it.uid == caller.uid && it.role == Role.PARENT &&
                it.status !is DeviceStatus.Revoked
        }
        others.forEach { other ->
            val atLamport = maxLamportOf(other.deviceId) + 1
            setStatus(other.deviceId, DeviceStatus.Revoked(atLamport))
            parentKeys.values.map { it.value }
                .filter { it.deviceId == other.deviceId && it.revokedAtLamport == null }
                .forEach { key ->
                    addParentKey(
                        RegisteredParentKey(
                            key.fingerprint,
                            key.publicKey,
                            key.uid,
                            key.deviceId,
                            key.validFromLamport,
                            atLamport,
                        ),
                    )
                }
        }
        setStatus(caller.deviceId, DeviceStatus.Active)
        recoveryLog += RecoveryRecord(caller.deviceId, request.method, others.size, now)
        val left = recoveryPins[caller.uid]?.let { RECOVERY_MAX_ATTEMPTS - it.failedAttempts } ?: 0
        val answer = RecoveredParentDevice(caller.deviceId, others.size, request.method, left, signInAgain = true)
        if (failNextSessionRevocation) {
            failNextSessionRevocation = false
            revocationPending[caller.deviceId] = answer
            return Result.failure(SyncException(SyncError.Permanent(INTERNAL)))
        }
        revokeSessions(caller.uid)
        return Result.success(answer)
    }

    /** `revokeRefreshTokens` of the account: every device session of [uid] is refused until it signs in again. */
    private fun revokeSessions(uid: UserId) {
        devices.values.filter { it.value.uid == uid }.forEach { signedOut += it.value.deviceId }
    }

    /** The household or the network, as for every call; null when the call may go on. */
    private fun recoveryReachable(caller: Caller): SyncException? = try {
        checkReachable(caller.deviceId)
        null
    } catch (failure: SyncException) {
        failure
    }

    /**
     * The PENDING device proof: the row must exist and be the caller's PARENT device (`permission-denied`), and be
     * PENDING (`not-pending` with its status).
     */
    private fun pendingProof(caller: Caller): Exception? {
        val device = devices[caller.deviceId]?.value
        return when {
            device == null || device.uid != caller.uid || device.role != Role.PARENT ->
                SyncException(SyncError.Permanent(PERMISSION_DENIED))

            device.status == DeviceStatus.Active ->
                RecoveryRefusedException(RecoveryRefusal.NotPending(RecoveryDeviceStatus.ACTIVE))

            device.status is DeviceStatus.Revoked ->
                RecoveryRefusedException(RecoveryRefusal.NotPending(RecoveryDeviceStatus.REVOKED))

            else -> null
        }
    }

    /** The highest lamport of anything [deviceId] stored (events, documents, messages): the server's `maxLamport`. */
    private fun maxLamportOf(deviceId: DeviceId): Long {
        val fromEvents = events.values.mapNotNull { stored ->
            val envelope = stored.value.json["envelope"] as? JsonObject
            envelope?.takeIf { it.string(AUTHOR_DEVICE_ID) == deviceId.value }?.long(LAMPORT)
        }
        val fromDocuments = documents.values.map { it.value }
            .filter { it.authorDeviceId == deviceId }
            .map { it.lamport }
        val fromMessages = messages.values.mapNotNull { stored ->
            stored.value.takeIf { it.string(AUTHOR_DEVICE_ID) == deviceId.value }?.long(LAMPORT)
        }
        return (fromEvents + fromDocuments + fromMessages).maxOrNull() ?: 0
    }

    // ---- what transports call ----

    /**
     * One registry row, as `households/{hid}/devices/{deviceId}` reads for a signed-in member: null for an unknown
     * device of the household; [PERMISSION_DENIED] for another household or a caller who is not a member (no
     * registered device of its uid), as the rules refuse the read; a partitioned caller fails transiently.
     */
    @Synchronized
    internal fun registeredDevice(caller: Caller, hid: HouseholdId, deviceId: DeviceId): RegisteredDevice? {
        checkReachable(caller.deviceId)
        if (hid != householdId || devices.values.none { it.value.uid == caller.uid }) {
            throw SyncException(SyncError.Permanent(PERMISSION_DENIED))
        }
        return devices[deviceId]?.value
    }

    /**
     * The server's household root as [SyncTransport.householdRoot] reads it: the root document first (missing:
     * [householdRootStored]), then the policy document; either read may be denied ([householdRootDenied]).
     */
    @Synchronized
    internal fun householdRoot(caller: Caller): HouseholdRoot {
        checkReachable(caller.deviceId)
        if (householdRootDenied == RootPath.ROOT) throw SyncException(SyncError.Permanent(PERMISSION_DENIED))
        if (!householdRootStored) throw SyncException(SyncError.Transient())
        if (householdRootDenied == RootPath.POLICY) throw SyncException(SyncError.Permanent(PERMISSION_DENIED))
        return HouseholdRoot(fiatCurrency, document(DocTypes.HOUSEHOLD_POLICY, householdId.value) != null)
    }

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
        fun <T> stored(collection: String, source: Map<*, Stored<T>>, readable: (T) -> Boolean): List<Stored<T>> {
            val after = cursor.perCollection[collection]?.toLongOrNull() ?: 0
            val newer = source.values.filter { it.seq > after && readable(it.value) }.sortedBy { it.seq }
            if (newer.size > pageSize) hasMore = true
            val taken = newer.take(pageSize)
            taken.lastOrNull()?.let { next[collection] = it.seq.toString() }
            return taken
        }
        fun <T> page(collection: String, source: Map<*, Stored<T>>): List<T> =
            stored(collection, source) { true }.map { it.value }
        val pulledMessages = stored(MESSAGES, messages) { mayReadThread(caller, it.string(THREAD_ID)) }
        return PullPage(
            devices = page(DEVICES, devices),
            parentKeys = page(PARENT_KEYS, parentKeys),
            documents = page(DOCUMENTS, documents),
            events = page(EVENTS, events),
            messages = pulledMessages.map { it.value },
            next = SyncCursor(next),
            hasMore = hasMore,
            chatPrunedBefore = chatPrunedBefore,
            // Each message's server stamp (when it was stored), as the Firebase transport reads `serverReceivedAt`.
            messageStamps = pulledMessages.mapNotNull { row ->
                row.value.string(MESSAGE_ID)?.let { it to Instant.fromEpochMilliseconds(row.at) }
            }.toMap(),
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
        val stored = events[fields.eventId]?.value?.json
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
        val stored = events[id]?.value?.json
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
            (document.docType != DocTypes.HOUSEHOLD_POLICY || householdPolicyAllowed(document.payload, stored)) &&
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
     * The `householdPolicy` payload rule (policy v2, M7 §1): the closed key set, and `chatRetentionDays` (1 to 3650)
     * and `dailyGameCapMinor` (0 to 10,000) absent, null or an integer within bounds. `requireParentSignature` is
     * one-way (D-REL-23): no write turns it off over a [stored] policy that has it on.
     */
    private fun householdPolicyAllowed(payload: JsonObject, stored: DocumentEnvelope?): Boolean {
        fun requiresSignature(of: JsonObject): Boolean =
            (of[REQUIRE_PARENT_SIGNATURE] as? JsonPrimitive)?.takeUnless { it.isString }?.content == "true"
        if (stored != null && requiresSignature(stored.payload) && !requiresSignature(payload)) return false
        fun bounded(key: String, range: LongRange): Boolean {
            val value = payload[key]
            return value == null || value is JsonNull ||
                ((value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.let { it in range } == true)
        }
        return POLICY_KEYS.containsAll(payload.keys) &&
            bounded(CHAT_RETENTION_DAYS, POLICY_RETENTION_DAYS) &&
            bounded(DAILY_GAME_CAP_MINOR, POLICY_GAME_CAP_MINOR)
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
        messages[id] = Stored(nextSeq(MESSAGES), message, nowEpochMs())
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
        blobs[id] = Stored(nextSeq(BLOBS), chunk, nowEpochMs())
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

    private fun storeEvent(
        id: String,
        event: JsonObject,
        author: DeviceId?,
        serverReceivedAt: Instant = Instant.fromEpochMilliseconds(nowEpochMs()),
    ) {
        if (id in events) return
        events[id] = Stored(nextSeq(EVENTS), PulledEvent(event, serverReceivedAt))
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

        /** 2026-11-02T08:00:00Z, `Fixtures.start` of core/domain-ledger's test fixtures. */
        const val DEFAULT_NOW_EPOCH_MS: Long = 1_793_606_400_000

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

        /** How far ahead of the server clock a prune's `before` may be (server `PRUNE_CLOCK_SKEW_MS`). */
        const val PRUNE_CLOCK_SKEW_MS: Long = 5 * 60 * 1000

        /** Chat blob chunks stored less than this before a prune's cut are kept (server `PRUNE_BLOB_GRACE_MS`). */
        const val PRUNE_BLOB_GRACE_MS: Long = 10 * 60 * 1000

        private const val CHAT_RETENTION_DAYS = "chatRetentionDays"
        private const val REQUIRE_PARENT_SIGNATURE = "requireParentSignature"
        private const val DAILY_GAME_CAP_MINOR = "dailyGameCapMinor"

        /** The closed key set of a `household_policy` payload (core/database `DocumentPayloads`, policy v2). */
        private val POLICY_KEYS =
            setOf("fiatCurrency", REQUIRE_PARENT_SIGNATURE, CHAT_RETENTION_DAYS, DAILY_GAME_CAP_MINOR)
        private val POLICY_RETENTION_DAYS = 1L..3650L
        private val POLICY_GAME_CAP_MINOR = 0L..10_000L

        const val MALFORMED: String = "malformed"
        const val WRONG_HOUSEHOLD: String = "wrong-household"
        const val UNKNOWN_DOC_TYPE: String = "unknown-doc-type"
        const val UNSIGNED: String = "unsigned"
        const val PERMISSION_DENIED: String = "permission-denied"
        const val INVALID_ARGUMENT: String = "invalid-argument"
        const val FAILED_PRECONDITION: String = "failed-precondition"
        const val INTERNAL: String = "internal"

        /** ADR-0013: ten wrong PINs close PIN recovery; the wait is 7 days; a recovery needs a 5-minute sign-in. */
        const val RECOVERY_MAX_ATTEMPTS: Int = 10
        const val RECOVERY_WAIT_MS: Long = 7 * DAY_MS
        const val RECENT_SIGN_IN_MS: Long = 5 * 60 * 1000
        private const val RECOVERY_SALT_BYTES = 16
        private const val RECOVERY_VERIFIER_BYTES = 32

        /** The server's shape check of a KDF descriptor (ADR-0013 §4). */
        private val RECOVERY_KDF_SHAPES = listOf(
            Regex("argon2id\\\$v=19\\\$m=\\d{1,7},t=\\d{1,2},p=\\d"),
            Regex("pbkdf2-sha256\\\$i=\\d{1,8}"),
        )

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

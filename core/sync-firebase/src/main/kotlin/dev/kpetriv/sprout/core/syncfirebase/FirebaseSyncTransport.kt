package dev.kpetriv.sprout.core.syncfirebase

import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.sync.ConflictResolver
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.sync.DocumentEnvelope
import dev.kpetriv.sprout.core.sync.PullPage
import dev.kpetriv.sprout.core.sync.PushItemId
import dev.kpetriv.sprout.core.sync.PushResult
import dev.kpetriv.sprout.core.sync.RemoteChange
import dev.kpetriv.sprout.core.sync.StoredDocument
import dev.kpetriv.sprout.core.sync.SyncCursor
import dev.kpetriv.sprout.core.sync.SyncError
import dev.kpetriv.sprout.core.sync.SyncException
import dev.kpetriv.sprout.core.sync.SyncTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SyncTransport] over Firestore (M3 §5), talking to the SDK only through [FirestoreFacade]. Shapes and paths are
 * [StoredShapes] and [FirestoreLayout]; `firestore/firestore.rules` enforces them.
 *
 * **Push** writes in batches of at most [batchSize] sets, each stamped with `serverReceivedAt`. A batch is atomic, so
 * when the rules refuse one item the batch is retried item by item to find it. A refused item is then classified
 * by reading the server copy: an event already stored under the same hash, or a document whose server copy beats or
 * equals this one ([ConflictResolver]), is accepted (pushes are idempotent); an event whose id is taken by a
 * different hash is stored as the conflicting copy `events/{eventId}__{hash}`; anything else is
 * `Permanent("permission-denied")`. Parent keys are never pushed: the server registers them (`registerParentKey`,
 * D-M3-34) and the rules refuse client writes, so there is no `parent_key` document type and one is
 * `Permanent("unknown-doc-type")` unsent.
 *
 * **Household gone** needs positive evidence ([membership]), because a bare `PERMISSION_DENIED` also follows a
 * revoked session (sign in again), a rules deploy or a clock skew: the account is gone ([AuthState.UserGone] from a
 * forced ID-token refresh), the `hid` claim is gone after that refresh, or this device's own `devices/{deviceId}` entry
 * reads `REVOKED`. Then [SyncError.HouseholdGone]. A refusal while `households/{hid}` is readable is the item's own
 * (permanent); an unreadable household without such evidence is [SyncError.Transient].
 *
 * **Pull** reads the registry in full (devices and parent keys are small and change in place, so their cursor is a
 * digest of the collection), then each document collection in [DocTypes.applyOrder], then events, each by
 * `(serverReceivedAt, document id)` after its cursor, [pageSize] per collection. Parent keys come back as registry
 * entries only.
 *
 * **Changes** come from snapshot listeners, open only while [foreground] is true.
 */
@Singleton
@Suppress("TooManyFunctions") // Push, pull, changes and the membership check.
class FirebaseSyncTransport
@Suppress("LongParameterList")
internal constructor(
    private val firestore: FirestoreFacade,
    private val household: HouseholdScope,
    private val foreground: Flow<Boolean>,
    private val authProbe: AuthProbe,
    private val pageSize: Int,
    private val batchSize: Int,
    private val callTimeoutMs: Long,
) : SyncTransport {

    @Inject
    constructor(
        firestore: FirestoreFacade,
        household: HouseholdScope,
        @AppForeground foreground: Flow<Boolean>,
        authProbe: AuthProbe,
    ) : this(firestore, household, foreground, authProbe, PAGE_SIZE, MAX_BATCH_WRITES, CALL_TIMEOUT_MS)

    init {
        require(pageSize > 0) { "pageSize must be positive" }
        require(batchSize in 1..MAX_BATCH_WRITES) { "batchSize must be 1..$MAX_BATCH_WRITES" }
    }

    override suspend fun isAvailable(): Boolean = household.currentHouseholdId() != null

    // ---- push ----

    private sealed class PendingWrite(val id: PushItemId, val path: String, val data: Map<String, Any?>) {
        class Event(id: PushItemId, path: String, data: Map<String, Any?>, val eventId: String, val hash: String) :
            PendingWrite(id, path, data)

        class Document(id: PushItemId, path: String, data: Map<String, Any?>, val envelope: DocumentEnvelope) :
            PendingWrite(id, path, data)

        fun write(): SetWrite = SetWrite(path, data)
    }

    /** What happened to one item retried on its own; [stop] ends the push (the rest stays unprocessed). */
    private sealed interface ItemOutcome {
        data object Accepted : ItemOutcome

        data class Rejected(val error: SyncError, val stop: Boolean = false) : ItemOutcome
    }

    override suspend fun push(events: List<JsonObject>, documents: List<DocumentEnvelope>): PushResult {
        val hid = requireHousehold()
        val accepted = mutableListOf<PushItemId>()
        val rejected = mutableListOf<Pair<PushItemId, SyncError>>()
        val pending = buildList {
            events.forEach { event -> eventWrite(hid, event).fold({ add(it) }, { rejected += it }) }
            documents.forEach { document -> documentWrite(hid, document).fold({ add(it) }, { rejected += it }) }
        }
        for (chunk in pending.chunked(batchSize)) {
            if (!pushChunk(hid, chunk, accepted, rejected)) break
        }
        return PushResult(accepted, rejected)
    }

    /** Commits one batch; false when the push must stop (the remaining batches stay unprocessed). */
    private suspend fun pushChunk(
        hid: HouseholdId,
        chunk: List<PendingWrite>,
        accepted: MutableList<PushItemId>,
        rejected: MutableList<Pair<PushItemId, SyncError>>,
    ): Boolean {
        val failure = commitCode(chunk.map(PendingWrite::write))
        return when {
            failure == null -> {
                accepted += chunk.map { it.id }
                true
            }

            failure in ITEM_LEVEL_CODES -> {
                val denied = failure == FirestoreCode.PERMISSION_DENIED
                when (if (denied) membership(hid) else Membership.READABLE) {
                    Membership.GONE -> throw SyncException(SyncError.HouseholdGone)

                    // Nothing says which item is at fault, or that any is: retry the whole push later.
                    Membership.UNCONFIRMED -> {
                        chunk.forEach { rejected += it.id to SyncError.Transient() }
                        false
                    }

                    Membership.READABLE -> settleOneByOne(hid, chunk, accepted, rejected)
                }
            }

            else -> {
                val error = unauthenticatedOrGone(failure)
                if (error == SyncError.HouseholdGone) throw SyncException(error)
                chunk.forEach { rejected += it.id to error }
                false
            }
        }
    }

    /** Retries [chunk] one item at a time; false when an item hit an error that ends the push. */
    private suspend fun settleOneByOne(
        hid: HouseholdId,
        chunk: List<PendingWrite>,
        accepted: MutableList<PushItemId>,
        rejected: MutableList<Pair<PushItemId, SyncError>>,
    ): Boolean {
        for (item in chunk) {
            when (val outcome = settle(hid, item)) {
                ItemOutcome.Accepted -> accepted += item.id

                is ItemOutcome.Rejected -> {
                    rejected += item.id to outcome.error
                    if (outcome.stop) return false
                }
            }
        }
        return true
    }

    private suspend fun settle(hid: HouseholdId, item: PendingWrite): ItemOutcome =
        when (val code = commitCode(listOf(item.write()))) {
            null -> ItemOutcome.Accepted

            FirestoreCode.PERMISSION_DENIED -> when (item) {
                is PendingWrite.Event -> resolveRefusedEvent(hid, item)
                is PendingWrite.Document -> resolveRefusedDocument(item)
            }

            // Not produced by sets today; an event that "already exists" is the idempotent case.
            FirestoreCode.ALREADY_EXISTS, FirestoreCode.FAILED_PRECONDITION ->
                if (item is PendingWrite.Event) ItemOutcome.Accepted else ItemOutcome.Rejected(permanentOf(code))

            FirestoreCode.INVALID_ARGUMENT -> ItemOutcome.Rejected(permanentOf(code))

            else -> ItemOutcome.Rejected(syncErrorOf(code), stop = true)
        }

    @Suppress("ReturnCount")
    private suspend fun resolveRefusedEvent(hid: HouseholdId, item: PendingWrite.Event): ItemOutcome {
        val stored = readOrOutcome(item.path) { return it } ?: return ItemOutcome.Rejected(PERMISSION_DENIED)
        if (StoredShapes.eventHashOf(stored.data) == item.hash) return ItemOutcome.Accepted
        // The id is taken by a different copy: keep this one as evidence under eventId__hash (M3 §5).
        val conflictPath = FirestoreLayout.conflictingEvent(hid, item.eventId, item.hash)
        return when (val code = commitCode(listOf(SetWrite(conflictPath, item.data)))) {
            null -> ItemOutcome.Accepted

            FirestoreCode.PERMISSION_DENIED -> {
                val copy = readOrOutcome(conflictPath) { return it }
                if (copy != null && StoredShapes.eventHashOf(copy.data) == item.hash) {
                    ItemOutcome.Accepted
                } else {
                    ItemOutcome.Rejected(PERMISSION_DENIED)
                }
            }

            FirestoreCode.INVALID_ARGUMENT -> ItemOutcome.Rejected(permanentOf(code))

            else -> ItemOutcome.Rejected(syncErrorOf(code), stop = true)
        }
    }

    @Suppress("ReturnCount")
    private suspend fun resolveRefusedDocument(item: PendingWrite.Document): ItemOutcome {
        val stored = readOrOutcome(item.path) { return it } ?: return ItemOutcome.Rejected(PERMISSION_DENIED)
        val server = StoredShapes.documentOf(stored.data)
        val serverWins = server != null &&
            ConflictResolver.resolve(StoredDocument.of(server), item.envelope) == ConflictResolver.Decision.Ignore
        return if (serverWins) ItemOutcome.Accepted else ItemOutcome.Rejected(PERMISSION_DENIED)
    }

    /** Reads [path]; a failure becomes the item's outcome through [onFailure] (which returns from the caller). */
    private suspend inline fun readOrOutcome(path: String, onFailure: (ItemOutcome) -> Nothing): FacadeDocument? = try {
        call { get(path) }
    } catch (failure: FirestoreFailure) {
        onFailure(
            if (failure.code == FirestoreCode.PERMISSION_DENIED) {
                ItemOutcome.Rejected(PERMISSION_DENIED)
            } else {
                ItemOutcome.Rejected(syncErrorOf(failure.code), stop = true)
            },
        )
    }

    @Suppress("ReturnCount")
    private fun eventWrite(hid: HouseholdId, event: JsonObject): Either {
        val id = PushItemId.ofEvent(event)
        val flat = StoredShapes.flattenEvent(event) ?: return Either.Rejected(id to SyncError.Permanent(MALFORMED))
        val householdId = (flat.fields["householdId"] as? JsonPrimitive)?.content
        if (householdId != hid.value) return Either.Rejected(id to SyncError.Permanent(WRONG_HOUSEHOLD))
        val data = PlainValues.toPlainMap(flat.fields) + (FirestoreFacade.SERVER_RECEIVED_AT to ServerTimestamp)
        return Either.Write(
            PendingWrite.Event(id, FirestoreLayout.event(hid, flat.eventId), data, flat.eventId, flat.hash),
        )
    }

    @Suppress("ReturnCount")
    private fun documentWrite(hid: HouseholdId, envelope: DocumentEnvelope): Either {
        val id = PushItemId.ofDocument(envelope)
        val collection = FirestoreLayout.collectionOf(envelope.docType)
        val fields = when {
            collection == null -> return Either.Rejected(id to SyncError.Permanent(UNKNOWN_DOC_TYPE))
            envelope.householdId != hid -> return Either.Rejected(id to SyncError.Permanent(WRONG_HOUSEHOLD))
            envelope.signature == null -> return Either.Rejected(id to SyncError.Permanent(UNSIGNED))
            else -> StoredShapes.documentFields(envelope)
        }
        val data = fields + (FirestoreFacade.SERVER_RECEIVED_AT to ServerTimestamp)
        return Either.Write(
            PendingWrite.Document(id, FirestoreLayout.document(hid, collection, envelope.docId), data, envelope),
        )
    }

    private sealed interface Either {
        class Write(val write: PendingWrite) : Either

        class Rejected(val rejection: Pair<PushItemId, SyncError>) : Either

        fun fold(onWrite: (PendingWrite) -> Unit, onRejected: (Pair<PushItemId, SyncError>) -> Unit) = when (this) {
            is Write -> onWrite(write)
            is Rejected -> onRejected(rejection)
        }
    }

    // ---- pull ----

    // SyncException carries the code only, never the SDK failure (codes only, no free text).
    @Suppress("SwallowedException")
    override suspend fun pull(cursor: SyncCursor): PullPage {
        val hid = requireHousehold()
        return try {
            pullPage(hid, cursor)
        } catch (failure: FirestoreFailure) {
            throw SyncException(readErrorOf(hid, failure.code))
        }
    }

    private suspend fun pullPage(hid: HouseholdId, cursor: SyncCursor): PullPage {
        val next = cursor.perCollection.toMutableMap()
        val documents = mutableListOf<DocumentEnvelope>()

        val deviceDocs = call { getAll(FirestoreLayout.collection(hid, FirestoreLayout.DEVICES)) }
        val devicesDigest = StoredShapes.digestOf(deviceDocs)
        val devices = if (devicesDigest == cursor.perCollection[FirestoreLayout.DEVICES]) {
            emptyList()
        } else {
            deviceDocs.mapNotNull(StoredShapes::deviceOf)
        }
        next[FirestoreLayout.DEVICES] = devicesDigest

        val keyDocs = call { getAll(FirestoreLayout.collection(hid, FirestoreLayout.PARENT_KEYS)) }
        val keysDigest = StoredShapes.digestOf(keyDocs)
        val parentKeys = if (keysDigest == cursor.perCollection[FirestoreLayout.PARENT_KEYS]) {
            emptyList()
        } else {
            keyDocs.mapNotNull(StoredShapes::parentKeyOf)
        }
        next[FirestoreLayout.PARENT_KEYS] = keysDigest

        var hasMore = false
        for ((docType, collection) in FirestoreLayout.documentCollections) {
            val page = page(hid, collection, cursor, next)
            hasMore = hasMore || page.size >= pageSize
            documents += page.mapNotNull { doc ->
                StoredShapes.documentOf(doc.data)?.takeIf { it.docType == docType && it.docId == doc.id }
            }
        }
        val eventPage = page(hid, FirestoreLayout.EVENTS, cursor, next)
        hasMore = hasMore || eventPage.size >= pageSize
        val events = eventPage.mapNotNull { StoredShapes.nestEvent(it.data) }

        return PullPage(devices, parentKeys, documents, events, SyncCursor(next), hasMore)
    }

    private suspend fun page(
        hid: HouseholdId,
        collection: String,
        cursor: SyncCursor,
        next: MutableMap<String, String>,
    ): List<FacadeDocument> {
        val after = cursor.perCollection[collection]?.let(CursorCodec::decode)
        val page = call { queryByServerTime(FirestoreLayout.collection(hid, collection), after, pageSize) }
        page.lastOrNull()?.let { last ->
            last.serverReceivedAt?.let { next[collection] = CursorCodec.encode(QueryPosition(it, last.id)) }
        }
        return page
    }

    // ---- changes ----

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun changes(): Flow<RemoteChange> = foreground.distinctUntilChanged().flatMapLatest { inForeground ->
        if (!inForeground) {
            emptyFlow()
        } else {
            flow {
                val hid = household.currentHouseholdId() ?: return@flow
                emitAll(listeners(hid))
            }
        }
    }

    private fun listeners(hid: HouseholdId): Flow<RemoteChange> = watched
        .map { (collection, latestOnly) ->
            firestore.listen(FirestoreLayout.collection(hid, collection), latestOnly).map { RemoteChange(collection) }
        }
        .merge()
        .catch { failure ->
            throw if (failure is FirestoreFailure) SyncException(syncErrorOf(failure.code)) else failure
        }

    // ---- shared ----

    private suspend fun requireHousehold(): HouseholdId =
        household.currentHouseholdId() ?: throw SyncException(SyncError.Unauthenticated)

    /** Runs one facade call within [callTimeoutMs]; a call that does not finish fails with [FirestoreCode.TIMEOUT]. */
    private suspend fun <T> call(block: suspend FirestoreFacade.() -> T): T {
        val result = withTimeoutOrNull(callTimeoutMs) { Box(firestore.block()) }
            ?: throw FirestoreFailure(FirestoreCode.TIMEOUT)
        return result.value
    }

    private class Box<T>(val value: T)

    private suspend fun commitCode(writes: List<SetWrite>): FirestoreCode? = try {
        call { commit(writes) }
        null
    } catch (failure: FirestoreFailure) {
        failure.code
    }

    /** What a refusal says about this member's place in household [HouseholdId] ([membership]). */
    private enum class Membership {
        /** Positive evidence that the household or this member is gone: wipe. */
        GONE,

        /** `households/{hid}` is readable: the member is fine and the refusal is about the item. */
        READABLE,

        /** Unreadable, but nothing proves the member is gone (a revoked session, a rules change, offline). */
        UNCONFIRMED,
    }

    /**
     * Decides what a `PERMISSION_DENIED` means, from positive evidence only:
     * 1. a forced ID-token refresh ([AuthProbe]) says the account was deleted or disabled ([AuthState.UserGone]), or
     *    the refreshed claims no longer name [hid] (the `hid` claim was removed);
     * 2. this device's own `devices/{deviceId}` entry ([HouseholdScope.currentDeviceId]) reads `REVOKED`;
     * then [Membership.GONE]. Otherwise [Membership.READABLE] when `households/{hid}` can be read, else
     * [Membership.UNCONFIRMED]. A check that cannot be made (offline, refresh failed) is not evidence.
     */
    private suspend fun membership(hid: HouseholdId): Membership {
        val auth = authProbe.refreshedState()
        val gone = when (auth) {
            AuthState.UserGone -> true
            is AuthState.SignedIn -> auth.claims.hid != hid
            AuthState.SignedOut, null -> false
        }
        return when {
            gone || ownDeviceRevoked(hid) -> Membership.GONE
            householdReadable(hid) -> Membership.READABLE
            else -> Membership.UNCONFIRMED
        }
    }

    private suspend fun ownDeviceRevoked(hid: HouseholdId): Boolean {
        val deviceId = household.currentDeviceId() ?: return false
        return try {
            call { get(FirestoreLayout.document(hid, FirestoreLayout.DEVICES, deviceId.value)) }
                ?.data?.get("status") == REVOKED
        } catch (expected: FirestoreFailure) {
            false
        }
    }

    private suspend fun householdReadable(hid: HouseholdId): Boolean = try {
        call { get(FirestoreLayout.household(hid)) } != null
    } catch (expected: FirestoreFailure) {
        false
    }

    private suspend fun readErrorOf(hid: HouseholdId, code: FirestoreCode): SyncError = when (code) {
        FirestoreCode.PERMISSION_DENIED, FirestoreCode.NOT_FOUND -> when (membership(hid)) {
            Membership.GONE -> SyncError.HouseholdGone
            Membership.READABLE -> syncErrorOf(code)
            Membership.UNCONFIRMED -> SyncError.Transient()
        }

        else -> unauthenticatedOrGone(code)
    }

    /**
     * [syncErrorOf], except that `UNAUTHENTICATED` from an account that a forced refresh finds deleted or disabled
     * ([AuthState.UserGone]) is [SyncError.HouseholdGone]: a deleted household deletes its members' accounts, so
     * their tokens stop refreshing before any rule is evaluated.
     */
    private suspend fun unauthenticatedOrGone(code: FirestoreCode): SyncError =
        if (code == FirestoreCode.UNAUTHENTICATED && authProbe.refreshedState() == AuthState.UserGone) {
            SyncError.HouseholdGone
        } else {
            syncErrorOf(code)
        }

    companion object {
        /** Firestore allows 500 writes per batch; 400 leaves room (M3 §5). */
        const val MAX_BATCH_WRITES: Int = 400

        /** Documents per collection per pull page. */
        const val PAGE_SIZE: Int = 500

        /** How long one Firestore call may take before it counts as a transient failure. */
        const val CALL_TIMEOUT_MS: Long = 30_000

        const val MALFORMED: String = "malformed"
        const val WRONG_HOUSEHOLD: String = "wrong-household"
        const val UNKNOWN_DOC_TYPE: String = "unknown-doc-type"
        const val UNSIGNED: String = "unsigned"

        private const val REVOKED = "REVOKED"

        private val PERMISSION_DENIED = SyncError.Permanent("permission-denied")

        /** Batch failures that may be caused by a single item, so the batch is retried item by item. */
        private val ITEM_LEVEL_CODES = setOf(
            FirestoreCode.PERMISSION_DENIED,
            FirestoreCode.INVALID_ARGUMENT,
            FirestoreCode.ALREADY_EXISTS,
            FirestoreCode.FAILED_PRECONDITION,
        )

        /** Collections watched by [changes]; the registry is small and changes in place, so it is watched in full. */
        private val watched: List<Pair<String, Boolean>> =
            listOf(FirestoreLayout.DEVICES to false, FirestoreLayout.PARENT_KEYS to false) +
                FirestoreLayout.documentCollections.map { (_, collection) -> collection to true } +
                (FirestoreLayout.EVENTS to true)

        private const val RESOURCE_EXHAUSTED_RETRY_MS = 60_000L

        private fun permanentOf(code: FirestoreCode) = SyncError.Permanent(code.name.lowercase().replace('_', '-'))

        /** The [SyncError] of a Firestore status that is not about the household being gone. */
        internal fun syncErrorOf(code: FirestoreCode): SyncError = when (code) {
            FirestoreCode.UNAUTHENTICATED -> SyncError.Unauthenticated
            FirestoreCode.PERMISSION_DENIED, FirestoreCode.INVALID_ARGUMENT -> permanentOf(code)
            FirestoreCode.RESOURCE_EXHAUSTED -> SyncError.Transient(RESOURCE_EXHAUSTED_RETRY_MS)
            else -> SyncError.Transient()
        }
    }
}

/** Pull cursor of one collection: `<ISO-8601 serverReceivedAt>|<document id>`. */
internal object CursorCodec {
    private const val SEPARATOR = '|'

    fun encode(position: QueryPosition): String =
        "${position.serverReceivedAt.toIsoString()}$SEPARATOR${position.documentId}"

    /** The position in [cursor], or null (start over) when it is not one this transport wrote. */
    @Suppress("ReturnCount")
    fun decode(cursor: String): QueryPosition? {
        val split = cursor.indexOf(SEPARATOR)
        if (split <= 0 || split == cursor.length - 1) return null
        val time = ServerTime.parseOrNull(cursor.substring(0, split)) ?: return null
        return QueryPosition(time, cursor.substring(split + 1))
    }
}

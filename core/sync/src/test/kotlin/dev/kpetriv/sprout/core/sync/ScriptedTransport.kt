package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.RegisteredParentKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonObject

/** A transport whose pull pages and push outcomes a test scripts; logs `pull` and `push` into [log]. */
internal class ScriptedTransport(private val log: MutableList<String> = mutableListOf()) : SyncTransport {
    val pages = ArrayDeque<PullPage>()
    val pulledCursors = mutableListOf<SyncCursor>()
    val pushedBatches = mutableListOf<List<PushItemId>>()
    var pullError: SyncError? = null
    var pushError: SyncError? = null
    var beforePull: suspend () -> Unit = {}

    /** Outcome per item; items not listed are accepted. */
    val outcomes = mutableMapOf<PushItemId, SyncError?>()
    val remoteChanges = MutableSharedFlow<RemoteChange>(extraBufferCapacity = 16)

    override suspend fun pull(cursor: SyncCursor): PullPage {
        beforePull()
        log += "pull"
        pulledCursors += cursor
        pullError?.let { throw SyncException(it) }
        return pages.removeFirstOrNull() ?: emptyPage(cursor)
    }

    override suspend fun push(events: List<JsonObject>, documents: List<DocumentEnvelope>): PushResult {
        log += "push"
        val ids = events.map(PushItemId::ofEvent) + documents.map(PushItemId::ofDocument)
        pushedBatches += ids
        pushError?.let { throw SyncException(it) }
        val accepted = mutableListOf<PushItemId>()
        val rejected = mutableListOf<Pair<PushItemId, SyncError>>()
        for (id in ids) {
            if (id !in outcomes) {
                accepted += id
            } else {
                outcomes[id]?.let { rejected += id to it }
            }
        }
        return PushResult(accepted, rejected)
    }

    override fun changes(): Flow<RemoteChange> = remoteChanges

    override suspend fun isAvailable(): Boolean = pullError == null

    companion object {
        fun emptyPage(cursor: SyncCursor) = PullPage(emptyList(), emptyList(), emptyList(), emptyList(), cursor, false)
    }
}

/** Wraps an applier and logs each call into [log]. */
internal class LoggingApplier(private val inner: RemoteApplier, private val log: MutableList<String>) :
    RemoteApplier by inner {
    val savedCursors = mutableListOf<SyncCursor>()

    override suspend fun applyRegistry(devices: List<RegisteredDevice>, parentKeys: List<RegisteredParentKey>) {
        log += "registry"
        inner.applyRegistry(devices, parentKeys)
    }

    override suspend fun applyDocuments(documents: List<DocumentEnvelope>): DocumentApplyReport {
        log += "documents"
        return inner.applyDocuments(documents)
    }

    override suspend fun applyEvents(events: List<JsonObject>): EventApplyReport {
        log += "events"
        return inner.applyEvents(events)
    }

    override suspend fun retryHeld(): EventApplyReport {
        log += "retryHeld"
        return inner.retryHeld()
    }

    override suspend fun saveCursor(cursor: SyncCursor) {
        log += "saveCursor"
        savedCursors += cursor
        inner.saveCursor(cursor)
    }
}

package dev.kpetriv.sprout.core.syncfirebase

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * An in-memory [FirestoreFacade]. Every committed batch gets one server time (as in Firestore, where all writes of a
 * batch share `request.time`), so tests see equal `serverReceivedAt` values across a page boundary. A [rule] can
 * refuse single writes (refusing the whole batch, like Firestore), and failures can be scripted per call.
 */
class FakeFirestore : FirestoreFacade {
    val docs = linkedMapOf<String, Map<String, Any?>>()
    val commits = mutableListOf<List<SetWrite>>()
    val listeners = mutableMapOf<Pair<String, Boolean>, MutableSharedFlow<Unit>>()

    /** The filter each listener was opened with, by the same key as [listeners]. */
    val listenFilters = mutableMapOf<Pair<String, Boolean>, FieldEquals?>()

    /** Every query's path and filter, in order. */
    val queries = mutableListOf<Pair<String, FieldEquals?>>()

    /** Returns the code that refuses a write, or null to allow it. Default: allow everything. */
    var rule: (write: SetWrite, existing: Map<String, Any?>?) -> FirestoreCode? = { _, _ -> null }

    /** Failures for the next commits, in order (null = no failure). */
    val commitFailures = ArrayDeque<FirestoreCode?>()

    /** When set, reads of this exact path fail with the code. */
    val readFailures = mutableMapOf<String, FirestoreCode>()

    /** When set, every query and getAll fails with this code. */
    var queryFailure: FirestoreCode? = null

    /** When true, commits never complete (an offline device whose writes stay queued). */
    var hangCommits = false

    private var clock = 1_700_000_000L

    override suspend fun commit(writes: List<SetWrite>) {
        if (hangCommits) awaitCancellation()
        commits += writes
        commitFailures.removeFirstOrNull()?.let { throw FirestoreFailure(it) }
        writes.firstNotNullOfOrNull { rule(it, docs[it.path]) }?.let { throw FirestoreFailure(it) }
        val now = ServerTime(clock++, 0)
        writes.forEach { write -> docs[write.path] = stamp(write.data, now) }
    }

    /** Stores [data] at [path] bypassing the rules (seeding), stamped with a fresh server time. */
    fun seed(path: String, data: Map<String, Any?>) {
        docs[path] = stamp(data, ServerTime(clock++, 0))
    }

    override suspend fun get(path: String): FacadeDocument? {
        readFailures[path]?.let { throw FirestoreFailure(it) }
        return docs[path]?.let { FacadeDocument(path.substringAfterLast('/'), it) }
    }

    override suspend fun getAll(path: String): List<FacadeDocument> {
        queryFailure?.let { throw FirestoreFailure(it) }
        return children(path)
    }

    override suspend fun queryByServerTime(
        path: String,
        after: QueryPosition?,
        limit: Int,
        where: FieldEquals?,
    ): List<FacadeDocument> {
        queries += path to where
        queryFailure?.let { throw FirestoreFailure(it) }
        return children(path)
            .filter { it.serverReceivedAt != null }
            .filter { where == null || it.data[where.field] == where.value }
            .sortedWith(compareBy<FacadeDocument>({ it.serverReceivedAt }, { it.id }))
            .filter { doc ->
                after == null ||
                    doc.serverReceivedAt!! > after.serverReceivedAt ||
                    (doc.serverReceivedAt == after.serverReceivedAt && doc.id > after.documentId)
            }
            .take(limit)
    }

    override fun listen(path: String, latestOnly: Boolean, where: FieldEquals?): Flow<Unit> {
        listenFilters[path to latestOnly] = where
        return listeners.getOrPut(path to latestOnly) { MutableSharedFlow(extraBufferCapacity = 16) }
    }

    private fun children(collection: String): List<FacadeDocument> = docs
        .filterKeys { it.startsWith("$collection/") && '/' !in it.removePrefix("$collection/") }
        .map { (path, data) -> FacadeDocument(path.substringAfterLast('/'), data) }

    private fun stamp(data: Map<String, Any?>, now: ServerTime): Map<String, Any?> =
        data.mapValues { (_, value) -> if (value == ServerTimestamp) now else value }
}

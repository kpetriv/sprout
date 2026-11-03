package dev.kpetriv.sprout.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drains the outbox to the backend and applies remote changes locally (ADR-0001, M3 §4).
 *
 * A [syncOnce] pulls first, then pushes, so a remote account or document a local write refers to is known before
 * anything is sent:
 * 1. pull pages until the transport says there is no more; per page: registry, then documents (in
 *    [DocTypes.applyOrder]), then events, then [RemoteApplier.saveCursor] with the page's cursor;
 * 2. [RemoteApplier.retryHeld] for events whose gap the pull may have filled;
 * 3. push the outbox in `seq` order in batches of at most [PUSH_BATCH_SIZE]: accepted items are acked; a
 *    [SyncError.Permanent] rejection is acked too (retrying cannot help) and listed in [SyncReport.rejections];
 *    a [SyncError.Transient] or [SyncError.Unauthenticated] failure records the attempt with a [backoff] time and
 *    ends the push, so later items wait behind it.
 *
 * A pull failure ends the sync before anything is pushed. [SyncError.HouseholdGone] from either side ends it with
 * [SyncReport.householdGone] set, and the caller wipes local data (M3 §6). Syncs never overlap: [syncOnce] holds a
 * [Mutex], so a WorkManager run and the foreground loop ([run]) are serialised.
 */
class SyncEngine(
    private val transport: SyncTransport,
    private val outbox: OutboxSource,
    private val applier: RemoteApplier,
    private val clock: SyncClock,
    private val backoff: Backoff = ExponentialBackoff(baseMs = 1_000, maxMs = 3_600_000, jitter = Jitter.equal()),
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val maxPagesPerSync: Int = DEFAULT_MAX_PAGES,
) {
    init {
        require(debounceMs >= 0) { "debounceMs must not be negative" }
        require(maxPagesPerSync > 0) { "maxPagesPerSync must be positive" }
    }

    private val mutex = Mutex()
    private val lastReport = MutableStateFlow<SyncReport?>(null)

    /** The report of the latest finished [syncOnce] (null before the first), for status UI and the wipe trigger. */
    val reports: StateFlow<SyncReport?> = lastReport.asStateFlow()

    /** One full pull-then-push pass. Transport failures end up in the report; they are not thrown. */
    suspend fun syncOnce(): SyncReport = mutex.withLock { sync().also { lastReport.value = it } }

    /**
     * Syncs whenever [transport] reports a remote change or [trigger] emits, debounced by [debounceMs]. A request
     * that arrives during a sync runs exactly one more sync after it, however many requests arrived. A failing
     * change listener only stops live updates; [trigger] keeps working. Cancel the returned job to stop.
     */
    @OptIn(FlowPreview::class)
    fun run(scope: CoroutineScope, trigger: Flow<Unit>): Job = scope.launch {
        val requests = Channel<Unit>(Channel.CONFLATED)
        launch {
            val remote = transport.changes().map { }.catch { /* listener lost: rely on triggers */ }
            merge(remote, trigger).debounce(debounceMs).collect { requests.trySend(Unit) }
        }
        requests.consumeEach { syncSafely() }
    }

    // A failing store must not end the loop: the report says the sync failed and the next trigger retries. The
    // exception is dropped on purpose; it may carry personal data and nothing here may log it.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun syncSafely() {
        try {
            syncOnce()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Exception) {
            lastReport.value = SyncReport(error = SyncError.Transient())
        }
    }

    private suspend fun sync(): SyncReport {
        val pulled = PullAccumulator()
        try {
            pullAll(pulled)
        } catch (failure: SyncException) {
            return pulled.report(failure.error)
        }
        val held = applier.retryHeld()
        pulled.events += held.copy(held = 0)
        val pushed = PushAccumulator()
        val error = pushAll(pushed)
        return pulled.report(error, pushed, held = held.held)
    }

    private suspend fun pullAll(pulled: PullAccumulator) {
        var cursor = applier.cursor()
        repeat(maxPagesPerSync) {
            val page = transport.pull(cursor)
            if (page.devices.isNotEmpty() || page.parentKeys.isNotEmpty()) {
                applier.applyRegistry(page.devices, page.parentKeys)
            }
            if (page.documents.isNotEmpty()) {
                pulled.documents += applier.applyDocuments(page.documents.sortedBy { DocTypes.applyOrder(it.docType) })
            }
            if (page.events.isNotEmpty()) pulled.events += applier.applyEvents(page.events)
            applier.saveCursor(page.next)
            pulled.add(page)
            cursor = page.next
            if (!page.hasMore) return
        }
    }

    /** Pushes due items until the outbox is drained; returns the error that stopped it, or null. */
    private suspend fun pushAll(pushed: PushAccumulator): SyncError? {
        var stop: SyncError? = null
        var more = true
        while (stop == null && more) {
            val batch = outbox.due(clock.nowEpochMs(), PUSH_BATCH_SIZE)
            if (batch.isNotEmpty()) stop = pushBatch(batch, pushed)
            more = batch.size == PUSH_BATCH_SIZE
        }
        return stop
    }

    private suspend fun pushBatch(batch: List<OutboxItem>, pushed: PushAccumulator): SyncError? {
        val result = try {
            transport.push(
                events = batch.mapNotNull { it.eventJson },
                documents = batch.mapNotNull { it.document },
            )
        } catch (failure: SyncException) {
            if (failure.error != SyncError.HouseholdGone) batch.forEach { fail(it, failure.error) }
            return failure.error
        }
        return settle(batch, result, pushed)
    }

    /** Acks or fails every item of [batch]; returns the error that ends the push, if any. */
    private suspend fun settle(batch: List<OutboxItem>, result: PushResult, pushed: PushAccumulator): SyncError? {
        val accepted = result.accepted.toSet()
        val rejected = result.rejected.toMap()
        var stop: SyncError? = null
        for (item in batch) {
            when (val error = rejected[item.pushId]) {
                null -> if (item.pushId in accepted) {
                    outbox.ack(item.seq)
                    pushed.accepted++
                } else {
                    // Not processed by the server: retry it like a transient failure.
                    val missing = SyncError.Transient()
                    fail(item, missing)
                    stop = stop ?: missing
                }

                is SyncError.Permanent -> {
                    outbox.ack(item.seq)
                    pushed.rejections += PushRejection(item.seq, item.pushId, error.code)
                }

                SyncError.HouseholdGone -> return error

                is SyncError.Transient, SyncError.Unauthenticated -> {
                    fail(item, error)
                    stop = stop ?: error
                }
            }
        }
        return stop
    }

    private suspend fun fail(item: OutboxItem, error: SyncError) {
        val retryAfter = (error as? SyncError.Transient)?.retryAfterMs
        outbox.fail(item.seq, error, clock.nowEpochMs() + backoff.delayMs(item.attempts, retryAfter))
    }

    private class PullAccumulator {
        var pages = 0
        var devices = 0
        var parentKeys = 0
        var documents = DocumentApplyReport()
        var events = EventApplyReport()

        fun add(page: PullPage) {
            pages++
            devices += page.devices.size
            parentKeys += page.parentKeys.size
        }

        fun report(error: SyncError?, pushed: PushAccumulator = PushAccumulator(), held: Int = 0) = SyncReport(
            pulled = PullCounts(pages, devices, parentKeys, documents, events),
            pushed = pushed.accepted,
            rejections = pushed.rejections.toList(),
            quarantined = events.quarantined + documents.rejectedTotal,
            held = held,
            householdGone = error == SyncError.HouseholdGone,
            error = error,
        )
    }

    private class PushAccumulator {
        var accepted = 0
        val rejections = mutableListOf<PushRejection>()
    }

    companion object {
        const val PUSH_BATCH_SIZE: Int = 100
        const val DEFAULT_DEBOUNCE_MS: Long = 500
        const val DEFAULT_MAX_PAGES: Int = 1_000
    }
}

/** What one [SyncEngine.syncOnce] did. */
data class SyncReport(
    val pulled: PullCounts = PullCounts(),
    /** Outbox items the server accepted. */
    val pushed: Int = 0,
    /** Outbox items the server refused for good; acked, to be audited as `SYNC_REJECTED` by the caller. */
    val rejections: List<PushRejection> = emptyList(),
    /** Pulled events quarantined plus pulled documents rejected. */
    val quarantined: Int = 0,
    /** Events still held for a missing predecessor after [RemoteApplier.retryHeld]. */
    val held: Int = 0,
    /** The household is gone: wipe local data (M3 §6). */
    val householdGone: Boolean = false,
    /** What ended the sync early, or null if it completed. */
    val error: SyncError? = null,
) {
    val rejected: Int get() = rejections.size
}

/** Totals over the pulled pages; [events] includes what [RemoteApplier.retryHeld] applied. */
data class PullCounts(
    val pages: Int = 0,
    val devices: Int = 0,
    val parentKeys: Int = 0,
    val documents: DocumentApplyReport = DocumentApplyReport(),
    val events: EventApplyReport = EventApplyReport(),
)

/** One outbox item the server refused for good: its `seq`, push id and the server's code (no free text). */
data class PushRejection(val seq: Long, val id: PushItemId, val code: String)

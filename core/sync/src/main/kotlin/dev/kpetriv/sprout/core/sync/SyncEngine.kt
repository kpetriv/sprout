package dev.kpetriv.sprout.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
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
 * 3. push the outbox in `seq` order in batches of at most [PUSH_BATCH_SIZE]. A batch never carries two items with
 *    the same [PushItemId] (it is cut before the second one, which goes in the next batch), so every per-item result
 *    maps back to exactly one outbox `seq`. Then, per item:
 *    - accepted: acked;
 *    - a **document** refused with [SyncError.Permanent]: acked too (retrying cannot help) and listed in
 *      [SyncReport.rejections];
 *    - a **ledger event** refused with [SyncError.Permanent]: **not** acked, because dropping it would break the
 *      device's hash chain for every receiver. It is marked failed with its next attempt [EVENT_REJECTION_RETRY_MS]
 *      later, listed in [SyncReport.rejections] with [PushRejection.blocking] set, and the push stops: everything
 *      queued behind it waits (head-of-line), so the parent sees one blocking item rather than a silent gap;
 *    - [SyncError.Transient]: the attempt is recorded with a [backoff] time and the push stops;
 *    - [SyncError.Unauthenticated]: the push stops **without** recording an attempt, so the backoff does not grow
 *      while the user is signed out and the outbox resumes as soon as the session is back.
 *
 * A pull failure ends the sync before anything is pushed. [SyncError.HouseholdGone] from either side ends it with
 * [SyncReport.householdGone] set, and the caller wipes local data (M3 §6). Syncs never overlap: [syncOnce] holds a
 * [Mutex], so a WorkManager run and the foreground loop ([run]) are serialised.
 *
 * Every report, whoever asked for the sync, is passed to [reportHandler] before the next sync can start, in the order
 * the syncs ran: no report is skipped or conflated (refused items must each get their `SYNC_REJECTED` audit row). The
 * handler runs under the sync mutex, so it must not call [syncOnce]; its failures are dropped (they may carry
 * personal data and must not end the sync).
 */
@Suppress("LongParameterList") // Collaborators plus tuning knobs with defaults.
class SyncEngine(
    private val transport: SyncTransport,
    private val outbox: OutboxSource,
    private val applier: RemoteApplier,
    private val clock: SyncClock,
    private val backoff: Backoff = ExponentialBackoff(baseMs = 1_000, maxMs = 3_600_000, jitter = Jitter.equal()),
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val maxPagesPerSync: Int = DEFAULT_MAX_PAGES,
    private val reportHandler: SyncReportHandler = SyncReportHandler { },
) {
    init {
        require(debounceMs >= 0) { "debounceMs must not be negative" }
        require(maxPagesPerSync > 0) { "maxPagesPerSync must be positive" }
    }

    private val mutex = Mutex()
    private val lastReport = MutableStateFlow<SyncReport?>(null)

    /**
     * The report of the latest finished [syncOnce] (null before the first), for status UI. Conflated: a consumer
     * that must see every report uses [reportHandler].
     */
    val reports: StateFlow<SyncReport?> = lastReport.asStateFlow()

    /** One full pull-then-push pass. Transport failures end up in the report; they are not thrown. */
    suspend fun syncOnce(): SyncReport = mutex.withLock { finish(sync()) }

    /**
     * Runs [block] while no sync runs: it waits for the sync in flight to finish, and no sync starts until [block]
     * returns (later [syncOnce] calls and the [run] loop wait for it). For steps that must not overlap a sync, such
     * as a sign-in that may replace the Firebase user. [block] must not call [syncOnce] (it would wait for itself).
     */
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    /** Publishes [report] and hands it to [reportHandler], still under the mutex. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // See the class comment.
    private suspend fun finish(report: SyncReport): SyncReport {
        lastReport.value = report
        try {
            reportHandler.handle(report)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Dropped on purpose: nothing here may log, and the sync itself finished.
        }
        return report
    }

    /**
     * Syncs whenever [transport] reports a remote change or [trigger] emits, debounced by [debounceMs]. A request
     * that arrives during a sync runs exactly one more sync after it, however many requests arrived. A failing
     * change listener is resubscribed after a [backoff] delay that grows with consecutive failures (reset by the
     * next change it delivers), so live updates come back after a network or listener error; [trigger] keeps working
     * meanwhile. Cancel the returned job to stop.
     */
    @OptIn(FlowPreview::class)
    fun run(scope: CoroutineScope, trigger: Flow<Unit>): Job = scope.launch {
        val requests = Channel<Unit>(Channel.CONFLATED)
        launch { merge(remoteChanges(), trigger).debounce(debounceMs).collect { requests.trySend(Unit) } }
        requests.consumeEach { syncSafely() }
    }

    /** [SyncTransport.changes] as sync requests, resubscribed with backoff whenever the listener fails. */
    private fun remoteChanges(): Flow<Unit> {
        var failures = 0
        return transport.changes()
            .onEach { failures = 0 }
            .map { }
            .retryWhen { cause, _ ->
                // The exception is dropped on purpose: it may carry personal data and nothing here may log it.
                if (cause is CancellationException) {
                    false
                } else {
                    delay(backoff.delayMs(failures++, null))
                    true
                }
            }
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
            mutex.withLock { finish(SyncReport(error = SyncError.Transient())) }
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
            val due = outbox.due(clock.nowEpochMs(), PUSH_BATCH_SIZE)
            val batch = untilRepeatedId(due)
            if (batch.isNotEmpty()) stop = pushBatch(batch, pushed)
            more = due.size == PUSH_BATCH_SIZE || batch.size < due.size
        }
        return stop
    }

    /**
     * The longest prefix of [due] without two items of the same [PushItemId], so each per-item result of a push
     * belongs to exactly one `seq`. The cut-off items are pushed in the next batch.
     */
    private fun untilRepeatedId(due: List<OutboxItem>): List<OutboxItem> {
        val seen = HashSet<PushItemId>()
        return due.takeWhile { seen.add(it.pushId) }
    }

    private suspend fun pushBatch(batch: List<OutboxItem>, pushed: PushAccumulator): SyncError? {
        val result = try {
            transport.push(
                events = batch.mapNotNull { it.eventJson },
                documents = batch.mapNotNull { it.document },
            )
        } catch (failure: SyncException) {
            if (failure.error is SyncError.Transient || failure.error is SyncError.Permanent) {
                batch.forEach { fail(it, failure.error) }
            }
            return failure.error
        }
        return settle(batch, result, pushed)
    }

    /**
     * Acks or fails every item of [batch]; returns the error that ends the push, if any. Results are matched to
     * items by [PushItemId], unique within a batch ([untilRepeatedId]), so each outbox `seq` gets its own outcome.
     */
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

                is SyncError.Permanent -> if (item.kind == OutboxItem.Kind.EVENT) {
                    outbox.fail(item.seq, error, clock.nowEpochMs() + EVENT_REJECTION_RETRY_MS)
                    pushed.rejections += PushRejection(item.seq, item.pushId, error.code, blocking = true)
                    stop = stop ?: error
                } else {
                    outbox.ack(item.seq)
                    pushed.rejections += PushRejection(item.seq, item.pushId, error.code)
                }

                SyncError.HouseholdGone -> return error

                is SyncError.Transient -> {
                    fail(item, error)
                    stop = stop ?: error
                }

                // Not an attempt: the backoff must not grow while signed out.
                SyncError.Unauthenticated -> stop = stop ?: error
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

        /** When a ledger event the server refused for good is offered again (a rules fix, an approval). */
        const val EVENT_REJECTION_RETRY_MS: Long = 3_600_000
        const val DEFAULT_DEBOUNCE_MS: Long = 500
        const val DEFAULT_MAX_PAGES: Int = 1_000
    }
}

/** Receives every [SyncReport] of a [SyncEngine], in order (see [SyncEngine]). */
fun interface SyncReportHandler {
    suspend fun handle(report: SyncReport)
}

/** What one [SyncEngine.syncOnce] did. */
data class SyncReport(
    val pulled: PullCounts = PullCounts(),
    /** Outbox items the server accepted. */
    val pushed: Int = 0,
    /**
     * Outbox items the server refused for good, to be audited as `SYNC_REJECTED` by the caller: documents are acked;
     * ledger events are kept and [blocking][PushRejection.blocking] (see [SyncEngine]).
     */
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

/**
 * One outbox item the server refused for good: its `seq`, push id and the server's code (no free text).
 *
 * @property blocking the item is a ledger event and stays in the outbox, holding back everything queued after it
 *   until it is accepted (retried every [SyncEngine.EVENT_REJECTION_RETRY_MS]) or a parent deals with it. False for
 *   documents, which are acked.
 */
data class PushRejection(val seq: Long, val id: PushItemId, val code: String, val blocking: Boolean = false)

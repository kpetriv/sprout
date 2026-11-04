package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.model.Role
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class SyncEngineTest {
    private val parent = TestIdentity("parent-device", "parent-uid", Role.PARENT)
    private val log = mutableListOf<String>()
    private val transport = ScriptedTransport(log)
    private val recording = RecordingApplier(SoftwareSignatureCheck, HOUSEHOLD)
    private val applier = LoggingApplier(recording, log)
    private val outbox = InMemoryOutboxSource()
    private val clock = FakeSyncClock(start = 1_000_000)
    private val engine = SyncEngine(
        transport,
        outbox,
        applier,
        clock,
        backoff = ExponentialBackoff(baseMs = 1_000, maxMs = 3_600_000, jitter = Jitter.None),
    )

    private fun page(n: Int, hasMore: Boolean, documents: List<DocumentEnvelope> = emptyList()) = PullPage(
        devices = if (n == 1) listOf(parent.registered) else emptyList(),
        parentKeys = emptyList(),
        documents = documents,
        events = listOf(PulledEvent(parent.event("e$n", lamport = n.toLong()), Instant.fromEpochSeconds(n * 60L))),
        next = SyncCursor(mapOf("events" to "$n")),
        hasMore = hasMore,
    )

    @Test
    fun pullsEveryPageThenRetriesHeldThenPushes() = runTest {
        val reward = parent.signed(DocTypes.REWARD, "reward-1", lamport = 1)
        transport.pages += page(1, hasMore = true, documents = listOf(reward))
        transport.pages += page(2, hasMore = false)
        outbox.enqueueEvent(parent.event("local", lamport = 3))

        val report = engine.syncOnce()

        assertEquals(
            listOf(
                "pull", "registry", "documents", "events", "saveCursor",
                "pull", "events", "saveCursor",
                "retryHeld", "push",
            ),
            log,
        )
        assertEquals(listOf(SyncCursor.START, SyncCursor(mapOf("events" to "1"))), transport.pulledCursors)
        assertEquals(
            listOf(SyncCursor(mapOf("events" to "1")), SyncCursor(mapOf("events" to "2"))),
            applier.savedCursors,
        )
        assertEquals(2, report.pulled.pages)
        assertEquals(1, report.pulled.documents.applied)
        assertEquals(2, report.pulled.events.applied)
        // Each pulled event reaches the applier with its server stamp.
        assertEquals(
            listOf(Instant.fromEpochSeconds(60), Instant.fromEpochSeconds(120)),
            recording.eventStamps().values.toList(),
        )
        assertEquals(1, report.pushed)
        assertNull(report.error)
        assertTrue(outbox.rows().isEmpty())
        assertEquals(report, engine.reports.value)
    }

    @Test
    fun documentsAreAppliedInDocTypeOrder() = runTest {
        val order = mutableListOf<String>()
        val engine = SyncEngine(
            transport,
            outbox,
            object : RemoteApplier by recording {
                override suspend fun applyDocuments(documents: List<DocumentEnvelope>): DocumentApplyReport {
                    order += documents.map { it.docType }
                    return DocumentApplyReport()
                }
            },
            clock,
        )
        transport.pages += page(
            1,
            hasMore = false,
            documents = listOf(
                DocTypes.REWARD,
                DocTypes.HOUSEHOLD_POLICY,
                DocTypes.CHORE,
                DocTypes.KID,
                DocTypes.ACCOUNT,
            )
                .map { parent.signed(it, "doc-$it".replace('_', '-'), lamport = 1) },
        )
        engine.syncOnce()
        assertEquals(
            listOf(DocTypes.KID, DocTypes.ACCOUNT, DocTypes.HOUSEHOLD_POLICY, DocTypes.CHORE, DocTypes.REWARD),
            order,
        )
    }

    @Test
    fun cursorIsSavedPerPageSoAFailedPageResumesThere() = runTest {
        transport.pages += page(1, hasMore = true)
        transport.beforePull = { if (transport.pulledCursors.size == 1) transport.pullError = SyncError.Transient() }

        val report = engine.syncOnce()

        assertEquals(SyncError.Transient(), report.error)
        assertEquals(listOf(SyncCursor(mapOf("events" to "1"))), applier.savedCursors)
        assertEquals(SyncCursor(mapOf("events" to "1")), recording.cursor())
        assertFalse("push" in log, "a failed pull ends the sync before pushing")
    }

    @Test
    fun transientRejectionBacksOffAndStops() = runTest {
        val first = outbox.enqueueEvent(parent.event("e1", 1))
        val second = outbox.enqueueEvent(parent.event("e2", 2))
        transport.outcomes[PushItemId.ofEvent(parent.event("e1", 1))] = SyncError.Transient()

        val report = engine.syncOnce()

        assertEquals(SyncError.Transient(), report.error)
        assertEquals(0, report.pushed)
        val row = outbox.row(first)!!
        assertEquals(1, row.item.attempts)
        assertEquals(clock.nowMs + 1_000, row.nextAttemptAtEpochMs)
        assertEquals("transient", row.lastError)
        // The transport stops the lane at the transient refusal; the item behind it is untouched and waits behind it.
        assertEquals(0, outbox.row(second)!!.item.attempts)
        assertNull(outbox.row(second)!!.lastError)
    }

    @Test
    fun backoffGrowsWithAttemptsAndHonoursRetryAfter() = runTest {
        val seq = outbox.enqueueEvent(parent.event("e1", 1))
        transport.pushError = SyncError.Transient()
        val delays = (1..4).map {
            engine.syncOnce()
            val row = outbox.row(seq)!!
            (row.nextAttemptAtEpochMs - clock.nowMs).also { clock.advanceBy(it) }
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), delays)

        transport.pushError = SyncError.Transient(retryAfterMs = 60_000)
        engine.syncOnce()
        assertEquals(clock.nowMs + 60_000, outbox.row(seq)!!.nextAttemptAtEpochMs)
    }

    @Test
    fun itemsWaitingForBackoffHoldBackLaterItems() = runTest {
        outbox.enqueueEvent(parent.event("e1", 1))
        transport.pushError = SyncError.Transient()
        engine.syncOnce()
        transport.pushError = null
        outbox.enqueueEvent(parent.event("e2", 2))

        val report = engine.syncOnce()

        assertEquals(0, report.pushed)
        assertEquals(1, transport.pushedBatches.size, "nothing is due while the head waits")
        clock.advanceBy(1_000)
        assertEquals(2, engine.syncOnce().pushed)
    }

    @Test
    fun pushesInSeqOrderInBatchesOfAtMostOneHundred() = runTest {
        val ids = (1..250).map { n -> parent.event("e$n", n.toLong()).also { outbox.enqueueEvent(it) } }
            .map(PushItemId::ofEvent)

        val report = engine.syncOnce()

        assertEquals(listOf(100, 100, 50), transport.pushedBatches.map { it.size })
        assertEquals(ids, transport.pushedBatches.flatten())
        assertEquals(250, report.pushed)
        assertTrue(outbox.rows().isEmpty())
    }

    @Test
    fun transientFailureStopsBeforeTheNextBatch() = runTest {
        repeat(150) { n -> outbox.enqueueEvent(parent.event("e$n", n.toLong())) }
        transport.pushError = SyncError.Transient()

        engine.syncOnce()

        assertEquals(1, transport.pushedBatches.size)
        assertEquals(100, outbox.rows().count { it.item.attempts == 1 })
        assertEquals(50, outbox.rows().count { it.item.attempts == 0 })
    }

    @Test
    fun permanentDocumentRejectionIsAckedAndReported() = runTest {
        val seq = outbox.enqueueDocument(parent.signed(DocTypes.REWARD, "reward-1", 1))
        val id = PushItemId("document/reward/reward-1")
        transport.outcomes[id] = SyncError.Permanent("permission-denied")

        val report = engine.syncOnce()

        assertNull(report.error)
        assertEquals(1, report.rejected)
        assertEquals(listOf(PushRejection(seq, id, "permission-denied")), report.rejections)
        assertTrue(outbox.rows().isEmpty())
        // Dropped through ackRejected, so an outbox with an audit log writes the refusal with the delete (A2-L3).
        assertEquals(report.rejections, outbox.ackedRejections)
    }

    @Test
    fun permanentEventRejectionIsKeptAndBlocksTheOutbox() = runTest {
        val first = outbox.enqueueEvent(parent.event("e1", 1))
        val second = outbox.enqueueEvent(parent.event("e2", 2))
        val later = (3..120).map { n -> outbox.enqueueEvent(parent.event("e$n", n.toLong())) }
        val id = PushItemId.ofEvent(parent.event("e1", 1))
        transport.outcomes[id] = SyncError.Permanent("permission-denied")

        val report = engine.syncOnce()

        assertEquals(SyncError.Permanent("permission-denied"), report.error)
        assertEquals(listOf(PushRejection(first, id, "permission-denied", blocking = true)), report.rejections)
        val row = outbox.row(first)!!
        assertEquals(clock.nowMs + SyncEngine.EVENT_REJECTION_RETRY_MS, row.nextAttemptAtEpochMs)
        assertEquals("permission-denied", row.lastError)
        // Everything behind it stays queued and untouched (not attempted); the push stops before the next batch.
        assertEquals(1, transport.pushedBatches.size)
        assertTrue((listOf(second) + later).all { outbox.row(it)?.item?.attempts == 0 })
        assertEquals(0, report.pushed)
        assertEquals(InMemoryOutboxSource.Blocked(first, "permission-denied"), outbox.observeBlocked().first())

        // Nothing is due behind the blocked head until its retry time; then it is offered again.
        assertEquals(0, engine.syncOnce().pushed)
        transport.outcomes.remove(id)
        clock.advanceBy(SyncEngine.EVENT_REJECTION_RETRY_MS)
        val retried = engine.syncOnce()
        assertNull(retried.error)
        assertTrue(outbox.rows().isEmpty())
    }

    @Test
    fun twoRowsWithTheSamePushIdAreSettledSeparately() = runTest {
        // A document rewritten after its first row was taken for a push, or an outbox that keeps both rows.
        val v1 = parent.signed(DocTypes.REWARD, "reward-1", 1)
        val v2 = parent.signed(DocTypes.REWARD, "reward-1", 2)
        val rows = InMemoryOutboxSourceWithDuplicates(listOf(OutboxItem.document(1, v1), OutboxItem.document(2, v2)))
        val engine = SyncEngine(transport, rows, applier, clock, backoff = ExponentialBackoff(jitter = Jitter.None))
        val id = PushItemId.ofDocument(v1)

        val report = engine.syncOnce()

        assertNull(report.error)
        assertEquals(2, report.pushed)
        assertEquals(listOf(listOf(id), listOf(id)), transport.pushedBatches, "never two of one id in a batch")
        assertEquals(listOf(1L, 2L), rows.acked)
    }

    @Test
    fun itemMissingFromTheResultIsRetried() = runTest {
        val seq = outbox.enqueueEvent(parent.event("e1", 1))
        transport.outcomes[PushItemId.ofEvent(parent.event("e1", 1))] = null

        val report = engine.syncOnce()

        assertEquals(SyncError.Transient(), report.error)
        assertEquals(1, outbox.row(seq)!!.item.attempts)
    }

    @Test
    fun unauthenticatedStopsWithoutCountingAnAttempt() = runTest {
        val first = outbox.enqueueEvent(parent.event("e1", 1))
        transport.outcomes[PushItemId.ofEvent(parent.event("e1", 1))] = SyncError.Unauthenticated
        repeat(120) { n -> outbox.enqueueEvent(parent.event("more$n", n + 2L)) }

        val report = engine.syncOnce()

        assertEquals(SyncError.Unauthenticated, report.error)
        val row = outbox.row(first)!!
        assertEquals(0, row.item.attempts)
        assertEquals(0, row.nextAttemptAtEpochMs)
        assertNull(row.lastError)
        assertEquals(1, transport.pushedBatches.size)

        // A whole push refused for the session does not count either.
        transport.outcomes.clear()
        transport.pushError = SyncError.Unauthenticated
        assertEquals(SyncError.Unauthenticated, engine.syncOnce().error)
        assertEquals(0, outbox.row(first)!!.item.attempts)

        // Signed in again: the rest goes at once (99 were accepted in the first batch), no backoff to wait out.
        transport.pushError = null
        assertEquals(22, engine.syncOnce().pushed)
        assertTrue(outbox.rows().isEmpty())

        transport.pullError = SyncError.Unauthenticated
        outbox.enqueueEvent(parent.event("after", 200))
        assertEquals(SyncError.Unauthenticated, engine.syncOnce().error)
        assertEquals(3, transport.pushedBatches.size, "nothing is pushed after a failed pull")
    }

    @Test
    fun errorCodesAreKebabCase() {
        val codes = listOf(SyncError.Transient(), SyncError.Unauthenticated, SyncError.HouseholdGone).map { it.code }
        assertEquals(listOf("transient", "unauthenticated", "household-gone"), codes)
        assertTrue(codes.all { it.matches(Regex("[a-z]+(-[a-z]+)*")) })
    }

    @Test
    fun householdGoneFromPullIsFlagged() = runTest {
        outbox.enqueueEvent(parent.event("e1", 1))
        transport.pullError = SyncError.HouseholdGone

        val report = engine.syncOnce()

        assertTrue(report.householdGone)
        assertEquals(SyncError.HouseholdGone, report.error)
        assertTrue(transport.pushedBatches.isEmpty())
    }

    @Test
    fun householdGoneFromPushIsFlaggedWithoutBackoff() = runTest {
        val seq = outbox.enqueueEvent(parent.event("e1", 1))
        transport.pushError = SyncError.HouseholdGone

        val report = engine.syncOnce()

        assertTrue(report.householdGone)
        assertEquals(0, outbox.row(seq)!!.item.attempts)

        transport.pushError = null
        transport.outcomes[PushItemId.ofEvent(parent.event("e1", 1))] = SyncError.HouseholdGone
        assertTrue(engine.syncOnce().householdGone)
    }

    @Test
    fun runCoalescesTriggers() = runTest {
        val trigger = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
        engine.run(backgroundScope, trigger)
        runCurrent()

        repeat(5) {
            trigger.emit(Unit)
            advanceTimeBy(100)
        }
        assertEquals(0, transport.pulledCursors.size, "debounced while triggers keep coming")
        advanceTimeBy(SyncEngine.DEFAULT_DEBOUNCE_MS + 1)
        assertEquals(1, transport.pulledCursors.size)

        transport.remoteChanges.emit(RemoteChange("documents"))
        advanceTimeBy(SyncEngine.DEFAULT_DEBOUNCE_MS + 1)
        assertEquals(2, transport.pulledCursors.size, "a remote change also syncs")
    }

    @Test
    fun aFailingChangeListenerIsResubscribedWithBackoff() = runTest {
        var subscriptions = 0
        val live = MutableSharedFlow<RemoteChange>(extraBufferCapacity = 16)
        val flaky = object : SyncTransport by transport {
            override fun changes(): Flow<RemoteChange> = flow {
                subscriptions++
                if (subscriptions <= 2) throw SyncException(SyncError.Transient())
                emitAll(live)
            }
        }
        val engine = SyncEngine(
            flaky,
            outbox,
            applier,
            clock,
            backoff = ExponentialBackoff(baseMs = 1_000, maxMs = 60_000, jitter = Jitter.None),
        )
        engine.run(backgroundScope, emptyFlow())
        runCurrent()
        assertEquals(1, subscriptions)
        advanceTimeBy(1_001)
        assertEquals(2, subscriptions, "first retry after the base delay")
        advanceTimeBy(1_001)
        assertEquals(2, subscriptions, "the second retry waits twice as long")
        advanceTimeBy(1_000)
        assertEquals(3, subscriptions)

        // Live updates are back.
        live.emit(RemoteChange("events"))
        advanceTimeBy(SyncEngine.DEFAULT_DEBOUNCE_MS + 1)
        assertEquals(1, transport.pulledCursors.size)
    }

    @Test
    fun requestsDuringASyncRunExactlyOneMoreSync() = runTest {
        val gate = CompletableDeferred<Unit>()
        transport.beforePull = { gate.await() }
        val trigger = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
        engine.run(backgroundScope, trigger)
        runCurrent()

        trigger.emit(Unit)
        advanceTimeBy(SyncEngine.DEFAULT_DEBOUNCE_MS + 1)
        // The first sync is waiting inside pull; three more requests arrive, debounced apart.
        repeat(3) {
            trigger.emit(Unit)
            advanceTimeBy(SyncEngine.DEFAULT_DEBOUNCE_MS + 1)
        }
        assertEquals(0, transport.pulledCursors.size)
        gate.complete(Unit)
        settle()
        assertEquals(2, transport.pulledCursors.size)
    }

    @Test
    fun everyReportReachesTheHandlerEvenBackToBack() = runTest {
        val handled = mutableListOf<SyncReport>()
        val engine = SyncEngine(transport, outbox, applier, clock, reportHandler = { handled += it })
        val first = outbox.enqueueDocument(parent.signed(DocTypes.REWARD, "reward-1", 1))
        transport.outcomes[PushItemId("document/reward/reward-1")] = SyncError.Permanent("permission-denied")
        val one = backgroundScope.async { engine.syncOnce() }
        val two = backgroundScope.async {
            outbox.enqueueDocument(parent.signed(DocTypes.REWARD, "reward-2", 2))
            transport.outcomes[PushItemId("document/reward/reward-2")] = SyncError.Permanent("invalid-argument")
            engine.syncOnce()
        }
        one.await()
        two.await()

        // The conflated StateFlow keeps only the last one; the handler saw both, in order.
        assertEquals(listOf(one.getCompleted(), two.getCompleted()), handled)
        assertEquals(listOf(first), handled.first().rejections.map { it.seq })
        assertEquals(listOf("invalid-argument"), handled.last().rejections.map { it.code })
        assertEquals(two.getCompleted(), engine.reports.value)
    }

    @Test
    fun theLoopsReportsReachTheHandlerAndAFailingHandlerDoesNotEndTheLoop() = runTest {
        val handled = mutableListOf<SyncReport>()
        val engine = SyncEngine(
            transport,
            outbox,
            applier,
            clock,
            reportHandler = {
                handled += it
                error("handler failure")
            },
        )
        val trigger = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
        engine.run(backgroundScope, trigger)
        runCurrent()

        repeat(2) {
            trigger.emit(Unit)
            settle()
        }

        assertEquals(2, handled.size)
        assertEquals(2, transport.pulledCursors.size)
    }

    @Test
    fun syncOnceCallsAreSerialised() = runTest {
        val gate = CompletableDeferred<Unit>()
        var inside = 0
        var maxInside = 0
        transport.beforePull = {
            inside++
            maxInside = maxOf(maxInside, inside)
            gate.await()
            inside--
        }
        val first = backgroundScope.async { engine.syncOnce() }
        val second = backgroundScope.async { engine.syncOnce() }
        runCurrent()
        gate.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, maxInside)
    }

    @Test
    fun exclusiveWaitsForTheSyncInFlightAndHoldsOffTheNextOne() = runTest {
        val pulling = CompletableDeferred<Unit>()
        transport.beforePull = { pulling.await() }
        val steps = mutableListOf<String>()
        val inFlight = backgroundScope.async { engine.syncOnce().also { steps += "first sync done" } }
        runCurrent()
        val held = CompletableDeferred<Unit>()
        val exclusive = backgroundScope.async {
            engine.exclusive {
                steps += "exclusive"
                held.await()
            }
        }
        runCurrent()
        assertEquals(emptyList(), steps, "the sync in flight finishes first")

        pulling.complete(Unit)
        inFlight.await()
        runCurrent()
        val next = backgroundScope.async { engine.syncOnce().also { steps += "next sync done" } }
        runCurrent()
        assertEquals(listOf("first sync done", "exclusive"), steps, "no sync starts while it runs")

        held.complete(Unit)
        exclusive.await()
        next.await()
        assertEquals(listOf("first sync done", "exclusive", "next sync done"), steps)
    }

    // ---- chat lanes (M4 §3) ----

    @Test
    fun messagesAreAppliedAfterTheDocumentsOfTheirPage() = runTest {
        val threadKey = parent.signed(DocTypes.THREAD_KEY, "kid-1.0", lamport = 1)
        transport.pages += PullPage(
            devices = listOf(parent.registered),
            parentKeys = emptyList(),
            documents = listOf(threadKey),
            events = listOf(PulledEvent(parent.event("e1", lamport = 2))),
            next = SyncCursor(mapOf("messages" to "1")),
            hasMore = false,
            messages = listOf(parent.message("m1", seq = 1, lamport = 3), parent.message("m2", seq = 2, lamport = 4)),
            messageStamps = mapOf("m1" to Instant.fromEpochMilliseconds(1_000)),
        )

        val report = engine.syncOnce()

        assertEquals(listOf("pull", "registry", "documents", "events", "messages", "saveCursor", "retryHeld"), log)
        // Each message's server stamp reaches the applier with it (D-M7-80).
        assertEquals(mapOf("m1" to Instant.fromEpochMilliseconds(1_000)), recording.messageStamps)
        assertEquals(MessageApplyReport(applied = 2), report.pulled.messages)
        assertEquals(listOf("m1", "m2"), recording.messages().map { it.messageId.value })
        assertEquals(SyncCursor(mapOf("messages" to "1")), recording.cursor())
    }

    @Test
    fun quarantinedMessagesCountAndHeldOnesAreReported() = runTest {
        val stranger = TestIdentity("kid-device", "kid-uid", Role.KID, KID_1)
        transport.pages += PullPage(
            devices = listOf(parent.registered),
            parentKeys = emptyList(),
            documents = emptyList(),
            events = emptyList(),
            next = SyncCursor.START,
            hasMore = false,
            // A gap (seq 2 without 1) is held; an unsigned copy quarantined.
            messages = listOf(
                parent.message("m2", seq = 2),
                parent.message("m9", seq = 1).with("signature", "AAAA"),
                stranger.message("k1"),
            ),
        )

        val report = engine.syncOnce()

        assertEquals(MessageApplyReport(quarantined = 1, held = 2), report.pulled.messages)
        assertEquals(1, report.quarantined)
    }

    @Test
    fun itemsOfEveryKindArePushedInOutboxOrder() = runTest {
        val chunk = parent.blobChunk("b1", thread = KID_1)
        val message = parent.message("m1", kind = dev.kpetriv.sprout.core.domainledger.chat.MessageKind.PHOTO)
        val submission = parent.signed(DocTypes.CHORE_SUBMISSION, "sub-1", 1)
        outbox.enqueueEvent(parent.event("e1", 1))
        outbox.enqueueBlobChunk(chunk)
        outbox.enqueueDocument(submission)
        outbox.enqueueMessage(message)

        val report = engine.syncOnce()

        assertEquals(
            listOf(
                PushItemId.ofEvent(parent.event("e1", 1)),
                PushItemId("blob/b1.0"),
                PushItemId.ofDocument(submission),
                PushItemId("message/m1"),
            ),
            transport.pushedBatches.single(),
        )
        assertEquals(4, report.pushed)
        assertTrue(outbox.rows().isEmpty())
    }

    @Test
    fun permanentMessageRejectionIsKeptAndBlocksTheChatLaneOnly() = runTest {
        val first = outbox.enqueueMessage(parent.message("m1"))
        val chatBehind = listOf(
            outbox.enqueueMessage(parent.message("m2", seq = 2)),
            outbox.enqueueBlobChunk(parent.blobChunk("b1", thread = KID_1)),
        )
        val ledgerBehind = listOf(
            outbox.enqueueEvent(parent.event("e1", 1)),
            outbox.enqueueDocument(parent.signed(DocTypes.REWARD, "reward-1", 1)),
        )
        val id = PushItemId("message/m1")
        transport.outcomes[id] = SyncError.Permanent("permission-denied")

        val report = engine.syncOnce()

        assertEquals(SyncError.Permanent("permission-denied"), report.error)
        assertEquals(listOf(PushRejection(first, id, "permission-denied", blocking = true)), report.rejections)
        val row = outbox.row(first)!!
        assertEquals(clock.nowMs + SyncEngine.EVENT_REJECTION_RETRY_MS, row.nextAttemptAtEpochMs)
        // The chat items queued behind the refused message are untouched: still queued, never attempted.
        assertTrue(chatBehind.all { outbox.row(it)?.item?.attempts == 0 && outbox.row(it)?.lastError == null })
        // The ledger lane does not wait for the chat lane (D-M4-22): the event and the document went out.
        assertEquals(2, report.pushed)
        assertTrue(ledgerBehind.all { outbox.row(it) == null })
        assertEquals(InMemoryOutboxSource.Blocked(first, "permission-denied"), outbox.observeBlocked().first())

        // Nothing goes out behind the blocked head until it is accepted.
        assertEquals(0, engine.syncOnce().pushed)
        transport.outcomes.remove(id)
        clock.advanceBy(SyncEngine.EVENT_REJECTION_RETRY_MS)
        assertNull(engine.syncOnce().error)
        assertTrue(outbox.rows().isEmpty())
        assertNull(outbox.observeBlocked().first())
    }

    @Test
    fun aRefusedEventDoesNotHoldBackTheMessageBehindIt() = runTest {
        val event = outbox.enqueueEvent(parent.event("e1", 1))
        val nextEvent = outbox.enqueueEvent(parent.event("e2", 2))
        val message = outbox.enqueueMessage(parent.message("m1"))
        val id = PushItemId.ofEvent(parent.event("e1", 1))
        transport.outcomes[id] = SyncError.Permanent("permission-denied")

        val report = engine.syncOnce()

        assertEquals(listOf(PushRejection(event, id, "permission-denied", blocking = true)), report.rejections)
        assertNull(outbox.row(message), "the message went out")
        assertEquals(1, report.pushed)
        assertEquals(0, outbox.row(nextEvent)!!.item.attempts, "the next event waits behind the refused one")
        assertEquals(InMemoryOutboxSource.Blocked(event, "permission-denied"), outbox.observeBlocked().first())

        // Later chat items keep going out while the ledger lane is blocked.
        val later = outbox.enqueueMessage(parent.message("m2", seq = 2))
        assertEquals(1, engine.syncOnce().pushed)
        assertNull(outbox.row(later))
        assertTrue(outbox.row(event) != null && outbox.row(nextEvent) != null)
    }

    @Test
    fun aDeferredChunkDoesNotHoldBackTheEventsBehindIt() = runTest {
        val chunk = outbox.enqueueBlobChunk(parent.blobChunk("b1", thread = KID_1))
        val message = outbox.enqueueMessage(
            parent.message("m1", kind = dev.kpetriv.sprout.core.domainledger.chat.MessageKind.PHOTO),
        )
        val events = (1..3).map { n -> outbox.enqueueEvent(parent.event("e$n", n.toLong())) }
        val id = PushItemId("blob/b1.0")
        // Over the daily blob budget: transient until midnight (D-M4-22), never permanent.
        transport.outcomes[id] = SyncError.Transient(retryAfterMs = 600_000)

        val report = engine.syncOnce()

        assertEquals(SyncError.Transient(retryAfterMs = 600_000), report.error)
        assertTrue(report.rejections.isEmpty(), "a deferral is not a refusal")
        assertEquals(3, report.pushed)
        assertTrue(events.all { outbox.row(it) == null }, "the events went out")
        assertEquals(clock.nowMs + 600_000, outbox.row(chunk)!!.nextAttemptAtEpochMs)
        assertEquals(0, outbox.row(message)!!.item.attempts, "the message naming the blob waits, untouched")
        assertNull(outbox.observeBlocked().first())

        // New events go out while the chunk waits for its retry; the message does not overtake the chunk.
        val later = outbox.enqueueEvent(parent.event("e4", 4))
        assertEquals(1, engine.syncOnce().pushed)
        assertNull(outbox.row(later))
        assertTrue(transport.pushedBatches.last().none { it == PushItemId("message/m1") })

        transport.outcomes.remove(id)
        clock.advanceBy(600_000)
        assertEquals(2, engine.syncOnce().pushed)
        assertTrue(outbox.rows().isEmpty())
    }

    @Test
    fun permanentBlobChunkRejectionBlocksTheMessageThatNamesIt() = runTest {
        val seq = outbox.enqueueBlobChunk(parent.blobChunk("b1"))
        val message = outbox.enqueueMessage(
            parent.message("m1", kind = dev.kpetriv.sprout.core.domainledger.chat.MessageKind.PHOTO),
        )
        val id = PushItemId("blob/b1.0")
        transport.outcomes[id] = SyncError.Permanent("permission-denied")

        val report = engine.syncOnce()

        assertEquals(SyncError.Permanent("permission-denied"), report.error)
        assertEquals(listOf(PushRejection(seq, id, "permission-denied", blocking = true)), report.rejections)
        assertEquals(listOf(listOf(id, PushItemId("message/m1"))), transport.pushedBatches)
        assertEquals(listOf(id), transport.pushedItems.map { it.id }.take(1))
        assertEquals(0, outbox.row(message)!!.item.attempts, "the message behind the chunk is never attempted")
        assertEquals(InMemoryOutboxSource.Blocked(seq, "permission-denied"), outbox.observeBlocked().first())
    }

    @Test
    fun aTransportThatIgnoresTheBlockStillAcksWhatItAccepted() = runTest {
        // Defensive: an item accepted after a blocking refusal is on the server, so it is acked.
        val transport = object : SyncTransport {
            override suspend fun push(items: List<PushItem>) = PushResult(
                accepted = items.drop(1).map { it.id },
                rejected = listOf(items.first().id to SyncError.Permanent("permission-denied")),
            )

            override suspend fun pull(cursor: SyncCursor) = ScriptedTransport.emptyPage(cursor)

            override suspend fun isAvailable() = true
        }
        val engine = SyncEngine(transport, outbox, applier, clock)
        val first = outbox.enqueueEvent(parent.event("e1", 1))
        val second = outbox.enqueueEvent(parent.event("e2", 2))

        val report = engine.syncOnce()

        assertEquals(1, report.pushed)
        assertTrue(outbox.row(first) != null)
        assertNull(outbox.row(second))
    }

    @Test
    fun malformedRowsOfAPullAreCounted() = runTest {
        transport.pages +=
            PullPage(emptyList(), emptyList(), emptyList(), emptyList(), SyncCursor.START, false, malformed = 2)

        assertEquals(2, engine.syncOnce().pulled.malformed)
    }

    @Test
    fun anOutboxItemCarriesExactlyTheFieldOfItsKind() {
        val message = parent.message("m1")
        val chunk = parent.blobChunk("b1")

        assertEquals(PushItemId("message/m1"), OutboxItem.message(1, message).pushId)
        assertEquals(PushItemId("blob/b1.0"), OutboxItem.blobChunk(2, chunk).pushId)
        assertEquals(3, OutboxItem.blobChunk(2, chunk).withAttempts(3).attempts)
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            OutboxItem(1, OutboxItem.Kind.MESSAGE, null, null, 0, blobChunk = chunk)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            OutboxItem(1, OutboxItem.Kind.BLOB_CHUNK, null, null, 0, messageJson = message, blobChunk = chunk)
        }
    }

    @Test
    fun aBlobChunkCopiesItsBytesAndHidesThemFromToString() {
        val bytes = byteArrayOf(1, 2, 3)
        val chunk = BlobChunkJson(parent.blobChunk("b1").header, bytes)
        bytes[0] = 9

        assertEquals(1, chunk.data[0].toInt())
        assertEquals("BlobChunkJson(docId=b1.0, size=3)", chunk.toString())
    }

    private fun TestScope.settle() {
        advanceTimeBy(10_000)
        runCurrent()
    }
}

/** An outbox that keeps two rows of one document (no upsert), recording acks. */
private class InMemoryOutboxSourceWithDuplicates(items: List<OutboxItem>) : OutboxSource {
    private val pending = items.toMutableList()
    val acked = mutableListOf<Long>()

    override suspend fun due(nowEpochMs: Long, limit: Int, lanes: Set<OutboxItem.Lane>): List<OutboxItem> =
        pending.filter { it.kind.lane in lanes }.take(limit)

    override suspend fun ack(seq: Long) {
        acked += seq
        pending.removeAll { it.seq == seq }
    }

    override suspend fun fail(seq: Long, error: SyncError, nextAttemptAtEpochMs: Long) = Unit
}

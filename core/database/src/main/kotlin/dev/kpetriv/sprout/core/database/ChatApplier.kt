package dev.kpetriv.sprout.core.database

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.crypto.X25519PublicKey
import dev.kpetriv.sprout.core.crypto.chat.MessagePlaintextException
import dev.kpetriv.sprout.core.crypto.chat.ThreadKeyUnwrapException
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.AuditLogEntity
import dev.kpetriv.sprout.core.database.entity.MessageEntity
import dev.kpetriv.sprout.core.database.entity.MessageSeqEntity
import dev.kpetriv.sprout.core.database.entity.MessageStatus
import dev.kpetriv.sprout.core.database.entity.SyncCursorEntity
import dev.kpetriv.sprout.core.database.entity.ThreadKeyEntity
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.domainledger.QuarantineReason
import dev.kpetriv.sprout.core.domainledger.SignatureCheck
import dev.kpetriv.sprout.core.domainledger.chat.MessageEnvelope
import dev.kpetriv.sprout.core.domainledger.chat.MessageJson
import dev.kpetriv.sprout.core.domainledger.chat.MessagePlaintextCodec
import dev.kpetriv.sprout.core.domainledger.chat.MessageVerifier
import dev.kpetriv.sprout.core.domainledger.chat.ThreadKeyDocument
import dev.kpetriv.sprout.core.domainledger.chat.VerifiedMessage
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.ThreadId
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.sync.ConflictResolver
import dev.kpetriv.sprout.core.sync.DocumentEnvelope
import dev.kpetriv.sprout.core.sync.MessageApplyReport
import dev.kpetriv.sprout.core.sync.StoredDocument
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

/**
 * The chat half of [RoomRemoteApplier] (M4 §1.2, §1.3, §4).
 *
 * **Messages** ([applyMessages]). Every pulled `MessageJson` tree goes through [MessageVerifier] against the registry
 * mirror, with `expectedSeq` from `message_seq`, sorted by (author device, thread, seq) so a page that arrives out of
 * order does not hold needlessly:
 * - **Ok**: its lamport must be above the previous message of the author in the thread (`message_seq.lastLamport`,
 *   audit M4), else it is quarantined [OUT_OF_ORDER] (its `seq` is still used up). Then stored, and decrypted with
 *   every key this device holds for its thread and epoch: RECEIVED when one opens it, LOCKED otherwise (no key yet,
 *   or no held key opens it: decryption failures are never quarantines). A ciphertext that authenticates but holds no
 *   valid plaintext (bad padding, a lone surrogate, review H1) is quarantined `UNDECODABLE`. Every per-message
 *   decryption failure is caught, so one message can never abort the batch's transaction. The time shown
 *   (`displayedAtEpochMs`) is the signed `createdAt` raised to the previous message's. The author's `message_seq`
 *   moves to `seq + 1` and the device clock observes the lamport.
 * - **Duplicate** (a `seq` already applied): ignored, unless the `seq` was skipped after a gap timed out and nothing
 *   is stored for it: then it is accepted **late** (audit L3, marked late and labelled "Arrived late" on screen). Its
 *   lamport must fall between the author's displayed neighbours in the thread (above the previous `seq`'s, below the
 *   next one's, audit A2-L1), else it is quarantined [OUT_OF_ORDER] and its `seq` is used up; its time shown is its
 *   `createdAt` raised to the previous displayed message's, so an author cannot backdate it above older messages. A
 *   different message id for a `seq` that already has a stored message is equivocation, audited
 *   `MESSAGE_EQUIVOCATION` once.
 * - **Held** (a `seq` gap, an unknown or pending author device) and a lamport too far ahead (retryable, as for
 *   documents): kept as a HELD row with its ciphertext, never shown, verified again after every batch, registry pull
 *   and [retryHeld]. At most [MAX_HELD_PER_AUTHOR] per author device: beyond it the earliest received are dropped,
 *   the farthest ahead (highest `seq`) first among those received together (audited `HELD_DROPPED`).
 *   A gap older than [HELD_GAP_TIMEOUT_MS] (its predecessor was quarantined, or never uploaded) is skipped: the
 *   author's expected `seq` jumps to its lowest held one (audited `MESSAGE_GAP_SKIPPED`) and the skipped range is
 *   remembered, so one bad message cannot stall a thread for ever. A gap a server prune explains is skipped at once
 *   (D-M7-53): the household's chat prune watermark ([applyChatPrune]) is later than when this device received the
 *   author's last stored message before the gap (or nothing of that author is stored here), so the missing messages
 *   may have been deleted before this device pulled them. Pulls are ordered by the server's stamp and an author
 *   uploads in `seq` order, so a missing message stored after the cut would already have come with the held one; if
 *   one still turns up, it is accepted late as for any skipped range.
 * - **Quarantined**: audited `MESSAGE_QUARANTINED` (ids and reason only) and kept as a QUARANTINED row without
 *   ciphertext; a verified message with the same id replaces that row.
 *
 * **Thread keys** ([applyThreadKey], for a document [RemoteDocumentVerifier] already verified: a PARENT author with a
 * valid signature). The payload must parse, its creator must be the envelope's author and its id the document id
 * (`"<threadId>.<epoch>.<creator>"`). Rows are kept per (thread, epoch, creator), last-writer-wins per creator
 * ([ConflictResolver]). When the document wraps for this device, the wrap is opened with this device's private key
 * and the creator's **registered, binding-verified** encryption key from the registry mirror (never a key from the
 * document). A newer document of a creator whose key differs from the stored one (review H2) replaces it and the old
 * key is kept in `olderKeys`, so messages under either stay readable; then every LOCKED message of the thread is
 * retried. This device's own document coming back from the server confirms it ([ThreadKeyEntity.confirmed]). A wrap
 * that cannot be opened yet (no registered key for the creator, no key pair here) leaves the key empty;
 * [retryMissingKeys] tries again after registry pulls (one `THREAD_KEY_UNWRAP_FAILED` row per document).
 *
 * Everything here runs in the caller's transaction (messages: their own, under the device clock lock). Plaintext
 * and keys never reach logs or audit rows.
 */
@Suppress("TooManyFunctions", "LargeClass") // The message and thread key paths of the applier.
internal class ChatApplier(
    private val database: SproutDatabase,
    private val clock: Clock,
    private val check: SignatureCheck,
    private val maxLamportJump: Long,
    private val crypto: ChatCrypto,
) {
    private val registry = RoomDeviceRegistry(database)
    private val deviceClock = DeviceClock.of(database)
    private val messages = database.messageDao()
    private val threadKeys = database.threadKeyDao()
    private val keyStore = ThreadKeyStore(database)

    /** See the class documentation. */
    suspend fun applyMessages(trees: List<JsonObject>): MessageApplyReport = deviceClock.withLock {
        database.withTransaction {
            val session = database.sessionDao().get()?.toDomain()
            if (session == null) {
                MessageApplyReport()
            } else {
                val batch = Batch(session, deviceClock.current())
                val decoded = trees.map { it to MessageJson.decodeOrNull(it) }
                val (readable, broken) = decoded.partition { it.second != null }
                for ((tree, _) in broken) batch.report += refuseUndecodable(batch, tree)
                for ((_, envelope) in readable.sortedWith(ENVELOPE_ORDER)) {
                    batch.report += process(batch, requireNotNull(envelope), held = null)
                }
                batch.report += releaseHeld(batch)
                batch.report
            }
        }
    }

    /** Verifies held messages again (the registry or the clock may have caught up) and skips expired gaps. */
    suspend fun retryHeld(): MessageApplyReport = deviceClock.withLock {
        database.withTransaction {
            val session = database.sessionDao().get()?.toDomain()
            if (session == null) MessageApplyReport() else releaseHeld(Batch(session, deviceClock.current()))
        }
    }

    /**
     * Remembers the household's chat prune watermark ([dev.kpetriv.sprout.core.sync.PullPage.chatPrunedBefore],
     * D-M7-53) for the session's household, never moving it back. Kept device-local in `sync_cursor` under
     * [CHAT_PRUNED_BEFORE] (a key no collection has; [RoomRemoteApplier.cursor] leaves it out), so no schema change.
     */
    suspend fun applyChatPrune(prunedBefore: Instant) {
        database.withTransaction {
            val household = database.sessionDao().get()?.householdId ?: return@withTransaction
            val known = chatPrunedBefore(household)
            val value = prunedBefore.toEpochMilliseconds()
            if (known == null || value > known) {
                database.syncCursorDao().upsert(listOf(SyncCursorEntity(household, CHAT_PRUNED_BEFORE, "$value")))
            }
        }
    }

    private suspend fun chatPrunedBefore(household: HouseholdId): Long? = database.syncCursorDao().all(household)
        .firstOrNull { it.collection == CHAT_PRUNED_BEFORE }?.cursor?.toLongOrNull()

    /** The `seq` [MessageVerifier] expects next from [deviceId] in [threadId]; 1 when none was applied. */
    suspend fun expectedSeq(deviceId: DeviceId, threadId: ThreadId): Long =
        messages.seq(threadId.value, deviceId)?.nextSeq ?: 1

    private inner class Batch(val session: Session, var localMax: Long) {
        val verifier = MessageVerifier(registry, check, maxLamportJump, session.householdId)
        val now: Long = clock.now().toEpochMilliseconds()
        var report = MessageApplyReport()
    }

    private suspend fun refuseUndecodable(batch: Batch, tree: JsonObject): MessageApplyReport {
        val refused = batch.verifier.verify(tree, batch.localMax) { _, _ -> 1 }
        if (refused is VerifiedMessage.Quarantined) {
            audit(AuditAction.MESSAGE_QUARANTINED, refused.authorDeviceId, refused.messageId?.value, batch.now) {
                put("reason", refused.reason.name)
            }
        }
        return MessageApplyReport(quarantined = 1)
    }

    /** Verifies and stores one envelope; [held] is its HELD row when this is a retry. */
    private suspend fun process(batch: Batch, envelope: MessageEnvelope, held: MessageEntity?): MessageApplyReport {
        val expected = expectedSeq(envelope.authorDeviceId, envelope.threadId)
        return when (val verified = batch.verifier.verify(envelope, batch.localMax, expected)) {
            is VerifiedMessage.Ok -> accept(batch, envelope, held, late = false)

            is VerifiedMessage.Duplicate -> duplicate(batch, envelope, held)

            is VerifiedMessage.Held -> hold(batch, envelope, verified.reason, held)

            is VerifiedMessage.Quarantined -> if (verified.reason == QuarantineReason.LAMPORT_TOO_FAR_AHEAD) {
                hold(batch, envelope, verified.reason, held)
            } else {
                quarantine(batch, envelope, verified.reason.name, held)
            }
        }
    }

    /**
     * An authenticated message for a `seq` below the expected one: accepted late when that `seq` was skipped and
     * nothing is stored for it, audited as equivocation when another message holds it, otherwise ignored.
     */
    private suspend fun duplicate(batch: Batch, envelope: MessageEnvelope, held: MessageEntity?): MessageApplyReport {
        val threadId = envelope.threadId.value
        val stored = messages.storedAt(envelope.authorDeviceId, threadId, envelope.seq)
        val seqRow = messages.seq(threadId, envelope.authorDeviceId)
        // Nothing usable stored for the seq: empty, or this very message's quarantined copy (a tampered relay).
        val free = stored == null ||
            (stored.status == MessageStatus.QUARANTINED && stored.messageId == envelope.messageId.value)
        if (free && seqRow != null && SkippedRanges.contains(seqRow.skippedRanges, envelope.seq)) {
            return accept(batch, envelope, held, late = true)
        }
        if (stored != null && stored.messageId != envelope.messageId.value) {
            val target = envelope.messageId.value
            if (database.auditLogDao().countSince(AuditAction.MESSAGE_EQUIVOCATION, target, 0) == 0) {
                audit(AuditAction.MESSAGE_EQUIVOCATION, envelope.authorDeviceId, target, batch.now) {
                    put("threadId", threadId)
                    put("seq", envelope.seq)
                    put("firstMessageId", stored.messageId)
                }
            }
        }
        held?.let { messages.delete(it.messageId) }
        return MessageApplyReport(duplicates = if (held == null) 1 else 0)
    }

    @Suppress("ReturnCount") // Out of order, then an id conflict, return early.
    private suspend fun accept(
        batch: Batch,
        envelope: MessageEnvelope,
        held: MessageEntity?,
        late: Boolean,
    ): MessageApplyReport {
        val threadId = envelope.threadId.value
        val previous = messages.seq(threadId, envelope.authorDeviceId)
        val outOfOrder = if (late) {
            !fitsBetweenNeighbours(envelope)
        } else {
            previous != null && previous.lastLamport > 0 && envelope.lamport <= previous.lastLamport
        }
        if (outOfOrder) {
            if (late) {
                // The skipped seq is used up: a copy of it never comes back through the late path.
                val base = requireNotNull(previous)
                messages.upsertSeq(base.copy(skippedRanges = SkippedRanges.remove(base.skippedRanges, envelope.seq)))
            } else {
                advanceSeq(envelope.threadId, envelope.authorDeviceId, envelope.seq + 1)
            }
            return quarantine(batch, envelope, OUT_OF_ORDER, held)
        }
        // The verifier bounded the lamport by localMax + maxLamportJump.
        batch.localMax = maxOf(batch.localMax, envelope.lamport)
        database.observeDeviceLamport(batch.localMax)
        val displayed = recordAccepted(envelope, previous, late)
        val existing = messages.get(envelope.messageId.value)
        val replaceable = existing == null || existing == held || existing.status == MessageStatus.QUARANTINED ||
            existing.status == MessageStatus.HELD
        if (!replaceable) {
            // Another authenticated message already holds this id: keep it; this one is refused.
            audit(AuditAction.MESSAGE_QUARANTINED, envelope.authorDeviceId, envelope.messageId.value, batch.now) {
                put("threadId", threadId)
                put("seq", envelope.seq)
                put("reason", MESSAGE_ID_CONFLICT)
            }
            return MessageApplyReport(quarantined = 1)
        }
        return when (val opened = open(envelope)) {
            Opened.Undecodable -> quarantine(batch, envelope, QuarantineReason.UNDECODABLE.name, held)

            Opened.Locked, is Opened.Plain -> {
                val plaintext = (opened as? Opened.Plain)?.bytes
                val status = if (plaintext == null) MessageStatus.LOCKED else MessageStatus.RECEIVED
                messages.upsert(MessageEntity.of(envelope, status, plaintext, null, batch.now, displayed, late))
                MessageApplyReport(applied = 1)
            }
        }
    }

    /**
     * Whether a late [envelope]'s lamport falls strictly between the author's displayed messages around its `seq` in
     * the thread (audit A2-L1): above the previous one's and below the next one's, each when there is one.
     */
    private suspend fun fitsBetweenNeighbours(envelope: MessageEnvelope): Boolean {
        val threadId = envelope.threadId.value
        val before = messages.displayedBefore(envelope.authorDeviceId, threadId, envelope.seq)
        val after = messages.displayedAfter(envelope.authorDeviceId, threadId, envelope.seq)
        return (before == null || envelope.lamport > before.lamport) &&
            (after == null || envelope.lamport < after.lamport)
    }

    /**
     * Moves the author's `message_seq` past [envelope] (or, for a [late] one, forgets its skipped `seq`); returns the
     * time to show it at: its `createdAt`, raised to the previous message's (for a late one, the author's previous
     * displayed message in the thread, audit A2-L1).
     */
    private suspend fun recordAccepted(envelope: MessageEnvelope, previous: MessageSeqEntity?, late: Boolean): Long {
        val createdAt = envelope.createdAt.toEpochMilliseconds()
        val base = previous ?: MessageSeqEntity(envelope.threadId.value, envelope.authorDeviceId, 1)
        if (late) {
            messages.upsertSeq(base.copy(skippedRanges = SkippedRanges.remove(base.skippedRanges, envelope.seq)))
            val before = messages.displayedBefore(envelope.authorDeviceId, envelope.threadId.value, envelope.seq)
            return maxOf(createdAt, before?.displayedAtEpochMs ?: 0)
        }
        val displayed = maxOf(createdAt, base.lastDisplayedAtEpochMs)
        messages.upsertSeq(
            base.copy(
                nextSeq = maxOf(base.nextSeq, envelope.seq + 1),
                lastLamport = envelope.lamport,
                lastDisplayedAtEpochMs = displayed,
            ),
        )
        return displayed
    }

    private suspend fun hold(
        batch: Batch,
        envelope: MessageEnvelope,
        reason: QuarantineReason,
        held: MessageEntity?,
    ): MessageApplyReport {
        if (held != null) {
            if (held.reason != reason.name) messages.upsert(held.copy(reason = reason.name))
            return MessageApplyReport()
        }
        val existing = messages.get(envelope.messageId.value)
        val sameSeqHeld = messages.heldAt(envelope.authorDeviceId, envelope.threadId.value, envelope.seq) > 0
        val known = (existing != null && existing.status != MessageStatus.QUARANTINED) || sameSeqHeld
        if (!known) {
            messages.upsert(MessageEntity.of(envelope, MessageStatus.HELD, null, reason.name, batch.now))
            capHeld(envelope.authorDeviceId, batch.now)
        }
        return if (known) MessageApplyReport(duplicates = 1) else MessageApplyReport(held = 1)
    }

    private suspend fun capHeld(author: DeviceId, now: Long) {
        val excess = messages.heldCountFor(author) - MAX_HELD_PER_AUTHOR
        if (excess <= 0) return
        for (row in messages.oldestHeldFor(author, excess)) {
            messages.delete(row.messageId)
            audit(AuditAction.HELD_DROPPED, author, row.messageId, now) {
                put("kind", "message")
                put("threadId", row.threadId)
                put("seq", row.seq)
                row.reason?.let { put("reason", it) }
            }
        }
    }

    /** [reason] is a `QuarantineReason` name or one of this applier's own codes ([OUT_OF_ORDER]). */
    private suspend fun quarantine(
        batch: Batch,
        envelope: MessageEnvelope,
        reason: String,
        held: MessageEntity?,
    ): MessageApplyReport {
        held?.let { messages.delete(it.messageId) }
        val existing = messages.get(envelope.messageId.value)
        if (existing == null) {
            messages.upsert(MessageEntity.of(envelope, MessageStatus.QUARANTINED, null, reason, batch.now))
        }
        audit(AuditAction.MESSAGE_QUARANTINED, envelope.authorDeviceId, envelope.messageId.value, batch.now) {
            put("threadId", envelope.threadId.value)
            put("seq", envelope.seq)
            put("reason", reason)
        }
        return MessageApplyReport(quarantined = 1)
    }

    /**
     * Verifies every HELD row again, repeatedly while that makes progress (a released message may fill the gap the
     * next one waits for), after skipping expired gaps. Rows a retry still holds are not counted again.
     */
    private suspend fun releaseHeld(batch: Batch): MessageApplyReport {
        var report = MessageApplyReport()
        skipExpiredGaps(batch.now, chatPrunedBefore(batch.session.householdId))
        do {
            var progress = false
            for (row in messages.withStatus(MessageStatus.HELD)) {
                val envelope = row.toEnvelope()
                if (envelope == null) {
                    messages.delete(row.messageId)
                    continue
                }
                val outcome = process(batch, envelope, row)
                report += outcome
                if (messages.get(row.messageId)?.status != MessageStatus.HELD) progress = true
            }
        } while (progress)
        return report
    }

    /**
     * For each (author, thread) whose oldest SEQ_GAP row has waited longer than [HELD_GAP_TIMEOUT_MS], or whose gap a
     * prune explains ([prunedBefore], see the class documentation), moves the expected `seq` up to its lowest held one
     * and remembers the skipped range.
     */
    private suspend fun skipExpiredGaps(now: Long, prunedBefore: Long?) {
        val gaps = messages.withStatus(MessageStatus.HELD)
            .filter { it.reason == QuarantineReason.SEQ_GAP.name }
            .groupBy { it.authorDeviceId to it.threadId }
        for ((key, rows) in gaps) {
            val (author, threadId) = key
            val expected = expectedSeq(author, ThreadId(threadId))
            val lowest = rows.minOf { it.seq }
            val timedOut = now - rows.minOf { it.receivedAtEpochMs } > HELD_GAP_TIMEOUT_MS
            val pruned = !timedOut && prunedBefore != null && lowest > expected &&
                (messages.storedAt(author, threadId, expected - 1)?.receivedAtEpochMs ?: Long.MIN_VALUE) < prunedBefore
            if (lowest <= expected || !(timedOut || pruned)) continue
            val current = messages.seq(threadId, author) ?: MessageSeqEntity(threadId, author, 1)
            messages.upsertSeq(
                current.copy(
                    nextSeq = lowest,
                    skippedRanges = SkippedRanges.add(current.skippedRanges, expected, lowest - 1),
                ),
            )
            audit(AuditAction.MESSAGE_GAP_SKIPPED, author, threadId, now) {
                put("fromSeq", expected)
                put("toSeq", lowest)
                if (pruned) put("cause", "pruned")
            }
        }
    }

    private suspend fun advanceSeq(threadId: ThreadId, author: DeviceId, next: Long) {
        val current = messages.seq(threadId.value, author)
        if (next > (current?.nextSeq ?: 1)) {
            messages.upsertSeq(current?.copy(nextSeq = next) ?: MessageSeqEntity(threadId.value, author, next))
        }
    }

    /** What decrypting a message gave. */
    private sealed interface Opened {
        /** The canonical plaintext bytes. */
        class Plain(val bytes: ByteArray) : Opened

        /** No held key opens it (yet). */
        data object Locked : Opened

        /** A held key authenticates it, but it holds no valid plaintext: never readable. */
        data object Undecodable : Opened
    }

    /**
     * [envelope] decrypted with any key this device holds for its epoch. Every failure of one key is caught (review
     * H1): a key that does not authenticate leaves it [Opened.Locked], one that authenticates an invalid plaintext
     * makes it [Opened.Undecodable].
     */
    @Suppress("TooGenericExceptionCaught") // Any crypto or codec failure of one message must not abort the batch.
    private suspend fun open(envelope: MessageEnvelope): Opened {
        val keys = keyStore.keysFor(envelope.threadId, envelope.epoch)
        var undecodable = false
        try {
            for (key in keys) {
                try {
                    return Opened.Plain(MessagePlaintextCodec.encode(crypto.messages.decrypt(key, envelope)))
                } catch (expected: MessagePlaintextException) {
                    undecodable = true
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (expected: Exception) {
                    // Not this key; try the next one.
                }
            }
        } finally {
            keys.forEach { it.close() }
        }
        return if (undecodable) Opened.Undecodable else Opened.Locked
    }

    /**
     * Retries every LOCKED message of [threadId]: one that now decrypts becomes RECEIVED, one that authenticates but
     * does not decode becomes QUARANTINED `UNDECODABLE` (audited). A failure of one row never stops the others.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // One broken row must not abort the transaction.
    private suspend fun unlock(threadId: ThreadId) {
        for (row in messages.lockedIn(threadId.value)) {
            try {
                val envelope = row.toEnvelope() ?: continue
                when (val opened = open(envelope)) {
                    is Opened.Plain -> messages.upsert(
                        row.copy(plaintext = opened.bytes, status = MessageStatus.RECEIVED),
                    )

                    Opened.Undecodable -> quarantineLocked(row)

                    Opened.Locked -> Unit
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (expected: Exception) {
                // Stays LOCKED; retried with the next key.
            }
        }
    }

    private suspend fun quarantineLocked(row: MessageEntity) {
        val reason = QuarantineReason.UNDECODABLE.name
        messages.upsert(
            row.copy(
                plaintext = null,
                status = MessageStatus.QUARANTINED,
                reason = reason,
                ciphertext = "",
                signature = null,
            ),
        )
        audit(AuditAction.MESSAGE_QUARANTINED, row.authorDeviceId, row.messageId, clock.now().toEpochMilliseconds()) {
            put("threadId", row.threadId)
            put("seq", row.seq)
            put("reason", reason)
        }
    }

    /** What [applyThreadKey] did. */
    enum class KeyOutcome { APPLIED, IGNORED, MALFORMED }

    /** Applies one verified `thread_key` document; see the class documentation. Inside the caller's transaction. */
    @Suppress("ReturnCount") // Tombstones and malformed payloads return early.
    suspend fun applyThreadKey(envelope: DocumentEnvelope, session: Session, keys: LazyKeyPair): KeyOutcome {
        if (envelope.deleted) return KeyOutcome.IGNORED
        val document = ThreadKeyDocument.fromPayload(envelope.payload)
        if (document == null || document.createdByDeviceId != envelope.authorDeviceId ||
            !document.matchesDocId(envelope.docId)
        ) {
            return KeyOutcome.MALFORMED
        }
        val existing = threadKeys.get(document.threadId.value, document.epoch, document.createdByDeviceId)
        return when {
            existing != null && envelope.authorDeviceId == session.deviceId && existing.lamport == envelope.lamport -> {
                // This device's own document, back from the server: it is confirmed.
                if (!existing.confirmed) threadKeys.upsert(existing.withConfirmed(true))
                KeyOutcome.IGNORED
            }

            existing != null && ConflictResolver.resolve(storedOf(existing), envelope) ==
                ConflictResolver.Decision.Ignore -> {
                // An older copy: it may still open a wrap the stored one could not open yet.
                val key = if (existing.key == null) unwrap(document, session, keys) else null
                if (key != null) {
                    threadKeys.upsert(existing.withKey(key))
                    unlock(document.threadId)
                }
                KeyOutcome.IGNORED
            }

            else -> {
                store(envelope, document, existing, unwrap(document, session, keys))
                KeyOutcome.APPLIED
            }
        }
    }

    /**
     * Stores [document] over [existing] (same creator and epoch) with [unwrapped], its key here: a different key
     * than the stored one keeps the stored one in `olderKeys`, and a new key unlocks the thread's LOCKED messages.
     */
    private suspend fun store(
        envelope: DocumentEnvelope,
        document: ThreadKeyDocument,
        existing: ThreadKeyEntity?,
        unwrapped: ByteArray?,
    ) {
        val stored = existing?.key
        val changed = unwrapped != null && stored != null && !unwrapped.contentEquals(stored)
        threadKeys.upsert(
            ThreadKeyEntity(
                threadId = document.threadId.value,
                epoch = document.epoch,
                createdByDeviceId = document.createdByDeviceId,
                key = unwrapped ?: stored,
                sourceDocId = envelope.docId,
                wrapSetJson = ThreadKeyStore.wrapSetJson(document),
                lamport = envelope.lamport,
                authorUid = envelope.authorUid,
                signature = envelope.signature,
                receivedAtEpochMs = clock.now().toEpochMilliseconds(),
                confirmed = true,
                olderKeys = if (changed) olderKeysWith(existing) else existing?.olderKeys,
            ),
        )
        if (unwrapped != null && (stored == null || changed)) unlock(document.threadId)
    }

    /**
     * Tries again to open the wraps of stored documents whose key this device does not have yet (the creator's
     * encryption key may have reached the registry mirror since). Inside the caller's transaction.
     */
    suspend fun retryMissingKeys(keys: LazyKeyPair) {
        val session = database.sessionDao().get()?.toDomain() ?: return
        for (row in threadKeys.withoutKey()) {
            // Null for a document that no longer parses or has no wrap this device can open.
            val document = ThreadKeyStore.documentOf(row)
            val key = document?.let { unwrap(it, session, keys) } ?: continue
            threadKeys.upsert(row.withKey(key))
            unlock(document.threadId)
        }
    }

    /**
     * This device's copy of [document]'s key, or null when the document has no wrap for it, the creator has no
     * verified encryption key in the registry mirror, this device has no key pair, or the wrap does not open
     * (audited `THREAD_KEY_UNWRAP_FAILED`, once per document: retries after every registry pull add no rows).
     */
    @Suppress("ReturnCount") // One early return per missing ingredient.
    private suspend fun unwrap(document: ThreadKeyDocument, session: Session, keys: LazyKeyPair): ByteArray? {
        if (document.wrappedFor(session.deviceId) == null) return null
        val senderKey = registry.device(document.createdByDeviceId)?.verifiedEncryptionKey(check) ?: return null
        val pair = keys.get() ?: return null
        return try {
            crypto.keyring.unwrap(
                session.householdId,
                document,
                session.deviceId,
                pair.privateKey,
                X25519PublicKey(senderKey),
            ).use { it.copyBytes() }
        } catch (expected: ThreadKeyUnwrapException) {
            val now = clock.now().toEpochMilliseconds()
            audit(AuditAction.THREAD_KEY_UNWRAP_FAILED, document.createdByDeviceId, document.docId, now, once = true) {
                put("threadId", document.threadId.value)
                put("epoch", document.epoch)
            }
            null
        }
    }

    private fun storedOf(row: ThreadKeyEntity): StoredDocument = StoredDocument(
        lamport = row.lamport,
        authorDeviceId = row.createdByDeviceId,
        deleted = false,
        payload = ThreadKeyStore.documentOf(row)?.toPayload() ?: JsonObject(emptyMap()),
    )

    /** Appends an audit row; with [once], only when no row with the same action, target and details exists. */
    @Suppress("LongParameterList") // The audit row's fields.
    private suspend fun audit(
        action: AuditAction,
        deviceId: DeviceId?,
        target: String?,
        now: Long,
        once: Boolean = false,
        details: JsonObjectBuilder.() -> Unit,
    ) {
        val detailsJson = LedgerJson.json.encodeToString(JsonObject.serializer(), buildJsonObject(details))
        val targetValue = target ?: UNKNOWN_TARGET
        if (once && database.auditLogDao().countExact(action, targetValue, detailsJson) > 0) return
        database.auditLogDao().insert(
            AuditLogEntity(
                atEpochMs = now,
                actorUid = UNKNOWN_ACTOR,
                deviceId = deviceId ?: UNKNOWN_DEVICE,
                action = action,
                target = targetValue,
                detailsJson = detailsJson,
            ),
        )
    }

    companion object {
        /** Most HELD messages kept per author device; beyond it the earliest received are dropped (audited). */
        const val MAX_HELD_PER_AUTHOR: Int = 500

        /**
         * The `sync_cursor` key of the chat prune watermark ([applyChatPrune]): epoch ms as text. `#` never starts a
         * Firestore collection id, so no transport cursor can collide with it.
         */
        const val CHAT_PRUNED_BEFORE: String = "#chatPrunedBefore"

        /** How long a `seq` gap may hold later messages before it is skipped: one day. */
        const val HELD_GAP_TIMEOUT_MS: Long = 24L * 60 * 60 * 1000

        /** Quarantine reason of a verified message whose id another stored message already has. */
        const val MESSAGE_ID_CONFLICT: String = "MESSAGE_ID_CONFLICT"

        /**
         * Quarantine reason of an authenticated message whose lamport is not above the previous message of its author
         * device in its thread (audit M4: `seq` and lamport must grow together).
         */
        const val OUT_OF_ORDER: String = "OUT_OF_ORDER"

        private val UNKNOWN_ACTOR = UserId("remote")
        private val UNKNOWN_DEVICE = DeviceId("unknown")
        private const val UNKNOWN_TARGET = "unknown"

        private val ENVELOPE_ORDER: Comparator<Pair<JsonObject, MessageEnvelope?>> = compareBy(
            { it.second?.authorDeviceId?.value },
            { it.second?.threadId?.value },
            { it.second?.seq },
        )
    }
}

/**
 * The `seq` ranges of `message_seq.skippedRanges` (audit L3): `"from-to"` pairs (inclusive) separated by commas,
 * oldest first, at most [MAX_RANGES] (the oldest are forgotten first).
 */
internal object SkippedRanges {
    const val MAX_RANGES: Int = 32

    fun parse(text: String): List<LongRange> = text.split(',').mapNotNull { part ->
        val bounds = part.split('-')
        val from = bounds.getOrNull(0)?.toLongOrNull()
        val to = bounds.getOrNull(1)?.toLongOrNull()
        (from?.let { start -> to?.let { start..it } })?.takeIf { bounds.size == 2 && !it.isEmpty() }
    }

    fun contains(text: String, seq: Long): Boolean = parse(text).any { seq in it }

    fun add(text: String, from: Long, to: Long): String = format(parse(text) + listOf(from..to))

    fun remove(text: String, seq: Long): String = format(
        parse(text).flatMap { range ->
            if (seq !in range) {
                listOf(range)
            } else {
                listOfNotNull(
                    (range.first until seq).takeUnless {
                        it.isEmpty()
                    },
                    ((seq + 1)..range.last).takeUnless { it.isEmpty() },
                )
            }
        },
    )

    private fun format(ranges: List<LongRange>): String =
        ranges.takeLast(MAX_RANGES).joinToString(",") { "${it.first}-${it.last}" }
}

package dev.kpetriv.sprout.core.database

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.crypto.X25519PublicKey
import dev.kpetriv.sprout.core.crypto.chat.MessageDecryptionException
import dev.kpetriv.sprout.core.crypto.chat.ThreadKeyUnwrapException
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.AuditLogEntity
import dev.kpetriv.sprout.core.database.entity.MessageEntity
import dev.kpetriv.sprout.core.database.entity.MessageSeqEntity
import dev.kpetriv.sprout.core.database.entity.MessageStatus
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
import dev.kpetriv.sprout.core.model.ThreadId
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.sync.ConflictResolver
import dev.kpetriv.sprout.core.sync.DocumentEnvelope
import dev.kpetriv.sprout.core.sync.MessageApplyReport
import dev.kpetriv.sprout.core.sync.StoredDocument
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The chat half of [RoomRemoteApplier] (M4 §1.2, §1.3, §4).
 *
 * **Messages** ([applyMessages]). Every pulled `MessageJson` tree goes through [MessageVerifier] against the registry
 * mirror, with `expectedSeq` from `message_seq`, sorted by (author device, thread, seq) so a page that arrives out of
 * order does not hold needlessly:
 * - **Ok**: stored, and decrypted with every key this device holds for its thread and epoch; RECEIVED when one opens
 *   it, LOCKED otherwise (no key yet, or no held key opens it: decryption failures are never quarantines). The
 *   author's `message_seq` moves to `seq + 1` and the device clock observes the lamport.
 * - **Duplicate** (a `seq` already applied): ignored.
 * - **Held** (a `seq` gap, an unknown or pending author device) and a lamport too far ahead (retryable, as for
 *   documents): kept as a HELD row with its ciphertext, never shown, verified again after every batch, registry pull
 *   and [retryHeld]. At most [MAX_HELD_PER_AUTHOR] per author device: beyond it the earliest received are dropped,
 *   the farthest ahead (highest `seq`) first among those received together (audited `HELD_DROPPED`).
 *   A gap older than [HELD_GAP_TIMEOUT_MS] (its predecessor was quarantined, or never uploaded) is skipped: the
 *   author's expected `seq` jumps to its lowest held one (audited `MESSAGE_GAP_SKIPPED`), so one bad message cannot
 *   stall a thread for ever. A message for a skipped `seq` that arrives later is a duplicate.
 * - **Quarantined**: audited `MESSAGE_QUARANTINED` (ids and reason only) and kept as a QUARANTINED row without
 *   ciphertext; a verified message with the same id replaces that row.
 *
 * **Thread keys** ([applyThreadKey], for a document [RemoteDocumentVerifier] already verified: a PARENT author with a
 * valid signature). The payload must parse, its creator must be the envelope's author and its id the document id.
 * Rows are kept per (thread, epoch, creator), last-writer-wins per creator ([ConflictResolver]). When the document
 * wraps for this device, the wrap is opened with this device's private key and the creator's **registered,
 * binding-verified** encryption key from the registry mirror (never a key from the document), and the key is stored;
 * then every LOCKED message of the thread is retried. A wrap that cannot be opened yet (no registered key for the
 * creator, no key pair here) leaves the key empty; [retryMissingKeys] tries again after registry pulls.
 *
 * Everything here runs in the caller's transaction (messages: their own, under the device clock lock). Plaintext
 * and keys never reach logs or audit rows.
 */
@Suppress("TooManyFunctions") // The message and thread key paths of the applier.
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
            is VerifiedMessage.Ok -> accept(batch, envelope, held)

            is VerifiedMessage.Duplicate -> {
                held?.let { messages.delete(it.messageId) }
                MessageApplyReport(duplicates = if (held == null) 1 else 0)
            }

            is VerifiedMessage.Held -> hold(batch, envelope, verified.reason, held)

            is VerifiedMessage.Quarantined -> if (verified.reason == QuarantineReason.LAMPORT_TOO_FAR_AHEAD) {
                hold(batch, envelope, verified.reason, held)
            } else {
                quarantine(batch, envelope, verified.reason, held)
            }
        }
    }

    private suspend fun accept(batch: Batch, envelope: MessageEnvelope, held: MessageEntity?): MessageApplyReport {
        // The verifier bounded the lamport by localMax + maxLamportJump.
        batch.localMax = maxOf(batch.localMax, envelope.lamport)
        database.observeDeviceLamport(batch.localMax)
        advanceSeq(envelope.threadId, envelope.authorDeviceId, envelope.seq + 1)
        val existing = messages.get(envelope.messageId.value)
        val replaceable = existing == null || existing == held || existing.status == MessageStatus.QUARANTINED ||
            existing.status == MessageStatus.HELD
        if (!replaceable) {
            // Another authenticated message already holds this id: keep it; this one is refused.
            audit(AuditAction.MESSAGE_QUARANTINED, envelope.authorDeviceId, envelope.messageId.value, batch.now) {
                put("threadId", envelope.threadId.value)
                put("seq", envelope.seq)
                put("reason", MESSAGE_ID_CONFLICT)
            }
            return MessageApplyReport(quarantined = 1)
        }
        val plaintext = decrypt(envelope)
        val status = if (plaintext == null) MessageStatus.LOCKED else MessageStatus.RECEIVED
        messages.upsert(MessageEntity.of(envelope, status, plaintext, null, batch.now))
        return MessageApplyReport(applied = 1)
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

    private suspend fun quarantine(
        batch: Batch,
        envelope: MessageEnvelope,
        reason: QuarantineReason,
        held: MessageEntity?,
    ): MessageApplyReport {
        held?.let { messages.delete(it.messageId) }
        val existing = messages.get(envelope.messageId.value)
        if (existing == null) {
            messages.upsert(MessageEntity.of(envelope, MessageStatus.QUARANTINED, null, reason.name, batch.now))
        }
        audit(AuditAction.MESSAGE_QUARANTINED, envelope.authorDeviceId, envelope.messageId.value, batch.now) {
            put("threadId", envelope.threadId.value)
            put("seq", envelope.seq)
            put("reason", reason.name)
        }
        return MessageApplyReport(quarantined = 1)
    }

    /**
     * Verifies every HELD row again, repeatedly while that makes progress (a released message may fill the gap the
     * next one waits for), after skipping expired gaps. Rows a retry still holds are not counted again.
     */
    private suspend fun releaseHeld(batch: Batch): MessageApplyReport {
        var report = MessageApplyReport()
        skipExpiredGaps(batch.now)
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
     * For each (author, thread) whose oldest SEQ_GAP row has waited longer than [HELD_GAP_TIMEOUT_MS], moves the
     * expected `seq` up to its lowest held one.
     */
    private suspend fun skipExpiredGaps(now: Long) {
        val gaps = messages.withStatus(MessageStatus.HELD)
            .filter { it.reason == QuarantineReason.SEQ_GAP.name }
            .groupBy { it.authorDeviceId to it.threadId }
        val expired = gaps.filterValues { rows -> now - rows.minOf { it.receivedAtEpochMs } > HELD_GAP_TIMEOUT_MS }
        for ((key, rows) in expired) {
            val (author, threadId) = key
            val expected = expectedSeq(author, ThreadId(threadId))
            val lowest = rows.minOf { it.seq }
            if (lowest <= expected) continue
            advanceSeq(ThreadId(threadId), author, lowest)
            audit(AuditAction.MESSAGE_GAP_SKIPPED, author, threadId, now) {
                put("fromSeq", expected)
                put("toSeq", lowest)
            }
        }
    }

    private suspend fun advanceSeq(threadId: ThreadId, author: DeviceId, next: Long) {
        val current = messages.seq(threadId.value, author)?.nextSeq ?: 1
        if (next > current) messages.upsertSeq(MessageSeqEntity(threadId.value, author, next))
    }

    /** The canonical plaintext bytes of [envelope] under any key this device holds for its epoch, or null. */
    private suspend fun decrypt(envelope: MessageEnvelope): ByteArray? {
        val keys = keyStore.keysFor(envelope.threadId, envelope.epoch)
        return try {
            keys.firstNotNullOfOrNull { key ->
                try {
                    MessagePlaintextCodec.encode(crypto.messages.decrypt(key, envelope))
                } catch (expected: MessageDecryptionException) {
                    null
                }
            }
        } finally {
            keys.forEach { it.close() }
        }
    }

    /** Retries every LOCKED message of [threadId]; a message that now decrypts becomes RECEIVED. */
    private suspend fun unlock(threadId: ThreadId) {
        for (row in messages.lockedIn(threadId.value)) {
            val plaintext = row.toEnvelope()?.let { decrypt(it) } ?: continue
            messages.upsert(row.copy(plaintext = plaintext, status = MessageStatus.RECEIVED))
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
        val applies = existing == null || ConflictResolver.resolve(storedOf(existing), envelope) !=
            ConflictResolver.Decision.Ignore
        val key = existing?.key ?: unwrap(document, session, keys)
        val row = if (applies) {
            ThreadKeyEntity(
                threadId = document.threadId.value,
                epoch = document.epoch,
                createdByDeviceId = document.createdByDeviceId,
                key = key,
                sourceDocId = envelope.docId,
                wrapSetJson = ThreadKeyStore.wrapSetJson(document),
                lamport = envelope.lamport,
                authorUid = envelope.authorUid,
                signature = envelope.signature,
                receivedAtEpochMs = clock.now().toEpochMilliseconds(),
            )
        } else {
            existing?.takeIf { it.key == null && key != null }?.withKey(key)
        }
        row?.let { threadKeys.upsert(it) }
        if (existing?.key == null && key != null) unlock(document.threadId)
        return if (applies) KeyOutcome.APPLIED else KeyOutcome.IGNORED
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
     * (audited `THREAD_KEY_UNWRAP_FAILED`).
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
            audit(AuditAction.THREAD_KEY_UNWRAP_FAILED, document.createdByDeviceId, document.docId, now) {
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

    private suspend fun audit(
        action: AuditAction,
        deviceId: DeviceId?,
        target: String?,
        now: Long,
        details: JsonObjectBuilder.() -> Unit,
    ) {
        database.auditLogDao().insert(
            AuditLogEntity(
                atEpochMs = now,
                actorUid = UNKNOWN_ACTOR,
                deviceId = deviceId ?: UNKNOWN_DEVICE,
                action = action,
                target = target ?: UNKNOWN_TARGET,
                detailsJson = LedgerJson.json.encodeToString(JsonObject.serializer(), buildJsonObject(details)),
            ),
        )
    }

    companion object {
        /** Most HELD messages kept per author device; beyond it the earliest received are dropped (audited). */
        const val MAX_HELD_PER_AUTHOR: Int = 500

        /** How long a `seq` gap may hold later messages before it is skipped: one day. */
        const val HELD_GAP_TIMEOUT_MS: Long = 24L * 60 * 60 * 1000

        /** Quarantine reason of a verified message whose id another stored message already has. */
        const val MESSAGE_ID_CONFLICT: String = "MESSAGE_ID_CONFLICT"

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

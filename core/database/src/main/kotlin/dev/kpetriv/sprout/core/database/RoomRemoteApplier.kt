package dev.kpetriv.sprout.core.database

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.database.entity.AccountEntity
import dev.kpetriv.sprout.core.database.entity.AllowanceScheduleEntity
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.AuditLogEntity
import dev.kpetriv.sprout.core.database.entity.ChoreEntity
import dev.kpetriv.sprout.core.database.entity.ChoreSubmissionEntity
import dev.kpetriv.sprout.core.database.entity.DeviceEntity
import dev.kpetriv.sprout.core.database.entity.DocumentInboxEntity
import dev.kpetriv.sprout.core.database.entity.DocumentStamp
import dev.kpetriv.sprout.core.database.entity.HouseholdPolicyEntity
import dev.kpetriv.sprout.core.database.entity.InterestPolicyEntity
import dev.kpetriv.sprout.core.database.entity.KidEntity
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.database.entity.RewardEntity
import dev.kpetriv.sprout.core.database.entity.SavingsGoalEntity
import dev.kpetriv.sprout.core.database.entity.SessionEntity
import dev.kpetriv.sprout.core.database.entity.SyncCursorEntity
import dev.kpetriv.sprout.core.domainhousehold.AllowanceSchedule
import dev.kpetriv.sprout.core.domainhousehold.Chore
import dev.kpetriv.sprout.core.domainhousehold.Reward
import dev.kpetriv.sprout.core.domainhousehold.SavingsGoal
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.JvmSha256Hasher
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.domainledger.LedgerPolicy
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.RegisteredParentKey
import dev.kpetriv.sprout.core.domainledger.SignatureCheck
import dev.kpetriv.sprout.core.domainledger.toHex
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSubmission
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.GoalId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.InterestPolicy
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.ScheduleId
import dev.kpetriv.sprout.core.model.SubmissionId
import dev.kpetriv.sprout.core.model.ThreadId
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.sync.ConflictResolver
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.sync.DocumentApplyReport
import dev.kpetriv.sprout.core.sync.DocumentEnvelope
import dev.kpetriv.sprout.core.sync.DocumentJsonCodec
import dev.kpetriv.sprout.core.sync.DocumentRejection
import dev.kpetriv.sprout.core.sync.EventApplyReport
import dev.kpetriv.sprout.core.sync.MessageApplyReport
import dev.kpetriv.sprout.core.sync.PulledEvent
import dev.kpetriv.sprout.core.sync.RemoteApplier
import dev.kpetriv.sprout.core.sync.RemoteDocumentVerifier
import dev.kpetriv.sprout.core.sync.StoredDocument
import dev.kpetriv.sprout.core.sync.SyncCursor
import dev.kpetriv.sprout.core.sync.VerifiedDocument
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import kotlin.time.Instant

/**
 * [RemoteApplier] on Room: what a pull brings is verified and written to the same tables local writes use.
 *
 * - [applyRegistry] upserts the registry mirror (`devices`, `parent_keys`), audited `DEVICE_REGISTERED` /
 *   `DEVICE_REVOKED`, and refreshes the session's device status when this device's entry changed. Parent keys come
 *   only from here: they are registered by the server (`registerParentKey`, D-M3-34).
 * - [applyDocuments] verifies every envelope with [RemoteDocumentVerifier] (registry, [check], the default
 *   `maxLamportJump`, this session's household) and resolves it with [ConflictResolver] against the stored row, in
 *   [DocTypes.applyOrder] (kid, account, household_policy, then the rest), all in one transaction. An applied document
 *   upserts its row from the payload (the same entity mappers local writes use) with the envelope's lamport, author,
 *   signature and tombstone flag; a tombstone keeps the row with `deleted = 1`, which every read hides and no later
 *   update can resurrect. The device clock observes every verified lamport. A document whose author device is
 *   unknown or PENDING is **held** in `document_inbox` (D-M3-31) and retried by [retryHeld]; one refused for a lamport
 *   too far ahead is audited and kept there too. Other refusals (another household included) get a
 *   `DOCUMENT_REJECTED` audit row (reason and ids only). Payloads that do not decode or whose ids disagree with the
 *   envelope are ignored and audited the same way; there is no `parent_key` document type (the
 *   verifier refuses one as an unknown type). At most [MAX_HELD_PER_AUTHOR] held
 *   documents per author device are kept. Kept device-local: an allowance schedule's `lastPaidOn`, an account's
 *   `lastAccruedOn`, this device's parent key. A pulled `household_policy` that changes the daily game cap rebuilds
 *   the ledger's balance caches once the documents are applied (D-M7-11).
 * - [applyEvents] is [RoomLedgerRepository.ingest]; [retryHeld] retries held documents, then
 *   [RoomLedgerRepository.retryHeld].
 * - [cursor] and [saveCursor] use `sync_cursor`, keyed by the session's household.
 */
@Suppress("TooManyFunctions", "LargeClass") // One apply function per document type.
class RoomRemoteApplier internal constructor(
    private val database: SproutDatabase,
    private val ledger: RoomLedgerRepository,
    private val clock: Clock,
    private val check: SignatureCheck,
    private val maxLamportJump: Long,
    private val crypto: ChatCrypto,
) : RemoteApplier {
    @Inject
    constructor(
        database: SproutDatabase,
        ledger: RoomLedgerRepository,
        clock: Clock,
        check: SignatureCheck,
        crypto: ChatCrypto,
    ) : this(database, ledger, clock, check, LedgerPolicy.DEFAULT_MAX_LAMPORT_JUMP, crypto)

    private val chat = ChatApplier(database, clock, check, maxLamportJump, crypto)
    private val registry = RoomDeviceRegistry(database)
    private val deviceClock = DeviceClock.of(database)
    private val audit = database.auditLogDao()

    override suspend fun applyRegistry(devices: List<RegisteredDevice>, parentKeys: List<RegisteredParentKey>) {
        database.withTransaction {
            val now = clock.now().toEpochMilliseconds()
            val session = database.sessionDao().get()
            devices.forEach { applyDevice(it, session, now) }
            for (key in parentKeys) {
                val existing = database.parentKeyDao().get(key.fingerprint)
                val entity = key.toEntity(existing?.registeredAtEpochMs ?: now)
                if (entity.fingerprint == JvmSha256Hasher.sha256(entity.publicKey).toHex()) {
                    database.parentKeyDao().upsert(entity)
                }
            }
            // A creator's encryption key may have just arrived: open the wraps that were waiting for it.
            if (devices.isNotEmpty()) LazyKeyPair(crypto).use { chat.retryMissingKeys(it) }
        }
    }

    /**
     * Mirrors one registry row, **pinning** what was seen first (audit H1, D-M4-16): a row whose device id is not the
     * SHA-256 of its public key is dropped (`DEVICE_REJECTED`); a known device's public key and verified encryption
     * key are never replaced (a different encryption key is kept out, the device is marked
     * [DeviceEntity.encryptionKeyConflict] and so excluded from every thread, audited `DEVICE_KEY_CHANGED` once); its
     * owner (`uid`, `role`, `kidId`) is pinned the same way (audit I1: the server writes a device row once and never
     * changes its owner, so a change would turn, say, a known KID device into a PARENT that every thread wraps for;
     * the first values are kept, the device is excluded, audited `DEVICE_KEY_CHANGED` with `key: "owner"` once); a
     * REVOKED device never becomes ACTIVE or PENDING again here, and its revocation lamport never moves up
     * (`DEVICE_REVOCATION_KEPT`).
     */
    @Suppress("ReturnCount") // A rejected row returns early.
    private suspend fun applyDevice(device: RegisteredDevice, session: SessionEntity?, now: Long) {
        if (JvmSha256Hasher.sha256(device.publicKey).toHex() != device.deviceId.value) {
            record(AuditAction.DEVICE_REJECTED, device.uid, device.deviceId, device.deviceId.value, now) {}
            return
        }
        val existing = database.deviceDao().get(device.deviceId)
        val incoming = device.toEntity(existing?.registeredAtEpochMs ?: now, session?.householdId)
        val entity = if (existing == null) incoming else pinned(existing, incoming, now)
        database.deviceDao().upsert(entity)
        val action = when {
            existing == null -> AuditAction.DEVICE_REGISTERED

            entity.status == RegistrationStatus.REVOKED && existing.status != RegistrationStatus.REVOKED ->
                AuditAction.DEVICE_REVOKED

            else -> null
        }
        if (action != null) {
            record(action, entity.uid, device.deviceId, device.deviceId.value, now) {
                put("role", entity.role.name)
                put("status", entity.status.name)
                put("attestation", device.attestation.name)
            }
        }
        if (session != null && session.deviceId == device.deviceId && session.deviceStatus != entity.status) {
            database.sessionDao().upsert(session.copy(deviceStatus = entity.status))
        }
    }

    /** [incoming] with everything [existing] pins kept; see [applyDevice]. Audits what it refused. */
    private suspend fun pinned(existing: DeviceEntity, incoming: DeviceEntity, now: Long): DeviceEntity {
        val pinnedKey = existing.encryptionKey?.takeIf { existing.toRegistered().verifiedEncryptionKey(check) != null }
        val keyChanged = pinnedKey != null && incoming.encryptionKey != null &&
            !incoming.encryptionKey.contentEquals(pinnedKey)
        val changed = changedPin(existing, incoming, keyChanged)
        // A changed signing key is audited every time; the other changes once, when the device is first excluded.
        if (changed == PUBLIC_KEY || (changed != null && !existing.encryptionKeyConflict)) {
            record(AuditAction.DEVICE_KEY_CHANGED, existing.uid, existing.deviceId, existing.deviceId.value, now) {
                put("key", changed)
            }
        }
        val revoked = existing.status == RegistrationStatus.REVOKED
        if (revoked && incoming.status != RegistrationStatus.REVOKED) {
            record(AuditAction.DEVICE_REVOCATION_KEPT, existing.uid, existing.deviceId, existing.deviceId.value, now) {
                put("status", incoming.status.name)
            }
        }
        val keySource = if (pinnedKey != null) existing else incoming
        return incoming.copy(
            uid = existing.uid,
            role = existing.role,
            kidId = existing.kidId,
            publicKey = existing.publicKey,
            status = if (revoked) RegistrationStatus.REVOKED else incoming.status,
            revokedAtLamport = if (revoked) keptRevocation(existing, incoming) else incoming.revokedAtLamport,
            encryptionKey = keySource.encryptionKey,
            encryptionKeySignature = keySource.encryptionKeySignature,
            encryptionKeyHouseholdId = keySource.encryptionKeyHouseholdId,
            encryptionKeyConflict = existing.encryptionKeyConflict || changed != null,
        )
    }

    /**
     * What [incoming] tries to change of what [existing] pins, as the `key` of the `DEVICE_KEY_CHANGED` audit row:
     * the signing key, the verified encryption key ([encryptionKeyChanged]) or the owner (`uid`, `role`, `kidId`,
     * audit I1); null when nothing pinned changes.
     */
    private fun changedPin(existing: DeviceEntity, incoming: DeviceEntity, encryptionKeyChanged: Boolean): String? =
        when {
            !incoming.publicKey.contentEquals(existing.publicKey) -> PUBLIC_KEY
            encryptionKeyChanged -> "encryptionKey"
            ownerOf(incoming) != ownerOf(existing) -> "owner"
            else -> null
        }

    /** A revoked device's revocation lamport: never moved up (that would admit more of its writes). */
    private fun keptRevocation(existing: DeviceEntity, incoming: DeviceEntity): Long? {
        val stored = existing.revokedAtLamport
        val claimed = incoming.revokedAtLamport.takeIf { incoming.status == RegistrationStatus.REVOKED }
        return if (stored == null || claimed == null) stored ?: claimed else minOf(stored, claimed)
    }

    override suspend fun applyDocuments(documents: List<DocumentEnvelope>): DocumentApplyReport =
        rebuildingOnCapChange {
            deviceClock.withLock {
                database.withTransaction {
                    val session = database.sessionDao().get()?.toDomain()
                    if (session == null) {
                        DocumentApplyReport(ignored = documents.size)
                    } else {
                        applyAll(documents.sortedBy { DocTypes.applyOrder(it.docType) }, session, fromInbox = false)
                    }
                }
            }
        }

    /**
     * Runs [apply] and then, when it changed the household's synced daily game cap, rebuilds the ledger's caches
     * (D-M7-11): the cap decides which plays count, so the balances follow a pulled policy at once.
     */
    private suspend fun <T> rebuildingOnCapChange(apply: suspend () -> T): T {
        val before = database.householdPolicyDao().get()?.dailyGameCapMinor
        val result = apply()
        if (database.householdPolicyDao().get()?.dailyGameCapMinor != before) ledger.rebuildCachesRetrying()
        return result
    }

    /**
     * Verifies and applies [documents]. [fromInbox] marks a retry of `document_inbox` rows ([retryHeldDocuments]):
     * those are removed once they apply, lose to the stored copy or are refused for good, and stay (with their
     * reason updated) while still held; nothing is audited twice for them.
     */
    private suspend fun applyAll(
        documents: List<DocumentEnvelope>,
        session: Session,
        fromInbox: Boolean,
    ): DocumentApplyReport {
        val verifier = RemoteDocumentVerifier(registry, check, maxLamportJump, session.householdId)
        val now = clock.now().toEpochMilliseconds()
        var localMax = deviceClock.current()
        var report = DocumentApplyReport()
        LazyKeyPair(crypto).use { keys ->
            for (envelope in documents) {
                report += applyOne(envelope, verifier, localMax, session, keys, now, fromInbox)
                localMax = maxOf(localMax, deviceClock.current())
            }
        }
        return report
    }

    @Suppress("LongParameterList") // One verified document and the batch's state.
    private suspend fun applyOne(
        envelope: DocumentEnvelope,
        verifier: RemoteDocumentVerifier,
        localMax: Long,
        session: Session,
        keys: LazyKeyPair,
        now: Long,
        fromInbox: Boolean,
    ): DocumentApplyReport = when (val verified = verifier.verify(envelope, localMax)) {
        // The author device is unknown or pending (D-M3-31): keep it and verify it again later.
        is VerifiedDocument.Held -> {
            holdDocument(envelope, verified.reason, now, fromInbox)
            DocumentApplyReport(held = if (fromInbox) 0 else 1)
        }

        is VerifiedDocument.Rejected -> refused(envelope, verified.reason, now, fromInbox)

        is VerifiedDocument.Ok -> {
            // The verifier bounded the lamport by localMax + maxLamportJump.
            database.observeDeviceLamport(maxOf(localMax, envelope.lamport))
            val outcome = apply(envelope, session, keys)
            settleInbox(envelope, outcome, fromInbox)
            outcome(envelope, outcome, now)
        }
    }

    /**
     * A refused document: audited `DOCUMENT_REJECTED` (a retried inbox row only when it is dropped). A lamport too
     * far ahead may become acceptable once this device's clock catches up, so that document is kept in
     * `document_inbox` and retried; any other refusal is final.
     */
    private suspend fun refused(
        envelope: DocumentEnvelope,
        reason: DocumentRejection,
        now: Long,
        fromInbox: Boolean,
    ): DocumentApplyReport {
        val retryable = reason == DocumentRejection.LAMPORT_TOO_FAR_AHEAD
        when {
            retryable -> holdDocument(envelope, reason, now, fromInbox)
            fromInbox -> database.documentInboxDao().delete(envelope.docType, envelope.docId)
        }
        if (!(fromInbox && retryable)) reject(envelope, reason.name, now)
        return DocumentApplyReport(rejected = mapOf(reason to 1))
    }

    /**
     * Keeps [envelope] in `document_inbox` with [reason]: one row per document, the higher lamport wins (a retried
     * row only has its reason refreshed). A new row may push its author over [MAX_HELD_PER_AUTHOR]; that author's
     * oldest rows are then dropped and audited `HELD_DROPPED`.
     */
    private suspend fun holdDocument(
        envelope: DocumentEnvelope,
        reason: DocumentRejection,
        now: Long,
        fromInbox: Boolean,
    ) {
        val inbox = database.documentInboxDao()
        val existing = inbox.get(envelope.docType, envelope.docId)
        val existingLamport = existing?.let { DocumentJsonCodec.decodeOrNull(it.envelopeJson)?.lamport }
        when {
            fromInbox -> if (existing != null && (existing.reason != reason.name || existing.authorDeviceId == null)) {
                inbox.upsert(existing.copy(reason = reason.name, authorDeviceId = envelope.authorDeviceId))
            }

            existing != null && existingLamport != null && existingLamport > envelope.lamport -> Unit

            else -> {
                inbox.upsert(
                    DocumentInboxEntity(
                        docType = envelope.docType,
                        docId = envelope.docId,
                        envelopeJson = DocumentJsonCodec.encode(envelope),
                        reason = reason.name,
                        receivedAtEpochMs = now,
                        authorDeviceId = envelope.authorDeviceId,
                    ),
                )
                capHeldDocuments(envelope.authorDeviceId, now)
            }
        }
    }

    private suspend fun capHeldDocuments(author: DeviceId, now: Long) {
        val inbox = database.documentInboxDao()
        val excess = inbox.countFor(author) - MAX_HELD_PER_AUTHOR
        if (excess <= 0) return
        for (row in inbox.oldestFor(author, excess)) {
            inbox.delete(row.docType, row.docId)
            record(AuditAction.HELD_DROPPED, LocalTrial.PARENT, author, row.docId, now) {
                put("kind", "document")
                put("docType", row.docType)
                put("reason", row.reason)
            }
        }
    }

    /**
     * After a verified document was resolved: its own inbox row (a retry) goes whatever the outcome, and a held copy
     * of the same document goes once the stored copy beats it, so a newer held copy is never lost to an older one
     * that applied.
     */
    private suspend fun settleInbox(envelope: DocumentEnvelope, outcome: Outcome, fromInbox: Boolean) {
        val inbox = database.documentInboxDao()
        val held = inbox.get(envelope.docType, envelope.docId) ?: return
        val heldEnvelope = DocumentJsonCodec.decodeOrNull(held.envelopeJson)
        val superseded = heldEnvelope == null || heldEnvelope == envelope || (
            outcome == Outcome.APPLIED &&
                ConflictResolver.resolve(StoredDocument.of(envelope), heldEnvelope) ==
                ConflictResolver.Decision.Ignore
            )
        if (fromInbox || superseded) inbox.delete(envelope.docType, envelope.docId)
    }

    /**
     * Verifies the documents kept in `document_inbox` again (the registry or this device's clock may have caught
     * up) and applies those that now pass, in [DocTypes.applyOrder]. A row that no longer decodes is dropped.
     */
    private suspend fun retryHeldDocuments(): DocumentApplyReport = rebuildingOnCapChange { retryHeldDocumentsLocked() }

    private suspend fun retryHeldDocumentsLocked(): DocumentApplyReport = deviceClock.withLock {
        database.withTransaction {
            val session = database.sessionDao().get()?.toDomain()
            val inbox = database.documentInboxDao()
            val rows = inbox.all()
            if (session == null || rows.isEmpty()) {
                DocumentApplyReport()
            } else {
                val envelopes = rows.mapNotNull { row ->
                    DocumentJsonCodec.decodeOrNull(row.envelopeJson)
                        ?.takeIf { it.docType == row.docType && it.docId == row.docId }
                        .also { if (it == null) inbox.delete(row.docType, row.docId) }
                }
                applyAll(envelopes.sortedBy { DocTypes.applyOrder(it.docType) }, session, fromInbox = true)
            }
        }
    }

    private suspend fun outcome(envelope: DocumentEnvelope, outcome: Outcome, now: Long): DocumentApplyReport =
        when (outcome) {
            Outcome.APPLIED -> {
                record(
                    AuditAction.DOCUMENT_APPLIED,
                    envelope.authorUid,
                    envelope.authorDeviceId,
                    envelope.docId,
                    now,
                ) {
                    put("docType", envelope.docType)
                    put("lamport", envelope.lamport)
                    if (envelope.deleted) put("deleted", true)
                }
                DocumentApplyReport(applied = 1)
            }

            Outcome.IGNORED -> DocumentApplyReport(ignored = 1)

            Outcome.MALFORMED -> {
                reject(envelope, MALFORMED_PAYLOAD, now)
                DocumentApplyReport(ignored = 1)
            }

            Outcome.DOWNGRADE -> {
                reject(envelope, POLICY_SIGNATURE_DOWNGRADE, now)
                DocumentApplyReport(ignored = 1)
            }
        }

    /** [DOWNGRADE]: a household policy that would turn required parent signatures off (D-REL-23); never applied. */
    private enum class Outcome { APPLIED, IGNORED, MALFORMED, DOWNGRADE }

    /**
     * Resolves and writes one verified document. Parent keys are registered only by the server (`registerParentKey`,
     * D-M3-34) and reach this device through the registry pull, never as documents.
     */
    private suspend fun apply(envelope: DocumentEnvelope, session: Session, keys: LazyKeyPair): Outcome {
        val fallbackAuthor = session.deviceId
        return when (envelope.docType) {
            DocTypes.THREAD_KEY -> applyThreadKey(envelope, session, keys)
            DocTypes.KID -> applyKid(envelope, fallbackAuthor)
            DocTypes.ACCOUNT -> applyAccount(envelope, fallbackAuthor)
            DocTypes.HOUSEHOLD_POLICY -> applyHouseholdPolicy(envelope, fallbackAuthor)
            DocTypes.CHORE -> applyChore(envelope, fallbackAuthor)
            DocTypes.CHORE_SUBMISSION -> applySubmission(envelope, fallbackAuthor)
            DocTypes.REWARD -> applyReward(envelope, fallbackAuthor)
            DocTypes.ALLOWANCE_SCHEDULE -> applyAllowance(envelope, fallbackAuthor)
            DocTypes.SAVINGS_GOAL -> applyGoal(envelope, fallbackAuthor)
            DocTypes.INTEREST_POLICY -> applyInterest(envelope, fallbackAuthor)
            else -> Outcome.MALFORMED
        }
    }

    private suspend fun applyThreadKey(envelope: DocumentEnvelope, session: Session, keys: LazyKeyPair): Outcome =
        when (chat.applyThreadKey(envelope, session, keys)) {
            ChatApplier.KeyOutcome.APPLIED -> Outcome.APPLIED
            ChatApplier.KeyOutcome.IGNORED -> Outcome.IGNORED
            ChatApplier.KeyOutcome.MALFORMED -> Outcome.MALFORMED
        }

    /**
     * The shared resolve-and-write step: [decoded] is the payload (null when it does not decode), [local] the stored
     * row (tombstones included). A tombstone whose payload does not decode can still delete a stored row.
     */
    @Suppress("LongParameterList") // The row's codec, passed per document type.
    private suspend fun <T : Any, R : Any> resolve(
        envelope: DocumentEnvelope,
        decoded: T?,
        identityMatches: (T) -> Boolean,
        local: R?,
        stored: (R) -> StoredDocument,
        rowOf: (T, R?) -> R,
        restamp: (R, Long, DocumentStamp) -> R,
        write: suspend (R) -> Unit,
    ): Outcome {
        val base = when {
            decoded != null && identityMatches(decoded) -> rowOf(decoded, local)
            decoded == null && envelope.deleted && local != null -> local
            else -> null
        }
        return when {
            base == null -> Outcome.MALFORMED

            ConflictResolver.resolve(local?.let(stored), envelope) == ConflictResolver.Decision.Ignore ->
                Outcome.IGNORED

            else -> {
                val stamp =
                    DocumentStamp(envelope.authorUid, envelope.authorDeviceId, envelope.signature, envelope.deleted)
                write(restamp(base, envelope.lamport, stamp))
                Outcome.APPLIED
            }
        }
    }

    private fun storedOf(lamport: Long, stamp: DocumentStamp, fallback: DeviceId, payload: JsonObject = EMPTY) =
        StoredDocument(lamport, stamp.authorDeviceId ?: fallback, stamp.deleted, payload)

    private suspend fun applyKid(envelope: DocumentEnvelope, fallback: DeviceId): Outcome = resolve(
        envelope,
        DocumentPayloads.decodeOrNull(Kid.serializer(), envelope.payload),
        { it.id.value == envelope.docId && it.householdId == envelope.householdId },
        database.kidDao().get(KidId(envelope.docId)),
        { storedOf(it.lamport, it.stamp, fallback) },
        { kid, _ -> KidEntity.from(kid) },
        { row, lamport, stamp -> row.copy(lamport = lamport, stamp = stamp) },
        { database.kidDao().upsert(it) },
    )

    private suspend fun applyAccount(envelope: DocumentEnvelope, fallback: DeviceId): Outcome {
        val account = DocumentPayloads.decodeOrNull(Account.serializer(), envelope.payload)
        val local = database.accountDao().get(AccountId(envelope.docId))
        // Accounts are never deleted or changed (the ledger refers to them); a second account for the same
        // (kid, currency, kind) under another id would break the unique index and the projection.
        val clash = account?.let { database.accountDao().find(it.kidId, it.currency, it.kind) }
        return when {
            envelope.deleted -> Outcome.IGNORED

            account != null && clash != null && clash.accountId != account.id -> Outcome.IGNORED

            account != null && local != null && local.toDomain() != account -> Outcome.IGNORED

            else -> resolve(
                envelope,
                account,
                { it.id.value == envelope.docId },
                local,
                { storedOf(it.lamport, it.stamp, fallback) },
                { decoded, row ->
                    AccountEntity.from(decoded, row?.createdAtEpochMs ?: clock.now().toEpochMilliseconds())
                        .copy(lastAccruedOn = row?.lastAccruedOn)
                },
                { row, lamport, stamp -> row.copy(lamport = lamport, stamp = stamp) },
                { database.accountDao().upsert(it) },
            )
        }
    }

    /**
     * The household policy. `requireParentSignature` is one-way (D-REL-23, audit L2), as the rules enforce: a pulled
     * policy that would turn it off over a stored policy that has it on is refused ([Outcome.DOWNGRADE], audited
     * `DOCUMENT_REJECTED` with [POLICY_SIGNATURE_DOWNGRADE]) whenever it would otherwise win; an older one is ignored
     * as usual.
     */
    private suspend fun applyHouseholdPolicy(envelope: DocumentEnvelope, fallback: DeviceId): Outcome {
        val decoded = DocumentPayloads.decodeHouseholdPolicy(envelope.payload)
        val stored = database.householdPolicyDao().get()
        val downgrade = decoded != null && stored != null && stored.requireParentSignature &&
            !decoded.requireParentSignature &&
            ConflictResolver.resolve(storedOf(stored.lamport, stored.stamp, fallback), envelope) !=
            ConflictResolver.Decision.Ignore
        return when {
            envelope.deleted -> Outcome.IGNORED
            downgrade -> Outcome.DOWNGRADE
            else -> resolvePolicy(envelope, decoded, stored, fallback)
        }
    }

    /** [applyHouseholdPolicy] once the policy is neither a tombstone nor a downgrade. */
    private suspend fun resolvePolicy(
        envelope: DocumentEnvelope,
        decoded: DocumentPayloads.HouseholdPolicyPayload?,
        stored: HouseholdPolicyEntity?,
        fallback: DeviceId,
    ): Outcome = resolve(
        envelope,
        decoded,
        { envelope.docId == envelope.householdId.value },
        stored,
        { storedOf(it.lamport, it.stamp, fallback) },
        { payload, row ->
            row?.copy(
                fiatCurrency = payload.fiatCurrency,
                requireParentSignature = payload.requireParentSignature,
                chatRetentionDays = payload.chatRetentionDays,
                dailyGameCapMinor = payload.dailyGameCapMinor,
            ) ?: HouseholdPolicyEntity(
                requireParentSignature = payload.requireParentSignature,
                parentSignatureFromLamport = null,
                parentPublicKey = null,
                fiatCurrency = payload.fiatCurrency,
                createdAtEpochMs = clock.now().toEpochMilliseconds(),
                chatRetentionDays = payload.chatRetentionDays,
                dailyGameCapMinor = payload.dailyGameCapMinor,
            )
        },
        { row, lamport, stamp -> row.copy(lamport = lamport, stamp = stamp) },
        { database.householdPolicyDao().upsert(it) },
    )

    private suspend fun applyChore(envelope: DocumentEnvelope, fallback: DeviceId): Outcome = resolve(
        envelope,
        DocumentPayloads.decodeOrNull(Chore.serializer(), envelope.payload),
        { it.id.value == envelope.docId && it.householdId == envelope.householdId },
        database.choreDao().get(ChoreId(envelope.docId)),
        { storedOf(it.lamport, it.stamp, fallback) },
        { chore, _ -> ChoreEntity.from(chore, 0) },
        { row, lamport, stamp -> row.copy(lamport = lamport, stamp = stamp) },
        { database.choreDao().upsert(it) },
    )

    private suspend fun applySubmission(envelope: DocumentEnvelope, fallback: DeviceId): Outcome {
        val submission = DocumentPayloads.decodeOrNull(ChoreSubmission.serializer(), envelope.payload)
        // (choreId, dueOn) is unique and determines the id; another id for the same occurrence is never applied.
        val occupant = submission?.let { database.choreSubmissionDao().forOccurrence(it.choreId, it.dueOn) }
        if (occupant != null && occupant.submissionId != submission.id) return Outcome.IGNORED
        return resolve(
            envelope,
            submission,
            { it.id.value == envelope.docId },
            database.choreSubmissionDao().get(SubmissionId(envelope.docId)),
            { row ->
                storedOf(
                    row.lamport,
                    row.stamp,
                    fallback,
                    DocumentPayloads.encode(ChoreSubmission.serializer(), row.toDomain()),
                )
            },
            { decoded, _ -> ChoreSubmissionEntity.from(decoded, 0) },
            { row, lamport, stamp -> row.copy(lamport = lamport, stamp = stamp) },
            { database.choreSubmissionDao().upsert(it) },
        )
    }

    private suspend fun applyReward(envelope: DocumentEnvelope, fallback: DeviceId): Outcome = resolve(
        envelope,
        DocumentPayloads.decodeOrNull(Reward.serializer(), envelope.payload),
        { it.id.value == envelope.docId && it.householdId == envelope.householdId },
        database.rewardDao().get(RewardId(envelope.docId)),
        { storedOf(it.lamport, it.stamp, fallback) },
        { reward, _ -> RewardEntity.from(reward, 0) },
        { row, lamport, stamp -> row.copy(lamport = lamport, stamp = stamp) },
        { database.rewardDao().upsert(it) },
    )

    private suspend fun applyAllowance(envelope: DocumentEnvelope, fallback: DeviceId): Outcome = resolve(
        envelope,
        DocumentPayloads.decodeOrNull(AllowanceSchedule.serializer(), envelope.payload),
        { it.id.value == envelope.docId },
        database.allowanceScheduleDao().get(ScheduleId(envelope.docId)),
        { storedOf(it.lamport, it.stamp, fallback) },
        { schedule, row -> AllowanceScheduleEntity.from(schedule, row?.lastPaidOn, 0) },
        { row, lamport, stamp -> row.copy(lamport = lamport, stamp = stamp) },
        { database.allowanceScheduleDao().upsert(it) },
    )

    private suspend fun applyGoal(envelope: DocumentEnvelope, fallback: DeviceId): Outcome = resolve(
        envelope,
        DocumentPayloads.decodeOrNull(SavingsGoal.serializer(), envelope.payload),
        { it.id.value == envelope.docId },
        database.savingsGoalDao().get(GoalId(envelope.docId)),
        { storedOf(it.lamport, it.stamp, fallback) },
        { goal, _ -> SavingsGoalEntity.from(goal, 0) },
        { row, lamport, stamp -> row.copy(lamport = lamport, stamp = stamp) },
        { database.savingsGoalDao().upsert(it) },
    )

    private suspend fun applyInterest(envelope: DocumentEnvelope, fallback: DeviceId): Outcome = resolve(
        envelope,
        DocumentPayloads.decodeOrNull(InterestPolicy.serializer(), envelope.payload),
        { it.householdId.value == envelope.docId && it.householdId == envelope.householdId },
        database.interestPolicyDao().get(HouseholdId(envelope.docId)),
        { storedOf(it.lamport, it.stamp, fallback) },
        { policy, _ -> InterestPolicyEntity.from(policy, 0) },
        { row, lamport, stamp -> row.copy(lamport = lamport, stamp = stamp) },
        { database.interestPolicyDao().upsert(it) },
    )

    override suspend fun applyEvents(events: List<PulledEvent>): EventApplyReport = ledger.ingest(events)

    /** Verifies and stores pulled chat messages with their server stamps (D-M7-80); see [ChatApplier]. */
    override suspend fun applyMessages(messages: List<JsonObject>, stamps: Map<String, Instant>): MessageApplyReport =
        chat.applyMessages(messages, stamps)

    /** Remembers the household's chat prune watermark; see [ChatApplier.applyChatPrune]. */
    override suspend fun applyChatPrune(prunedBefore: Instant) = chat.applyChatPrune(prunedBefore)

    override suspend fun expectedSeq(deviceId: DeviceId, threadId: ThreadId): Long =
        chat.expectedSeq(deviceId, threadId)

    /**
     * Retries held documents first (an account, kid or thread key they bring may be what a held event or message
     * waits for), then held messages ([ChatApplier.retryHeld]), then held events ([RoomLedgerRepository.retryHeld]).
     * The report is the events'; documents and messages only show in the audit log and their tables.
     */
    override suspend fun retryHeld(): EventApplyReport {
        retryHeldDocuments()
        chat.retryHeld()
        return ledger.retryHeld()
    }

    /** The session household's positions; empty without a real session (nothing to resume). */
    override suspend fun cursor(): SyncCursor {
        val household = syncedHousehold() ?: return SyncCursor(emptyMap())
        return SyncCursor(
            database.syncCursorDao().all(household)
                .filter { it.collection != ChatApplier.CHAT_PRUNED_BEFORE }
                .associate { it.collection to it.cursor },
        )
    }

    /** Saved under the session's household; dropped without a real session. */
    override suspend fun saveCursor(cursor: SyncCursor) {
        val household = syncedHousehold() ?: return
        database.syncCursorDao().upsert(
            cursor.perCollection.map { (collection, position) ->
                SyncCursorEntity(household, collection, position)
            },
        )
    }

    private suspend fun syncedHousehold(): HouseholdId? =
        database.sessionDao().get()?.householdId?.takeUnless { it == LocalTrial.HOUSEHOLD }

    override suspend fun localMaxLamport(): Long = ledger.localMaxLamport()

    /** A `DOCUMENT_REJECTED` audit row: the reason code and ids only, never the payload. */
    private suspend fun reject(envelope: DocumentEnvelope, reason: String, now: Long) {
        record(AuditAction.DOCUMENT_REJECTED, envelope.authorUid, envelope.authorDeviceId, envelope.docId, now) {
            put("docType", envelope.docType)
            put("lamport", envelope.lamport)
            put("reason", reason)
        }
    }

    @Suppress("LongParameterList") // The audit row's fields.
    private suspend fun record(
        action: AuditAction,
        actorUid: UserId,
        deviceId: DeviceId,
        target: String,
        now: Long,
        details: JsonObjectBuilder.() -> Unit,
    ) {
        audit.insert(
            AuditLogEntity(
                atEpochMs = now,
                actorUid = actorUid,
                deviceId = deviceId,
                action = action,
                target = target,
                detailsJson = LedgerJson.json.encodeToString(JsonObject.serializer(), buildJsonObject(details)),
            ),
        )
    }

    companion object {
        /** Most rows `document_inbox` keeps per author device; beyond it the oldest are dropped (audited). */
        const val MAX_HELD_PER_AUTHOR: Int = RoomLedgerRepository.MAX_HELD_PER_AUTHOR

        /** Audit reason for a payload that does not decode or whose ids disagree with its envelope. */
        const val MALFORMED_PAYLOAD: String = "MALFORMED_PAYLOAD"

        /**
         * The audited reason for a pulled household policy that would turn `requireParentSignature` off over a stored
         * policy that has it on (D-REL-23): the rules refuse that write, so only a forged or stale copy carries it.
         */
        const val POLICY_SIGNATURE_DOWNGRADE: String = "POLICY_SIGNATURE_DOWNGRADE"

        private val EMPTY = JsonObject(emptyMap())

        /** The owner fields of a device row (pinned, audit I1). */
        private fun ownerOf(device: DeviceEntity) = Triple(device.uid, device.role, device.kidId)

        /** `DEVICE_KEY_CHANGED` key of a changed signing key. */
        private const val PUBLIC_KEY = "publicKey"
    }
}

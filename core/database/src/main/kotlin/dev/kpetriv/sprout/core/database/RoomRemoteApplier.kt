package dev.kpetriv.sprout.core.database

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.database.entity.AccountEntity
import dev.kpetriv.sprout.core.database.entity.AllowanceScheduleEntity
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.AuditLogEntity
import dev.kpetriv.sprout.core.database.entity.ChoreEntity
import dev.kpetriv.sprout.core.database.entity.ChoreSubmissionEntity
import dev.kpetriv.sprout.core.database.entity.DocumentInboxEntity
import dev.kpetriv.sprout.core.database.entity.DocumentStamp
import dev.kpetriv.sprout.core.database.entity.HouseholdPolicyEntity
import dev.kpetriv.sprout.core.database.entity.InterestPolicyEntity
import dev.kpetriv.sprout.core.database.entity.KidEntity
import dev.kpetriv.sprout.core.database.entity.ParentKeyEntity
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
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.sync.ConflictResolver
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.sync.DocumentApplyReport
import dev.kpetriv.sprout.core.sync.DocumentEnvelope
import dev.kpetriv.sprout.core.sync.DocumentJsonCodec
import dev.kpetriv.sprout.core.sync.DocumentRejection
import dev.kpetriv.sprout.core.sync.EventApplyReport
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

/**
 * [RemoteApplier] on Room: what a pull brings is verified and written to the same tables local writes use.
 *
 * - [applyRegistry] upserts the registry mirror (`devices`, `parent_keys`), audited `DEVICE_REGISTERED` /
 *   `DEVICE_REVOKED`, and refreshes the session's device status when this device's entry changed.
 * - [applyDocuments] verifies every envelope with [RemoteDocumentVerifier] (registry, [check], the default
 *   `maxLamportJump`) and resolves it with [ConflictResolver] against the stored row, in [DocTypes.applyOrder] (kid,
 *   account, household_policy, parent_key, then the rest), all in one transaction. An applied document upserts its row
 *   from the payload (the same entity mappers local writes use) with the envelope's lamport, author, signature and
 *   tombstone flag; a tombstone keeps the row with `deleted = 1`, which every read hides and no later update can
 *   resurrect. The device clock observes every verified lamport. Refused documents get a `DOCUMENT_REJECTED` audit
 *   row (reason and ids only) and, for `LAMPORT_TOO_FAR_AHEAD`, a `document_inbox` row. Documents of another household
 *   and payloads that do not decode or whose ids disagree with the envelope are ignored and audited the same way.
 *   Kept device-local: an allowance schedule's `lastPaidOn`, an account's `lastAccruedOn`, this device's parent key.
 * - [applyEvents] and [retryHeld] are [RoomLedgerRepository.ingest] and [RoomLedgerRepository.retryHeld].
 * - [cursor] and [saveCursor] use `sync_cursor`.
 */
@Suppress("TooManyFunctions", "LargeClass") // One apply function per document type.
class RoomRemoteApplier internal constructor(
    private val database: SproutDatabase,
    private val ledger: RoomLedgerRepository,
    private val clock: Clock,
    private val check: SignatureCheck,
    private val maxLamportJump: Long,
) : RemoteApplier {
    @Inject
    constructor(database: SproutDatabase, ledger: RoomLedgerRepository, clock: Clock, check: SignatureCheck) :
        this(database, ledger, clock, check, LedgerPolicy.DEFAULT_MAX_LAMPORT_JUMP)

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
        }
    }

    private suspend fun applyDevice(device: RegisteredDevice, session: SessionEntity?, now: Long) {
        val existing = database.deviceDao().get(device.deviceId)
        val entity = device.toEntity(existing?.registeredAtEpochMs ?: now)
        database.deviceDao().upsert(entity)
        val action = when {
            existing == null -> AuditAction.DEVICE_REGISTERED

            entity.status == RegistrationStatus.REVOKED && existing.status != RegistrationStatus.REVOKED ->
                AuditAction.DEVICE_REVOKED

            else -> null
        }
        if (action != null) {
            record(action, device.uid, device.deviceId, device.deviceId.value, now) {
                put("role", device.role.name)
                put("status", entity.status.name)
                put("attestation", device.attestation.name)
            }
        }
        if (session != null && session.deviceId == device.deviceId && session.deviceStatus != entity.status) {
            database.sessionDao().upsert(session.copy(deviceStatus = entity.status))
        }
    }

    override suspend fun applyDocuments(documents: List<DocumentEnvelope>): DocumentApplyReport = deviceClock.withLock {
        database.withTransaction {
            val session = database.sessionDao().get()?.toDomain()
            if (session == null) {
                DocumentApplyReport(ignored = documents.size)
            } else {
                applyAll(documents.sortedBy { DocTypes.applyOrder(it.docType) }, session)
            }
        }
    }

    private suspend fun applyAll(documents: List<DocumentEnvelope>, session: Session): DocumentApplyReport {
        val verifier = RemoteDocumentVerifier(registry, check, maxLamportJump)
        val now = clock.now().toEpochMilliseconds()
        var localMax = deviceClock.current()
        var report = DocumentApplyReport()
        for (envelope in documents) {
            if (envelope.householdId != session.householdId) {
                reject(envelope, WRONG_HOUSEHOLD, now)
                report += DocumentApplyReport(ignored = 1)
                continue
            }
            report += when (val verified = verifier.verify(envelope, localMax)) {
                is VerifiedDocument.Rejected -> {
                    reject(envelope, verified.reason.name, now)
                    if (verified.reason == DocumentRejection.LAMPORT_TOO_FAR_AHEAD) {
                        database.documentInboxDao().upsert(
                            DocumentInboxEntity(
                                docType = envelope.docType,
                                docId = envelope.docId,
                                envelopeJson = DocumentJsonCodec.encode(envelope),
                                reason = verified.reason.name,
                                receivedAtEpochMs = now,
                            ),
                        )
                    }
                    DocumentApplyReport(rejected = mapOf(verified.reason to 1))
                }

                is VerifiedDocument.Ok -> {
                    // The verifier bounded the lamport by localMax + maxLamportJump.
                    localMax = maxOf(localMax, envelope.lamport)
                    database.observeDeviceLamport(localMax)
                    outcome(envelope, apply(envelope, session), now)
                }
            }
        }
        return report
    }

    private suspend fun outcome(envelope: DocumentEnvelope, outcome: Outcome, now: Long): DocumentApplyReport =
        when (outcome) {
            Outcome.APPLIED -> {
                database.documentInboxDao().delete(envelope.docType, envelope.docId)
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
        }

    private enum class Outcome { APPLIED, IGNORED, MALFORMED }

    /** Resolves and writes one verified document. */
    private suspend fun apply(envelope: DocumentEnvelope, session: Session): Outcome {
        val fallbackAuthor = session.deviceId
        return when (envelope.docType) {
            DocTypes.KID -> applyKid(envelope, fallbackAuthor)
            DocTypes.ACCOUNT -> applyAccount(envelope, fallbackAuthor)
            DocTypes.HOUSEHOLD_POLICY -> applyHouseholdPolicy(envelope, fallbackAuthor)
            DocTypes.PARENT_KEY -> applyParentKey(envelope)
            DocTypes.CHORE -> applyChore(envelope, fallbackAuthor)
            DocTypes.CHORE_SUBMISSION -> applySubmission(envelope, fallbackAuthor)
            DocTypes.REWARD -> applyReward(envelope, fallbackAuthor)
            DocTypes.ALLOWANCE_SCHEDULE -> applyAllowance(envelope, fallbackAuthor)
            DocTypes.SAVINGS_GOAL -> applyGoal(envelope, fallbackAuthor)
            DocTypes.INTEREST_POLICY -> applyInterest(envelope, fallbackAuthor)
            else -> Outcome.MALFORMED
        }
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

    private suspend fun applyHouseholdPolicy(envelope: DocumentEnvelope, fallback: DeviceId): Outcome {
        if (envelope.deleted) return Outcome.IGNORED
        return resolve(
            envelope,
            DocumentPayloads.decodeHouseholdPolicy(envelope.payload),
            { envelope.docId == envelope.householdId.value },
            database.householdPolicyDao().get(),
            { storedOf(it.lamport, it.stamp, fallback) },
            { decoded, row ->
                row?.copy(fiatCurrency = decoded.fiatCurrency, requireParentSignature = decoded.requireParentSignature)
                    ?: HouseholdPolicyEntity(
                        requireParentSignature = decoded.requireParentSignature,
                        parentSignatureFromLamport = null,
                        parentPublicKey = null,
                        fiatCurrency = decoded.fiatCurrency,
                        createdAtEpochMs = clock.now().toEpochMilliseconds(),
                    )
            },
            { row, lamport, stamp -> row.copy(lamport = lamport, stamp = stamp) },
            { database.householdPolicyDao().upsert(it) },
        )
    }

    /** Parent keys are immutable: the first verified copy is kept; revocation comes from the registry only. */
    private suspend fun applyParentKey(envelope: DocumentEnvelope): Outcome {
        val key = DocumentPayloads.decodeParentKey(envelope.payload)
        return when {
            envelope.deleted -> Outcome.IGNORED

            key == null || key.fingerprint != envelope.docId ||
                key.fingerprint != JvmSha256Hasher.sha256(key.publicKey).toHex() ||
                key.deviceId != envelope.authorDeviceId || key.uid != envelope.authorUid -> Outcome.MALFORMED

            database.parentKeyDao().get(key.fingerprint) != null -> Outcome.IGNORED

            else -> {
                database.parentKeyDao().upsert(
                    ParentKeyEntity(
                        fingerprint = key.fingerprint,
                        publicKey = key.publicKey,
                        uid = key.uid,
                        deviceId = key.deviceId,
                        validFromLamport = key.validFromLamport,
                        revokedAtLamport = null,
                        registeredAtEpochMs = clock.now().toEpochMilliseconds(),
                    ),
                )
                Outcome.APPLIED
            }
        }
    }

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

    override suspend fun applyEvents(events: List<JsonObject>): EventApplyReport = ledger.ingest(events)

    override suspend fun retryHeld(): EventApplyReport = ledger.retryHeld()

    override suspend fun cursor(): SyncCursor =
        SyncCursor(database.syncCursorDao().all().associate { it.collection to it.cursor })

    override suspend fun saveCursor(cursor: SyncCursor) {
        database.syncCursorDao().upsert(
            cursor.perCollection.map { (collection, position) ->
                SyncCursorEntity(collection, position)
            },
        )
    }

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
        /** Audit reason for a document of another household. */
        const val WRONG_HOUSEHOLD: String = "WRONG_HOUSEHOLD"

        /** Audit reason for a payload that does not decode or whose ids disagree with its envelope. */
        const val MALFORMED_PAYLOAD: String = "MALFORMED_PAYLOAD"

        private val EMPTY = JsonObject(emptyMap())
    }
}

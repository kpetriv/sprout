package dev.kpetriv.sprout.core.database

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.crypto.Verifier
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.AuditLogEntity
import dev.kpetriv.sprout.core.database.entity.BalanceEntity
import dev.kpetriv.sprout.core.database.entity.EventOrigin
import dev.kpetriv.sprout.core.database.entity.LedgerEventEntity
import dev.kpetriv.sprout.core.database.entity.OutboxEntity
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.Balance
import dev.kpetriv.sprout.core.domainledger.ChainVerification
import dev.kpetriv.sprout.core.domainledger.HashChain
import dev.kpetriv.sprout.core.domainledger.Ledger
import dev.kpetriv.sprout.core.domainledger.LedgerAnomaly
import dev.kpetriv.sprout.core.domainledger.LedgerEvent
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.domainledger.LedgerPolicy
import dev.kpetriv.sprout.core.domainledger.LedgerRepository
import dev.kpetriv.sprout.core.domainledger.LedgerRules
import dev.kpetriv.sprout.core.domainledger.MovesValue
import dev.kpetriv.sprout.core.domainledger.Projection
import dev.kpetriv.sprout.core.domainledger.ValidationResult
import dev.kpetriv.sprout.core.domainledger.Violation
import dev.kpetriv.sprout.core.domainledger.anomaliesFor
import dev.kpetriv.sprout.core.domainledger.signedPayload
import dev.kpetriv.sprout.core.domainledger.signedPayloadOf
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * [LedgerRepository] on Room. [append] does everything in one transaction: the event, the balances and anomalies
 * caches, the outbox row and the audit row are written together or not at all.
 *
 * **Local events only.** [append] accepts an event only if it was authored by this device (its `authorDeviceId` is
 * this device key's fingerprint) and carries a valid signature by that key over its signed payload; anything else
 * is [AppendResult.Rejected] with [FOREIGN_REJECTION]. Events pulled from other devices will enter through a
 * separate `ingest` entry point in M3, which verifies them against the household's registered device keys and
 * stores failures as quarantined rows instead of rejecting them.
 *
 * **Projection.** Every append folds the whole household's verified events (all kids) with [Ledger.project], so an
 * eventId reused across kids is still a conflicting duplicate, then filters per kid ([anomaliesFor]) into the
 * caches. That is linear in the household's history, which is fine at M1 volumes (hundreds of events). The
 * optimisation path (ADR-0002, "Projection cost") is to keep the folded state, apply new events incrementally when
 * [Projection.isInOrder] holds, and only rebuild for late events or conflicts.
 *
 * **Verification on read.** Loading for a projection runs [quarantineUnverifiable]'s check on every row: the stored
 * document must decode and match its hash (computed from the stored JSON tree), and a LOCAL row must still carry
 * this device's valid signature. A row that fails is marked quarantined (a write, so never inside a read [Flow]) and
 * the caches are rebuilt without it. Read flows skip rows that fail the same checks and never throw.
 *
 * @param localEvents decides what counts as this device's own, signed event.
 * @param policy household-tunable limits; the defaults until settings exist.
 */
class RoomLedgerRepository internal constructor(
    private val database: SproutDatabase,
    private val clock: Clock,
    private val localEvents: LocalEventCheck,
    private val policy: LedgerPolicy,
) : LedgerRepository {
    @Inject
    constructor(database: SproutDatabase, clock: Clock, signer: DeviceSigner, verifier: Verifier) :
        this(database, clock, DeviceKeyEventCheck(signer, verifier), LedgerPolicy())

    private val events = database.ledgerEventDao()
    private val accounts = database.accountDao()
    private val balances = database.balanceDao()
    private val anomalies = database.anomalyDao()
    private val outbox = database.outboxDao()
    private val audit = database.auditLogDao()
    private val chain = HashChain()

    override fun observeBalances(kidId: KidId): Flow<List<Balance>> =
        balances.observeForKid(kidId).map { rows -> rows.map(BalanceEntity::toDomain) }

    override fun observeAnomalies(kidId: KidId): Flow<List<LedgerAnomaly>> =
        anomalies.observeForKid(kidId).map(AnomalyMapper::toDomain)

    override fun observeEvents(kidId: KidId, limit: Int): Flow<List<LedgerEvent>> =
        events.observeNewestForKid(kidId, limit).map { rows -> rows.mapNotNull(LedgerEventMapper::toDomain) }

    override suspend fun append(event: LedgerEvent): AppendResult {
        val check = bindLocalCheck()
        return database.withTransaction { appendLocked(event, check) }
    }

    override suspend fun latestLamport(): Long = events.maxLamport()

    override suspend fun headHash(authorDeviceId: DeviceId): String? = events.headHash(authorDeviceId)

    /**
     * Maintenance: quarantines every stored row that no longer verifies (see the class KDoc) and rebuilds the caches
     * if any was found. Returns how many rows were quarantined. Safe to run at any time; the app runs it at startup.
     */
    suspend fun quarantineUnverifiable(): Int {
        val check = bindLocalCheck()
        return database.withTransaction { loadVerified(check).quarantined }
    }

    /** Binds the local-event check to this device's key; a Keystore read, so done outside any transaction. */
    internal suspend fun bindLocalCheck(): LocalEventCheck.Bound = localEvents.bind()

    /** The household projection from stored, verified events; for tests. */
    internal suspend fun project(): Projection = database.withTransaction {
        project(loadVerified(localEvents.bind()).events)
    }

    /** The body of [append]; must run inside a Room transaction. [check] comes from [bindLocalCheck]. */
    internal suspend fun appendLocked(event: LedgerEvent, check: LocalEventCheck.Bound): AppendResult = when {
        !isOwnSignedEvent(event, check) -> AppendResult.Rejected(FOREIGN_REJECTION)

        events.copiesOf(event.eventId).any { it.hash == event.envelope.hash } -> AppendResult.Duplicate

        // Verifying (and quarantining) first means the chain head below ignores rows that no longer verify.
        else -> appendNew(event, loadVerified(check).events)
    }

    private suspend fun appendNew(event: LedgerEvent, stored: List<LedgerEvent>): AppendResult {
        if (!linksToStoredHead(event)) return AppendResult.Rejected(CHAIN_REJECTION)
        val before = project(stored)
        val storedCopies = events.copiesOf(event.eventId).filterNot { it.quarantined }.map { it.hash }
        return if (storedCopies.isEmpty()) {
            validateAndStore(event, stored, before)
        } else {
            storeConflict(event, storedCopies.first(), stored, before)
        }
    }

    private fun isOwnSignedEvent(event: LedgerEvent, check: LocalEventCheck.Bound): Boolean =
        payloadOrNull { event.signedPayload() }?.let { check.accepts(event, it) } == true

    /** [payload], or null if the event has no canonical form (malformed tree or an unpaired surrogate). */
    private inline fun payloadOrNull(payload: () -> ByteArray): ByteArray? = try {
        payload()
    } catch (expected: IllegalArgumentException) {
        null
    } catch (expected: CharacterCodingException) {
        null
    }

    private class Loaded(val events: List<LedgerEvent>, val quarantined: Int)

    /**
     * Every non-quarantined row that still verifies. Rows that do not are quarantined here, and the caches are
     * rebuilt from what is left, so nothing derived from them survives.
     */
    private suspend fun loadVerified(check: LocalEventCheck.Bound): Loaded {
        val verified = ArrayList<LedgerEvent>()
        var quarantined = 0
        for (row in events.allVerified()) {
            val reason = when (val read = LedgerEventMapper.verify(row)) {
                is LedgerEventMapper.Read.Failed -> read.reason

                is LedgerEventMapper.Read.Verified ->
                    signatureFailure(row, read.tree, read.event, check).also { if (it == null) verified += read.event }
            }
            if (reason != null) {
                events.quarantine(row.eventId, row.hash, reason)
                quarantined += 1
            }
        }
        if (quarantined > 0) refreshCaches(project(verified), verified)
        return Loaded(verified, quarantined)
    }

    /** LOCAL rows must still be this device's signed events; pulled rows are verified by sync (M3). */
    private fun signatureFailure(
        row: LedgerEventEntity,
        tree: JsonObject,
        event: LedgerEvent,
        check: LocalEventCheck.Bound,
    ): String? {
        if (row.origin != EventOrigin.LOCAL) return null
        val payload = payloadOrNull { signedPayloadOf(tree) }
        return when {
            payload == null -> LedgerEventMapper.UNDECODABLE
            check.accepts(event, payload) -> null
            else -> LedgerEventMapper.SIGNATURE_INVALID
        }
    }

    private suspend fun project(stored: List<LedgerEvent>): Projection =
        Ledger.project(stored, accounts.all().map { it.toDomain() }, policy)

    /**
     * True when [event] continues its author's stored chain: it links to the head, its hash is right, and its
     * lamport is above every stored one of that author (so the head stays the highest-lamport event).
     */
    private suspend fun linksToStoredHead(event: LedgerEvent): Boolean {
        val author = event.envelope.authorDeviceId
        return chain.verifyChain(
            events = listOf(event),
            anchorHash = events.headHash(author),
            anchorLamport = events.maxLamportFor(author),
        ) is ChainVerification.Valid
    }

    /** A new eventId: every [LedgerRules] invariant, including the lamport bound against the household. */
    private suspend fun validateAndStore(
        event: LedgerEvent,
        stored: List<LedgerEvent>,
        before: Projection,
    ): AppendResult {
        val validation = LedgerRules.validate(event, before)
        if (validation is ValidationResult.Invalid) {
            return AppendResult.Rejected(INVARIANT_REJECTION, validation.violations)
        }
        store(event, stored, before, AuditAction.LEDGER_EVENT_APPENDED)
        return AppendResult.Accepted
    }

    /**
     * A different event under a stored eventId. It chained, so it is kept next to the stored copy and the projection
     * decides which copy, if any, applies. Only the lamport bound is checked here: the other rules are applied to
     * whichever copy the projection folds, and a copy breaking them shows up as an anomaly.
     */
    private suspend fun storeConflict(
        event: LedgerEvent,
        storedHash: String,
        stored: List<LedgerEvent>,
        before: Projection,
    ): AppendResult {
        val maxJump = policy.maxLamportJump
        // Both lamports are non-negative, so the subtraction cannot overflow.
        if (event.lamport - before.maxLamport > maxJump) {
            val violation = Violation.LamportTooFarAhead(before.maxLamport, event.lamport, maxJump)
            return AppendResult.Rejected(INVARIANT_REJECTION, listOf(violation))
        }
        store(event, stored, before, AuditAction.LEDGER_EVENT_CONFLICT)
        return AppendResult.Conflict(event.eventId, storedHash = storedHash, incomingHash = event.envelope.hash)
    }

    /** Writes [event] and everything derived from it: caches, outbox and audit. */
    private suspend fun store(event: LedgerEvent, stored: List<LedgerEvent>, before: Projection, action: AuditAction) {
        val envelope = event.envelope
        val now = clock.now().toEpochMilliseconds()
        val row = LedgerEventMapper.toEntity(event, EventOrigin.LOCAL, receivedAtEpochMs = now)
        events.insert(row)
        refreshCaches(before.apply(event), stored + event)

        outbox.insert(
            OutboxEntity(
                kind = OutboxKind.LEDGER_EVENT,
                refId = "${envelope.eventId.value}:${envelope.hash}",
                payload = row.payloadJson,
                createdAtEpochMs = now,
            ),
        )
        audit.insert(
            AuditLogEntity(
                atEpochMs = now,
                actorUid = envelope.authorUid,
                deviceId = envelope.authorDeviceId,
                action = action,
                target = envelope.eventId.value,
                detailsJson = auditDetails(event, row.type),
            ),
        )
    }

    /**
     * Replaces the balances and anomalies caches with [projection] (folded from [folded]): balances of registered
     * accounts, and each kid's anomalies ([anomaliesFor]) for every kid with an account or named by an event.
     */
    private suspend fun refreshCaches(projection: Projection, folded: List<LedgerEvent>) {
        val now = clock.now().toEpochMilliseconds()
        balances.replaceAll(
            projection.balances.values.mapNotNull { balance ->
                projection.accounts[balance.accountId]?.let { BalanceEntity.from(it.kidId, balance) }
            },
        )
        val kids = projection.accounts.values.map { it.kidId }.toSet() + folded.map { it.envelope.kidId }
        val known = anomalies.all().associate { it.anomalyId to it.detectedAtEpochMs }
        anomalies.replaceAll(
            kids.flatMap { kidId ->
                projection.anomaliesFor(kidId).mapIndexed { order, anomaly ->
                    val entity = AnomalyMapper.toEntity(kidId, anomaly, order, now)
                    known[entity.anomalyId]?.let { entity.copy(detectedAtEpochMs = it) } ?: entity
                }
            },
        )
    }

    /** Ids, type and amount only: free text such as an adjustment reason never reaches the audit log. */
    private fun auditDetails(event: LedgerEvent, type: String): String {
        val details = buildJsonObject {
            put("type", type)
            put("kidId", event.envelope.kidId.value)
            put("accountId", event.envelope.accountId.value)
            put("lamport", event.lamport)
            put("role", event.envelope.authorRole.name)
            if (event is MovesValue) put("amountMinor", event.amount.minorUnits)
        }
        return LedgerJson.json.encodeToString(JsonObject.serializer(), details)
    }

    companion object {
        /** [AppendResult.Rejected.reason] when the event does not link to its author's stored chain head. */
        const val CHAIN_REJECTION: String = "chain verification failed"

        /** [AppendResult.Rejected.reason] when [LedgerRules] rejects the event. */
        const val INVARIANT_REJECTION: String = "invariant violated"

        /** [AppendResult.Rejected.reason] when the event is not this device's own, validly signed event. */
        const val FOREIGN_REJECTION: String = "unsigned or foreign event"
    }
}

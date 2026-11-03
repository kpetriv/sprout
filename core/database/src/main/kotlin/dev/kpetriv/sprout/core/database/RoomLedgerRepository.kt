package dev.kpetriv.sprout.core.database

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.crypto.JceEcdsaVerifier
import dev.kpetriv.sprout.core.crypto.Verifier
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.AuditLogEntity
import dev.kpetriv.sprout.core.database.entity.BalanceEntity
import dev.kpetriv.sprout.core.database.entity.EventOrigin
import dev.kpetriv.sprout.core.database.entity.LedgerEventEntity
import dev.kpetriv.sprout.core.database.entity.LedgerInboxEntity
import dev.kpetriv.sprout.core.database.entity.OutboxEntity
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.Balance
import dev.kpetriv.sprout.core.domainledger.ChainVerification
import dev.kpetriv.sprout.core.domainledger.DeviceRegistry
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.HashChain
import dev.kpetriv.sprout.core.domainledger.IngestOrdering
import dev.kpetriv.sprout.core.domainledger.Ledger
import dev.kpetriv.sprout.core.domainledger.LedgerAnomaly
import dev.kpetriv.sprout.core.domainledger.LedgerEvent
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.domainledger.LedgerPolicy
import dev.kpetriv.sprout.core.domainledger.LedgerRepository
import dev.kpetriv.sprout.core.domainledger.LedgerRules
import dev.kpetriv.sprout.core.domainledger.MovesValue
import dev.kpetriv.sprout.core.domainledger.Projection
import dev.kpetriv.sprout.core.domainledger.QuarantineReason
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.RemoteEventVerifier
import dev.kpetriv.sprout.core.domainledger.RewardPurchased
import dev.kpetriv.sprout.core.domainledger.SignatureCheck
import dev.kpetriv.sprout.core.domainledger.StreamHead
import dev.kpetriv.sprout.core.domainledger.ValidationResult
import dev.kpetriv.sprout.core.domainledger.VerifiedRemote
import dev.kpetriv.sprout.core.domainledger.Violation
import dev.kpetriv.sprout.core.domainledger.anomaliesFor
import dev.kpetriv.sprout.core.domainledger.parentSignatureBytes
import dev.kpetriv.sprout.core.domainledger.parentSignedPayload
import dev.kpetriv.sprout.core.domainledger.requiresParentSignature
import dev.kpetriv.sprout.core.domainledger.signatureBytes
import dev.kpetriv.sprout.core.domainledger.signedPayload
import dev.kpetriv.sprout.core.domainledger.signedPayloadOf
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.sync.EventApplyReport
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import kotlin.time.Instant

/**
 * [LedgerRepository] on Room. [append] does everything in one transaction: the event, the balances and anomalies
 * caches, the outbox row and the audit row are written together or not at all.
 *
 * **Local events.** [append] accepts an event only if it was authored by this device (its `authorDeviceId` is
 * this device key's fingerprint) and carries a valid signature by that key over its signed payload; anything else
 * is [AppendResult.Rejected] with [FOREIGN_REJECTION].
 *
 * **Remote events (M3 §2).** [ingest] takes pulled event documents: each is checked by [RemoteEventVerifier] against
 * the device registry ([DeviceRegistry]), events already stored are counted as duplicates, the rest are ordered per
 * author with [IngestOrdering] from the stored chain heads (held events from `ledger_inbox` are planned again with
 * them), and each applicable event is validated against the projection before it. See [ingest] for what is stored.
 * Remote rows never get an outbox row.
 *
 * **Projection.** Every append folds the whole household's verified events (all kids) with [Ledger.project], so an
 * eventId reused across kids is still a conflicting duplicate, then filters per kid ([anomaliesFor]) into the
 * caches. That is linear in the household's history, which is fine at M1 volumes (hundreds of events). The
 * optimisation path (ADR-0002, "Projection cost") is to keep the folded state, apply new events incrementally when
 * [Projection.isInOrder] holds, and only rebuild for late events or conflicts. Conflicting copies are resolved with the
 * registry's device roles once any device is registered, and with the envelopes' roles only for the pre-linking
 * local household ([RoleLookup]).
 *
 * **Verification on read.** Loading for a projection runs [quarantineUnverifiable]'s check on every row: the stored
 * document must decode and match its hash (computed from the stored JSON tree), a LOCAL row must still carry this
 * device's valid signature, and a REMOTE row must carry a valid signature by its registered author device (a row
 * whose device the registry does not know is left as it is). A row that fails is marked quarantined (a write, so never
 * inside a read [Flow]) and the caches are rebuilt without it. Read flows skip rows that fail the same checks and
 * never throw.
 *
 * **Policy and parent signatures (M2).** The [LedgerPolicy] comes from `household_policy` on every append
 * ([HouseholdPolicySource]): once a parent key is registered, parent-only events from the registration lamport on
 * must carry a parent signature. Whenever a local event carries a parent signature and a parent key is registered,
 * the signature must verify against that key over [parentSignedPayload] ([PARENT_SIGNATURE_REJECTION] otherwise); a
 * required signature that is missing is [Violation.ParentSignatureMissing]. While **no** parent key is registered a
 * parent signature cannot be verified, so it is dropped before storing and the event is stored (and reported by the
 * use cases) as unsigned. Stored rows are not re-verified against the key on read, because re-registering a new key
 * (after a biometric enrolment change) must not quarantine history signed by the old one; the registry keeps every
 * registered key for remote verification.
 *
 * **Reward catalogue (M2, M3).** A kid-authored [RewardPurchased] appended here must match the catalogue row of its
 * reward (active, available to the kid, same cost, currency and granted minutes, and a parent-authored row: see
 * [RewardTableCatalogue]), or it is [CATALOGUE_REJECTION].
 *
 * @param localEvents decides what counts as this device's own, signed event.
 * @param policies the ledger policy and parent key in force, loaded inside each transaction.
 * @param catalogue checks kid-authored purchases against the reward catalogue.
 * @param registry the household's device registry, for remote events.
 * @param check verifies remote device and parent signatures.
 */
@Suppress("TooManyFunctions", "LargeClass") // The LedgerRepository API, ingest and the data layer's internal reads.
class RoomLedgerRepository internal constructor(
    private val database: SproutDatabase,
    private val clock: Clock,
    private val localEvents: LocalEventCheck,
    private val policies: LedgerPolicySource,
    private val catalogue: PurchaseCatalogue,
    private val registry: DeviceRegistry = RoomDeviceRegistry(database),
    private val check: SignatureCheck = VerifierSignatureCheck(JceEcdsaVerifier()),
) : LedgerRepository {
    @Inject
    constructor(database: SproutDatabase, clock: Clock, signer: DeviceSigner, verifier: Verifier) : this(
        database,
        clock,
        DeviceKeyEventCheck(signer, verifier),
        HouseholdPolicySource(database, verifier),
        RewardTableCatalogue(database, VerifierSignatureCheck(verifier)),
        RoomDeviceRegistry(database),
        VerifierSignatureCheck(verifier),
    )

    private val events = database.ledgerEventDao()
    private val accounts = database.accountDao()
    private val balances = database.balanceDao()
    private val anomalies = database.anomalyDao()
    private val outbox = database.outboxDao()
    private val audit = database.auditLogDao()
    private val inbox = database.ledgerInboxDao()
    private val deviceClock = DeviceClock.of(database)
    private val chain = HashChain()

    override fun observeBalances(kidId: KidId): Flow<List<Balance>> =
        balances.observeForKid(kidId).map { rows -> rows.map(BalanceEntity::toDomain) }

    override fun observeAnomalies(kidId: KidId): Flow<List<LedgerAnomaly>> =
        anomalies.observeForKid(kidId).map(AnomalyMapper::toDomain)

    override fun observeEvents(kidId: KidId, limit: Int): Flow<List<LedgerEvent>> =
        events.observeNewestForKid(kidId, limit).map { rows -> rows.mapNotNull(LedgerEventMapper::toDomain) }

    /**
     * Every quarantined row (local or pulled), newest first, for the parent's "Rejected events" card: ids, author
     * device and reason only, never the payload.
     */
    fun observeQuarantined(): Flow<List<QuarantinedEvent>> = events.observeQuarantined().map { rows ->
        rows.map { row ->
            QuarantinedEvent(
                eventId = row.eventId,
                hash = row.hash,
                authorDeviceId = row.authorDeviceId,
                reason = row.quarantineReason ?: UNKNOWN_REASON,
                receivedAt = Instant.fromEpochMilliseconds(row.receivedAtEpochMs),
                origin = row.origin,
            )
        }
    }

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
        return database.withTransaction { loadVerified(check, policies.load()).quarantined }
    }

    /**
     * Ingests pulled event documents (`LedgerJson` trees) in one transaction, under the device clock's lock:
     * 1. [RemoteEventVerifier] (registry, [check], the policy's lamport bound) checks each document. A refused one is
     *    stored `quarantined` with the [QuarantineReason] name and its raw tree as `payloadJson` (evidence) when it
     *    decodes, and audited `LEDGER_EVENT_QUARANTINED`; a document too broken to decode is audited only.
     * 2. Events already stored under the same `(eventId, hash)` are duplicates and skipped.
     * 3. [IngestOrdering.plan] orders the rest together with the events held in `ledger_inbox`, from each author's
     *    stored chain head. Events that do not link yet go to (or stay in) `ledger_inbox`.
     * 4. Each applicable event, in order: a different event under a stored eventId is kept as conflict evidence
     *    (`LEDGER_EVENT_CONFLICT`); otherwise [LedgerRules.validate] runs against the projection before it. A valid
     *    event is stored (`origin = REMOTE`, `ingestedAtEpochMs`, no outbox row, audit `LEDGER_EVENT_INGESTED` with
     *    ids only). An event naming an account this device does not know yet is held. An event breaking a rule that
     *    does not depend on what was applied before it (role, amounts, currency, ownership, account kind, text, period
     *    key, interest terms) is stored quarantined with the violations and audited `LEDGER_EVENT_QUARANTINED`, and its
     *    author's later events in the batch are held behind it. An event that only breaks an order-dependent rule
     *    (funds, daily cap, time bank, period claim, grant) is stored like a valid one: whether it applies is decided
     *    by the projection, identically on every device, so replicas converge whatever order events arrive in.
     * 5. The device clock observes the highest lamport stored (bounded by `maxLamportJump`), and the balances and
     *    anomalies caches are rebuilt.
     *
     * `held` in the report counts the events of [jsons] that were held.
     */
    suspend fun ingest(jsons: List<JsonObject>): EventApplyReport {
        val check = bindLocalCheck()
        return deviceClock.withLock { database.withTransaction { ingestLocked(jsons, check) } }
    }

    /** Plans the events held in `ledger_inbox` again (see [ingest]); `held` is what is still held afterwards. */
    suspend fun retryHeld(): EventApplyReport {
        val check = bindLocalCheck()
        return deviceClock.withLock {
            database.withTransaction { ingestLocked(emptyList(), check).copy(held = inbox.count()) }
        }
    }

    /** The highest lamport this device has seen ([DeviceClock.current]). */
    suspend fun localMaxLamport(): Long = database.withTransaction { deviceClock.current() }

    /** Binds the local-event check to this device's key; a Keystore read, so done outside any transaction. */
    internal suspend fun bindLocalCheck(): LocalEventCheck.Bound = localEvents.bind()

    /**
     * The household projection from stored, verified events under the current policy: balances, claimed periods
     * and reward grants for the data layer's use cases. Binds the local check first, so call it outside a transaction.
     */
    internal suspend fun projection(): Projection = snapshot().projection

    /**
     * The stored, verified events with the registered accounts and the current policy, for projections of the past
     * (the interest planner's balance on a given day). Binds the local check first, so call it outside a transaction.
     */
    internal suspend fun snapshot(): LedgerSnapshot {
        val check = bindLocalCheck()
        return database.withTransaction {
            val active = policies.load()
            LedgerSnapshot(
                loadVerified(check, active).events,
                accounts.all().map { it.toDomain() },
                active.policy,
                active.roles,
            )
        }
    }

    /** The body of [append]; must run inside a Room transaction. [check] comes from [bindLocalCheck]. */
    internal suspend fun appendLocked(event: LedgerEvent, check: LocalEventCheck.Bound): AppendResult = when {
        !isOwnSignedEvent(event, check) -> AppendResult.Rejected(FOREIGN_REJECTION)

        events.copiesOf(event.eventId).any { it.hash == event.envelope.hash } -> AppendResult.Duplicate

        // Verifying (and quarantining) first means the chain head below ignores rows that no longer verify.
        else -> policies.load().let { active ->
            val stored = loadVerified(check, active).events
            appendNew(withoutUnverifiableParentSignature(event, active), stored, active)
        }
    }

    private suspend fun appendNew(event: LedgerEvent, stored: List<LedgerEvent>, active: ActivePolicy): AppendResult =
        when {
            !linksToStoredHead(event) -> AppendResult.Rejected(CHAIN_REJECTION)

            !parentSignatureVerifies(event, active) -> AppendResult.Rejected(PARENT_SIGNATURE_REJECTION)

            else -> {
                val before = project(stored, active)
                val storedCopies = events.copiesOf(event.eventId).filterNot { it.quarantined }.map { it.hash }
                if (storedCopies.isEmpty()) {
                    validateAndStore(event, stored, before)
                } else {
                    storeConflict(event, storedCopies.first(), stored, before)
                }
            }
        }

    /**
     * False when [event] carries a parent signature that does not verify against the registered parent key, or when
     * the policy requires one and there is no key to verify it with. An absent signature passes here; whether it was
     * required is [LedgerRules]' [Violation.ParentSignatureMissing].
     */
    private fun parentSignatureVerifies(event: LedgerEvent, active: ActivePolicy): Boolean {
        val signature = event.parentSignatureBytes()?.takeIf { it.isNotEmpty() }
        val key = active.parentKey
        return when {
            signature == null -> true
            key == null -> !event.requiresParentSignature(active.policy)
            else -> payloadOrNull { event.parentSignedPayload() }?.let { key.verify(it, signature) } == true
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
    private suspend fun loadVerified(check: LocalEventCheck.Bound, active: ActivePolicy): Loaded {
        val verified = ArrayList<LedgerEvent>()
        var quarantined = 0
        val devices = HashMap<DeviceId, RegisteredDevice?>()
        for (row in events.allVerified()) {
            val reason = when (val read = LedgerEventMapper.verify(row)) {
                is LedgerEventMapper.Read.Failed -> read.reason

                is LedgerEventMapper.Read.Verified -> {
                    val device = if (row.origin == EventOrigin.REMOTE) {
                        devices.getOrPut(row.authorDeviceId) { registry.device(row.authorDeviceId) }
                    } else {
                        null
                    }
                    signatureFailure(row, read.tree, read.event, check, device).also {
                        if (it == null) verified += read.event
                    }
                }
            }
            if (reason != null) {
                events.quarantine(row.eventId, row.hash, reason)
                quarantined += 1
            }
        }
        if (quarantined > 0) refreshCaches(project(verified, active), verified)
        return Loaded(verified, quarantined)
    }

    /**
     * LOCAL rows must still be this device's signed events. REMOTE rows must still verify against their registered
     * author [device] (and be within its active lamports); when the registry does not know the device ([device]
     * null) the row is left as it is.
     */
    private fun signatureFailure(
        row: LedgerEventEntity,
        tree: JsonObject,
        event: LedgerEvent,
        check: LocalEventCheck.Bound,
        device: RegisteredDevice?,
    ): String? {
        if (row.origin == EventOrigin.REMOTE && device == null) return null
        val payload = payloadOrNull { signedPayloadOf(tree) }
        val signature = event.signatureBytes()
        return when {
            payload == null -> LedgerEventMapper.UNDECODABLE

            device != null -> when {
                !device.acceptsLamport(event.lamport) -> QuarantineReason.DEVICE_NOT_ACTIVE.name
                signature != null && verifies(device.publicKey, payload, signature) -> null
                else -> LedgerEventMapper.SIGNATURE_INVALID
            }

            check.accepts(event, payload) -> null

            else -> LedgerEventMapper.SIGNATURE_INVALID
        }
    }

    @Suppress("TooGenericExceptionCaught") // A third-party SignatureCheck must not be able to break reads.
    private fun verifies(publicKey: ByteArray, payload: ByteArray, signature: ByteArray): Boolean = try {
        this.check.verify(publicKey, payload, signature)
    } catch (expected: Exception) {
        false
    }

    private suspend fun project(stored: List<LedgerEvent>, active: ActivePolicy): Projection = Ledger.project(
        stored,
        accounts.all().map { it.toDomain() },
        active.policy,
        roleOf = active.roles::roleOf,
        trustEnvelopeRoles = active.roles.trustEnvelopeRoles,
    )

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
        return when {
            validation is ValidationResult.Invalid ->
                AppendResult.Rejected(INVARIANT_REJECTION, validation.violations)

            event is RewardPurchased && event.envelope.authorRole == Role.KID && !catalogue.matches(event) ->
                AppendResult.Rejected(CATALOGUE_REJECTION)

            else -> {
                store(event, stored, before, AuditAction.LEDGER_EVENT_APPENDED)
                AppendResult.Accepted
            }
        }
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
        val maxJump = before.policy.maxLamportJump
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
            put("parentSigned", event.envelope.parentSignature != null)
        }
        return LedgerJson.json.encodeToString(JsonObject.serializer(), details)
    }

    /** One pulled document waiting to be ingested; [heldKey] is set when it came from `ledger_inbox`. */
    private class Incoming(val json: JsonObject, val heldKey: Pair<EventId, String>?)

    /** Running totals of one ingest. */
    private class IngestTally {
        var applied = 0
        var duplicates = 0
        var quarantined = 0
        var newlyHeld = 0
        var maxLamport = 0L

        fun report() = EventApplyReport(applied, duplicates, quarantined, newlyHeld)
    }

    /** The body of [ingest] and [retryHeld]; inside a transaction, under the device clock's lock. */
    private suspend fun ingestLocked(jsons: List<JsonObject>, check: LocalEventCheck.Bound): EventApplyReport {
        val active = policies.load()
        val localMax = deviceClock.current()
        val now = clock.now().toEpochMilliseconds()
        val tally = IngestTally()
        val verifier = RemoteEventVerifier(registry, this.check, active.policy)
        // Held events first, so a held event pulled again is a duplicate of its inbox row.
        val incoming = heldIncoming(now, tally) + jsons.map { Incoming(it, null) }
        val ok = LinkedHashMap<Pair<EventId, String>, Pair<VerifiedRemote.Ok, Incoming>>()
        for (item in incoming) {
            when (val verified = verifier.verify(item.json, localMax)) {
                is VerifiedRemote.Quarantined -> {
                    dropHeld(item)
                    storeRefused(item.json, verified, now, tally)
                }

                is VerifiedRemote.Ok -> accept(verified, item, ok, tally)
            }
        }
        if (ok.isNotEmpty()) applyPlanned(ok, check, active, now, tally)
        if (tally.applied > 0) {
            // The verifier bounded every stored lamport by localMax + maxLamportJump; clamp anyway.
            val jump = active.policy.maxLamportJump
            val bound = if (localMax > Long.MAX_VALUE - jump) Long.MAX_VALUE else localMax + jump
            database.observeDeviceLamport(minOf(tally.maxLamport, bound))
        }
        return tally.report()
    }

    /** Step 2 of [ingest]: a verified event already stored is a duplicate; the rest wait for planning in [ok]. */
    private suspend fun accept(
        verified: VerifiedRemote.Ok,
        item: Incoming,
        ok: MutableMap<Pair<EventId, String>, Pair<VerifiedRemote.Ok, Incoming>>,
        tally: IngestTally,
    ) {
        val key = verified.event.eventId to verified.event.envelope.hash
        val stored = events.copiesOf(key.first).any { it.hash == key.second }
        if (stored) dropHeld(item)
        when {
            stored || key in ok -> if (item.heldKey == null) tally.duplicates++
            else -> ok[key] = verified to item
        }
    }

    /** Steps 3 to 5 of [ingest] for the verified, not yet stored events in [ok]. */
    private suspend fun applyPlanned(
        ok: Map<Pair<EventId, String>, Pair<VerifiedRemote.Ok, Incoming>>,
        check: LocalEventCheck.Bound,
        active: ActivePolicy,
        now: Long,
        tally: IngestTally,
    ) {
        val folded = ArrayList(loadVerified(check, active).events)
        val heads = ok.values.map { it.first.event.envelope.authorDeviceId }.toSet().associateWith { author ->
            events.headHash(author)?.let { hash -> StreamHead(hash, events.maxLamportFor(author) ?: 0) }
        }
        val plan = IngestOrdering.plan(ok.values.map { it.first.event }, heads::get)
        var projection = project(folded, active)
        val blocked = HashSet<DeviceId>()
        for (event in plan.apply) {
            val (verified, item) = ok.getValue(event.eventId to event.envelope.hash)
            val author = event.envelope.authorDeviceId
            val outcome = if (author in blocked) RemoteOutcome.Held else applyRemote(verified, projection, now)
            if (outcome !is RemoteOutcome.Stored) blocked += author
            when (outcome) {
                RemoteOutcome.Held -> hold(verified, item, now, tally)

                RemoteOutcome.Quarantined -> {
                    dropHeld(item)
                    tally.quarantined++
                }

                is RemoteOutcome.Stored -> {
                    dropHeld(item)
                    folded += event
                    projection = if (outcome.conflict) project(folded, active) else projection.apply(event)
                    tally.applied++
                    tally.maxLamport = maxOf(tally.maxLamport, event.lamport)
                }
            }
        }
        plan.held.forEach { event ->
            val (verified, item) = ok.getValue(event.eventId to event.envelope.hash)
            hold(verified, item, now, tally)
        }
        if (tally.applied > 0) refreshCaches(project(folded, active), folded)
    }

    /** Removes [item] from `ledger_inbox` if it came from there. */
    private suspend fun dropHeld(item: Incoming) {
        item.heldKey?.let { (eventId, hash) -> inbox.delete(eventId, hash) }
    }

    /** The events held in `ledger_inbox`, parsed; a row that no longer parses is dropped and audited. */
    private suspend fun heldIncoming(now: Long, tally: IngestTally): List<Incoming> = inbox.all().mapNotNull { row ->
        val json = parseObjectOrNull(row.payloadJson)
        if (json == null) {
            inbox.delete(row.eventId, row.hash)
            auditRemote(
                AuditAction.LEDGER_EVENT_QUARANTINED,
                LocalTrial.PARENT,
                row.authorDeviceId,
                row.eventId.value,
                now,
            ) {
                put("hash", row.hash)
                put("reason", QuarantineReason.UNDECODABLE.name)
            }
            tally.quarantined++
            null
        } else {
            Incoming(json, row.eventId to row.hash)
        }
    }

    private sealed interface RemoteOutcome {
        data class Stored(val conflict: Boolean) : RemoteOutcome

        data object Held : RemoteOutcome

        data object Quarantined : RemoteOutcome
    }

    /** Step 4 of [ingest] for one planned event. */
    private suspend fun applyRemote(verified: VerifiedRemote.Ok, before: Projection, now: Long): RemoteOutcome {
        val event = verified.event
        val storedCopies = events.copiesOf(event.eventId).filterNot { it.quarantined }
        if (storedCopies.isNotEmpty()) {
            storeRemote(event, verified.json, now, null, AuditAction.LEDGER_EVENT_CONFLICT)
            return RemoteOutcome.Stored(conflict = true)
        }
        val violations = (LedgerRules.validate(event, before) as? ValidationResult.Invalid)?.violations.orEmpty()
        return when {
            violations.any { it is Violation.UnknownAccount } -> RemoteOutcome.Held

            violations.any { !it.isOrderDependent() } -> {
                val reason = INVARIANT_QUARANTINE + violations.joinToString(",") { it::class.simpleName.orEmpty() }
                storeRemote(event, verified.json, now, reason, AuditAction.LEDGER_EVENT_QUARANTINED)
                RemoteOutcome.Quarantined
            }

            else -> {
                storeRemote(event, verified.json, now, null, AuditAction.LEDGER_EVENT_INGESTED)
                RemoteOutcome.Stored(conflict = false)
            }
        }
    }

    /** Keeps [verified] in `ledger_inbox` (a no-op when it is already there). */
    private suspend fun hold(verified: VerifiedRemote.Ok, incoming: Incoming, now: Long, tally: IngestTally) {
        if (incoming.heldKey != null) return
        val envelope = verified.event.envelope
        val inserted = inbox.insertIfAbsent(
            LedgerInboxEntity(
                eventId = envelope.eventId,
                hash = envelope.hash,
                payloadJson = LedgerJson.json.encodeToString(JsonObject.serializer(), verified.json),
                authorDeviceId = envelope.authorDeviceId,
                lamport = envelope.lamport,
                receivedAtEpochMs = now,
            ),
        )
        if (inserted != NOT_INSERTED) tally.newlyHeld++ else tally.duplicates++
    }

    /**
     * A document the verifier refused: stored quarantined (raw tree as evidence) when it decodes and is not stored
     * yet, audited either way.
     */
    private suspend fun storeRefused(
        json: JsonObject,
        refused: VerifiedRemote.Quarantined,
        now: Long,
        tally: IngestTally,
    ) {
        val event = LedgerJson.decodeOrNull(json)
        val stored = event != null && events.copiesOf(event.eventId).any { it.hash == event.envelope.hash }
        when {
            stored -> tally.duplicates++

            event != null -> {
                storeRemote(event, json, now, refused.reason.name, AuditAction.LEDGER_EVENT_QUARANTINED)
                tally.quarantined++
            }

            else -> {
                auditRemote(
                    AuditAction.LEDGER_EVENT_QUARANTINED,
                    LocalTrial.PARENT,
                    refused.authorDeviceId ?: UNKNOWN_DEVICE,
                    refused.eventId?.value ?: UNKNOWN_TARGET,
                    now,
                ) {
                    refused.hash?.let { put("hash", it) }
                    put("reason", refused.reason.name)
                }
                tally.quarantined++
            }
        }
    }

    /** Stores a pulled event (its own tree as `payloadJson`), with no outbox row, and audits it with ids only. */
    private suspend fun storeRemote(
        event: LedgerEvent,
        tree: JsonObject,
        now: Long,
        quarantineReason: String?,
        action: AuditAction,
    ) {
        val row = LedgerEventMapper.toEntity(event, EventOrigin.REMOTE, now, quarantineReason, tree)
            .copy(ingestedAtEpochMs = now)
        events.insert(row)
        val envelope = event.envelope
        auditRemote(action, envelope.authorUid, envelope.authorDeviceId, envelope.eventId.value, now) {
            put("type", row.type)
            put("hash", envelope.hash)
            put("kidId", envelope.kidId.value)
            put("accountId", envelope.accountId.value)
            put("lamport", envelope.lamport)
            put("role", envelope.authorRole.name)
            quarantineReason?.let { put("reason", it) }
        }
    }

    @Suppress("LongParameterList") // The audit row's fields.
    private suspend fun auditRemote(
        action: AuditAction,
        actor: UserId,
        deviceId: DeviceId,
        target: String,
        now: Long,
        details: JsonObjectBuilder.() -> Unit,
    ) {
        audit.insert(
            AuditLogEntity(
                atEpochMs = now,
                actorUid = actor,
                deviceId = deviceId,
                action = action,
                target = target,
                detailsJson = LedgerJson.json.encodeToString(JsonObject.serializer(), buildJsonObject(details)),
            ),
        )
    }

    private fun parseObjectOrNull(text: String): JsonObject? = try {
        LedgerJson.json.parseToJsonElement(text) as? JsonObject
    } catch (expected: IllegalArgumentException) {
        null
    }

    companion object {
        /** [AppendResult.Rejected.reason] when the event does not link to its author's stored chain head. */
        const val CHAIN_REJECTION: String = "chain verification failed"

        /** [AppendResult.Rejected.reason] when [LedgerRules] rejects the event. */
        const val INVARIANT_REJECTION: String = "invariant violated"

        /** [AppendResult.Rejected.reason] when the event is not this device's own, validly signed event. */
        const val FOREIGN_REJECTION: String = "unsigned or foreign event"

        /**
         * [AppendResult.Rejected.reason] when the event's parent signature does not verify against the registered
         * parent key (or one is required and no key is registered).
         */
        const val PARENT_SIGNATURE_REJECTION: String = "parent signature invalid"

        /** [AppendResult.Rejected.reason] when a kid-authored purchase does not match the reward catalogue. */
        const val CATALOGUE_REJECTION: String = "reward does not match the catalogue"

        /** Prefix of the quarantine reason of a pulled event that broke an order-independent ledger rule. */
        const val INVARIANT_QUARANTINE: String = "invariant violated: "

        private const val UNKNOWN_REASON = "unknown"
        private const val UNKNOWN_TARGET = "unknown"
        private val UNKNOWN_DEVICE = DeviceId("unknown")

        /** Room's row id for an `INSERT OR IGNORE` that was ignored. */
        private const val NOT_INSERTED = -1L
    }
}

/**
 * [event] without its parent signature when no parent key is registered (audit M1): such a signature cannot be
 * verified, so it is never stored and the event counts as unsigned. The hash and device signature do not cover
 * the parent signature, so dropping it changes nothing else. With a key registered the signature is kept and must
 * verify (see [RoomLedgerRepository]).
 */
private fun withoutUnverifiableParentSignature(event: LedgerEvent, active: ActivePolicy): LedgerEvent =
    if (active.parentKey == null && event.envelope.parentSignature != null) {
        event.withEnvelope(event.envelope.copy(parentSignature = null))
    } else {
        event
    }

/**
 * What [RoomLedgerRepository.snapshot] read: verified events, registered accounts, the policy in force and the
 * author-device roles conflicts are resolved with.
 */
internal class LedgerSnapshot(
    val events: List<LedgerEvent>,
    val accounts: List<Account>,
    val policy: LedgerPolicy,
    private val roles: RoleLookup = RoleLookup.LOCAL,
) {
    /** The household projection of every event. */
    val projection: Projection by lazy { projectionOf { true } }

    /** The projection of only the events [include] keeps (for example those created up to some day). */
    fun projectionOf(include: (LedgerEvent) -> Boolean): Projection = Ledger.project(
        events.filter(include),
        accounts,
        policy,
        roleOf = roles::roleOf,
        trustEnvelopeRoles = roles.trustEnvelopeRoles,
    )
}

/** One quarantined row, as the parent's "Rejected events" card lists it: ids, author device, reason; no payload. */
data class QuarantinedEvent(
    val eventId: EventId,
    val hash: String,
    val authorDeviceId: DeviceId,
    val reason: String,
    val receivedAt: Instant,
    val origin: EventOrigin,
)

/**
 * Whether a violation depends on what was applied before the event (funds, caps, time bank, period claims, grants,
 * lamport distance). Those are judged by the projection on every device, in the same total order, so a pulled event
 * that only breaks them is stored and left to the projection; any other violation is a property of the event itself
 * and quarantines it.
 */
private fun Violation.isOrderDependent(): Boolean = when (this) {
    is Violation.DuplicateEvent,
    is Violation.LamportTooFarAhead,
    is Violation.InsufficientFunds,
    is Violation.DailyCapExceeded,
    is Violation.DuplicatePeriod,
    is Violation.GrantNotFound,
    is Violation.GrantRewardMismatch,
    is Violation.GrantMinutesMismatch,
    is Violation.GrantAlreadyCredited,
    Violation.TimeBankAlreadyRunning,
    Violation.TimeBankEmpty,
    Violation.TimeBankNotRunning,
    Violation.Overflow,
    -> true

    else -> false
}

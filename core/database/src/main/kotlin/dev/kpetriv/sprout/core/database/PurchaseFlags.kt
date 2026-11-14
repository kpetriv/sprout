package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.dao.PurchaseFlagRow
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.AuditLogEntity
import dev.kpetriv.sprout.core.database.entity.PurchaseFlagEntity
import dev.kpetriv.sprout.core.database.entity.PurchaseFlagReason
import dev.kpetriv.sprout.core.database.entity.RewardEntity
import dev.kpetriv.sprout.core.database.entity.RewardHistoryEntity
import dev.kpetriv.sprout.core.database.entity.RewardVersionEntity
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.DeviceRegistry
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.RewardPurchased
import dev.kpetriv.sprout.core.domainledger.SignatureCheck
import dev.kpetriv.sprout.core.model.ID_PATTERN
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.Role
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
 * Reviews a pulled kid-authored [RewardPurchased] that ingest has just stored (D-REL-65). Called by
 * [RoomLedgerRepository] inside the ingest transaction, only for a purchase that carries a server stamp and was
 * stored as `LEDGER_EVENT_INGESTED` or as a conflict copy (`LEDGER_EVENT_CONFLICT`). It may record a flag; it never
 * changes what is stored, quarantined, held or projected.
 */
internal fun interface PurchaseReview {
    suspend fun review(event: RewardPurchased, serverReceivedAtEpochMs: Long)
}

/**
 * This device's reward version history and the flags it raises on pulled kid purchases (D-REL-65, option (b)).
 *
 * **Recording.** [record] keeps every reward version written here ([RoomRewardRepository]) or applied from a pull
 * ([RoomRemoteApplier], losers of last-writer-wins and tombstones included) in `reward_versions`, insert-or-ignore,
 * and starts the device-wide history (`reward_history`) if it has not started yet. A version that is new here is
 * then tested against the open flags of its reward whose purchase has a higher lamport (no other version can have
 * changed their outcome): a purchase pulled before the version that sells it stops being flagged once the version
 * arrives, audited `PURCHASE_FLAG_CLEARED`, so every `PURCHASE_FLAGGED` row of a healed flag has a closing row.
 *
 * **Review.** [review] runs on PARENT sessions only (a kid device has nothing to act on). The purchase is checked
 * only when its server stamp is at or after the **device-wide** history start (set now, on this device's wall clock,
 * if it is not set yet), whatever reward it names, so a fresh install or a restore never flags the household's past
 * while a reward and a forged purchase of it that arrive in the same pull are still checked. A purchase that
 * [RewardTableCatalogue.matchesAnyVersion] does not match is flagged ([PurchaseFlagReason]) and audited
 * `PURCHASE_FLAGGED` with ids and the reason only.
 *
 * Whether a version was seen depends on this device's sync history, so flags differ between devices; that is why
 * they never refuse or quarantine anything (D-REL-65 §3). All of it runs inside the caller's transaction.
 */
internal class RewardVersionHistory(
    private val database: SproutDatabase,
    private val clock: Clock,
    check: SignatureCheck,
    registry: DeviceRegistry = RoomDeviceRegistry(database),
) : PurchaseReview {
    private val catalogue = RewardTableCatalogue(database, check, registry)
    private val versions = database.rewardVersionDao()
    private val flags = database.purchaseFlagDao()

    /** Records [row] (its lamport and stamp) as a version seen now; a row without an author device is skipped. */
    suspend fun record(row: RewardEntity) {
        RewardVersionEntity.of(row, clock.now().toEpochMilliseconds())?.let { record(it) }
    }

    /**
     * Records [version] (kept as first recorded if already there); a new one starts the history if needed and heals
     * the flags it may sell.
     */
    suspend fun record(version: RewardVersionEntity) {
        if (versions.insertIfAbsent(version) == NOT_INSERTED) return
        deviceStart(version.recordedAtEpochMs)
        heal(version)
    }

    override suspend fun review(event: RewardPurchased, serverReceivedAtEpochMs: Long) {
        if (database.sessionDao().get()?.role != Role.PARENT) return
        val now = clock.now().toEpochMilliseconds()
        val rewardId = event.rewardId.takeIf(ID_PATTERN::matches)?.let(::RewardId)
        val inHistory = serverReceivedAtEpochMs >= deviceStart(now)
        if (inHistory && !catalogue.matchesAnyVersion(event)) flag(event, rewardId, now)
    }

    /** Flags [event] ([rewardId] when its reward id is a valid id) and audits it, unless it is flagged already. */
    private suspend fun flag(event: RewardPurchased, rewardId: RewardId?, now: Long) {
        val known = rewardId != null && versions.countFor(rewardId) > 0
        val reason = if (known) PurchaseFlagReason.NO_MATCHING_VERSION else PurchaseFlagReason.NO_SUCH_REWARD
        val envelope = event.envelope
        val inserted = flags.insertIfAbsent(
            PurchaseFlagEntity(
                eventId = event.eventId,
                hash = envelope.hash,
                kidId = envelope.kidId,
                rewardId = event.rewardId,
                purchaseLamport = event.lamport,
                reason = reason,
                amount = event.amount,
                currency = event.currency,
                minutesGranted = event.minutesGranted,
                flaggedAtEpochMs = now,
            ),
        )
        if (inserted != NOT_INSERTED) {
            audit(AuditAction.PURCHASE_FLAGGED, event, now) { put("reason", reason.name) }
        }
    }

    /**
     * Audits [action] on [event], with its author as actor, its hash, kid and reward id, and [details]: ids and codes
     * only.
     */
    private suspend fun audit(
        action: AuditAction,
        event: RewardPurchased,
        now: Long,
        details: JsonObjectBuilder.() -> Unit,
    ) {
        val envelope = event.envelope
        database.auditLogDao().insert(
            AuditLogEntity(
                atEpochMs = now,
                actorUid = envelope.authorUid,
                deviceId = envelope.authorDeviceId,
                action = action,
                target = event.eventId.value,
                detailsJson = LedgerJson.json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("hash", envelope.hash)
                        put("kidId", envelope.kidId.value)
                        // A reward id outside the id alphabet is free text the kid device chose: left out.
                        event.rewardId.takeIf(ID_PATTERN::matches)?.let { put("rewardId", it) }
                        details()
                    },
                ),
            ),
        )
    }

    /** The device-wide history start, set to [now] if it was not set yet. */
    private suspend fun deviceStart(now: Long): Long {
        versions.startHistoryIfAbsent(RewardHistoryEntity(startedAtEpochMs = now))
        return versions.historyStart()?.startedAtEpochMs ?: now
    }

    /** Drops the open flags whose purchase the newly recorded [version] sells, audited `PURCHASE_FLAG_CLEARED`. */
    private suspend fun heal(version: RewardVersionEntity) {
        if (version.deleted) return
        val now = clock.now().toEpochMilliseconds()
        for (flag in flags.openAfter(version.rewardId.value, version.lamport)) {
            val purchase = flags.flaggedEvent(flag.eventId, flag.hash)
                ?.let(LedgerEventMapper::toDomain) as? RewardPurchased ?: continue
            if (catalogue.versionSells(version, purchase)) {
                flags.delete(flag.eventId, flag.hash)
                audit(AuditAction.PURCHASE_FLAG_CLEARED, purchase, now) {
                    put("versionLamport", version.lamport)
                    put("versionAuthorDeviceId", version.authorDeviceId.value)
                }
            }
        }
    }

    private companion object {
        /** Room's row id for an `INSERT OR IGNORE` that was ignored. */
        const val NOT_INSERTED = -1L
    }
}

/** A reward as it is now, next to a flagged purchase of it. */
data class CurrentReward(val title: String, val cost: Money, val currency: Currency, val minutesGranted: Int?) {
    override fun toString(): String =
        "CurrentReward(title=<redacted>, cost=$cost, currency=$currency, minutesGranted=$minutesGranted)"
}

/**
 * An open flag on a pulled kid purchase (D-REL-65): what the purchase claims, why this device flagged it, and the
 * reward as it is now ([currentReward], null when the reward is unknown here or deleted).
 *
 * @property rewardId the reward id the purchase names, as it names it.
 */
data class FlaggedPurchase(
    val eventId: EventId,
    val hash: String,
    val kidId: KidId,
    val rewardId: String,
    val reason: PurchaseFlagReason,
    val amount: Money,
    val currency: Currency,
    val minutesGranted: Money?,
    val flaggedAt: Instant,
    val currentReward: CurrentReward?,
) {
    // The reward title is the household's free text: never in logs (CurrentReward redacts it).
    override fun toString(): String =
        "FlaggedPurchase(eventId=$eventId, hash=$hash, kidId=$kidId, rewardId=$rewardId, reason=$reason, " +
            "amount=$amount, currency=$currency, minutesGranted=$minutesGranted, flaggedAt=$flaggedAt, " +
            "currentReward=$currentReward)"
}

/**
 * Pulled kid purchases this PARENT device flagged because they match no reward version it has seen (D-REL-65), for a
 * parent to check. A flag never changes the ledger: dismissing one only hides it.
 */
interface PurchaseFlagRepository {
    /**
     * The open (not dismissed) flags whose purchase is stored and not quarantined, newest first. A row this mapping
     * cannot read (an unknown currency) is left out; what can still throw is Room's own conversion of a row edited
     * outside the app (an unknown `reason` or a malformed `eventId`, on a rooted device), as for every Room read.
     */
    fun observePurchaseFlags(): Flow<List<FlaggedPurchase>>

    /** Dismisses the flag on the purchase `(eventId, hash)`; nothing happens when there is no open one. */
    suspend fun dismissPurchaseFlag(eventId: EventId, hash: String)
}

/** [PurchaseFlagRepository] on Room (`purchase_flags` joined with `rewards`). */
class RoomPurchaseFlagRepository @Inject constructor(database: SproutDatabase, private val clock: Clock) :
    PurchaseFlagRepository {
    private val flags = database.purchaseFlagDao()

    override fun observePurchaseFlags(): Flow<List<FlaggedPurchase>> =
        flags.observeOpen().map { rows -> rows.mapNotNull(::toDomainOrNull) }

    override suspend fun dismissPurchaseFlag(eventId: EventId, hash: String) {
        flags.dismiss(eventId, hash, clock.now().toEpochMilliseconds())
    }

    private fun toDomainOrNull(row: PurchaseFlagRow): FlaggedPurchase? = try {
        val flag = row.flag
        FlaggedPurchase(
            eventId = flag.eventId,
            hash = flag.hash,
            kidId = flag.kidId,
            rewardId = flag.rewardId,
            reason = flag.reason,
            amount = flag.amount,
            currency = flag.currency,
            minutesGranted = flag.minutesGranted,
            flaggedAt = Instant.fromEpochMilliseconds(flag.flaggedAtEpochMs),
            currentReward = currentReward(row),
        )
    } catch (expected: IllegalArgumentException) {
        null
    }

    private fun currentReward(row: PurchaseFlagRow): CurrentReward? {
        val title = row.rewardTitle
        val cost = row.rewardCost
        val currency = row.rewardCurrency
        return if (title != null && cost != null && currency != null) {
            CurrentReward(title, Money(cost), Converters.stringToCurrency(currency), row.rewardMinutes)
        } else {
            null
        }
    }
}

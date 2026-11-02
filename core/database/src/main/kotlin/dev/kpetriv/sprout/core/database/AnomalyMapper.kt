package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.AnomalyEntity
import dev.kpetriv.sprout.core.database.entity.AnomalyKind
import dev.kpetriv.sprout.core.domainledger.CurrencySerializer
import dev.kpetriv.sprout.core.domainledger.DuplicateCopy
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.LedgerAnomaly
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Maps [LedgerAnomaly] to cache rows and back, losslessly. Row ids are stable across projection rebuilds:
 * - overdraft: `overdraft:<accountId>:<first causing eventId>` (later debits extend the same anomaly);
 * - daily cap: `daily_cap:<eventId>`;
 * - invalid event, conflicting duplicate: `<kind>:<eventId>:<kidId>`. One eventId can concern several kids (an id
 *   reused across kids), and each kid's audit view gets its own row.
 *
 * Details hold ids, dates, currency codes, roles, hashes and violation fields only, never free text. `order` is the
 * anomaly's position in the kid's projected list, so reads return the same order the domain produced.
 */
internal object AnomalyMapper {
    private const val ORDER = "order"

    fun toEntity(kidId: KidId, anomaly: LedgerAnomaly, order: Int, detectedAtEpochMs: Long): AnomalyEntity =
        when (anomaly) {
            is LedgerAnomaly.OverdraftDetected -> {
                val firstCause = anomaly.causedBy.first().value
                AnomalyEntity(
                    anomalyId = "overdraft:${anomaly.accountId.value}:$firstCause",
                    kidId = kidId,
                    kind = AnomalyKind.OVERDRAFT,
                    accountId = anomaly.accountId,
                    eventId = firstCause,
                    amountMinor = anomaly.shortfall,
                    detailsJson = details(order) {
                        put("causedBy", JsonArray(anomaly.causedBy.map { JsonPrimitive(it.value) }))
                    },
                    detectedAtEpochMs = detectedAtEpochMs,
                )
            }

            is LedgerAnomaly.DailyCapExceeded -> AnomalyEntity(
                anomalyId = "daily_cap:${anomaly.eventId.value}",
                kidId = kidId,
                kind = AnomalyKind.DAILY_CAP_EXCEEDED,
                accountId = null,
                eventId = anomaly.eventId.value,
                amountMinor = anomaly.excess,
                detailsJson = details(order) {
                    put("anomalyKidId", anomaly.kidId.value)
                    put("date", anomaly.date.toString())
                    put("currency", LedgerJson.json.encodeToJsonElement(CurrencySerializer, anomaly.currency))
                },
                detectedAtEpochMs = detectedAtEpochMs,
            )

            is LedgerAnomaly.InvalidEvent -> AnomalyEntity(
                anomalyId = "invalid:${anomaly.eventId.value}:${kidId.value}",
                kidId = kidId,
                kind = AnomalyKind.INVALID_EVENT,
                accountId = null,
                eventId = anomaly.eventId.value,
                amountMinor = null,
                detailsJson = details(order) {
                    put("violations", JsonArray(anomaly.violations.map(ViolationCodec::encode)))
                },
                detectedAtEpochMs = detectedAtEpochMs,
            )

            is LedgerAnomaly.ConflictingDuplicate -> AnomalyEntity(
                anomalyId = "conflict:${anomaly.eventId.value}:${kidId.value}",
                kidId = kidId,
                kind = AnomalyKind.CONFLICTING_DUPLICATE,
                accountId = null,
                eventId = anomaly.eventId.value,
                amountMinor = null,
                detailsJson = details(order) { put("copies", JsonArray(anomaly.copies.map(::encodeCopy))) },
                detectedAtEpochMs = detectedAtEpochMs,
            )
        }

    /** Every readable row as a domain anomaly, in projection order. A row that cannot be read is skipped. */
    fun toDomain(rows: List<AnomalyEntity>): List<LedgerAnomaly> = rows
        .mapNotNull { row -> decodeOrNull(row) }
        .sortedBy { it.first }
        .map { it.second }

    private fun decodeOrNull(row: AnomalyEntity): Pair<Int, LedgerAnomaly>? = try {
        val details = LedgerJson.json.parseToJsonElement(row.detailsJson).jsonObject
        details.getValue(ORDER).jsonPrimitive.int to decode(row, details)
    } catch (expected: IllegalArgumentException) {
        // SerializationException and NumberFormatException are IllegalArgumentExceptions; a missing key is the
        // NoSuchElementException below. A corrupt cache row must never end the read Flow; the next projection
        // rewrites it.
        null
    } catch (expected: NoSuchElementException) {
        null
    }

    private fun decode(row: AnomalyEntity, details: JsonObject): LedgerAnomaly {
        val eventId = EventId(row.eventId)
        return when (row.kind) {
            AnomalyKind.OVERDRAFT -> LedgerAnomaly.OverdraftDetected(
                accountId = requireNotNull(row.accountId) { "overdraft without account" },
                shortfall = requireNotNull(row.amountMinor) { "overdraft without shortfall" },
                causedBy = details.getValue("causedBy").jsonArray.map { EventId(it.jsonPrimitive.content) },
            )

            AnomalyKind.DAILY_CAP_EXCEEDED -> LedgerAnomaly.DailyCapExceeded(
                kidId = KidId(details.getValue("anomalyKidId").jsonPrimitive.content),
                date = CalendarDate.parse(details.getValue("date").jsonPrimitive.content),
                currency = LedgerJson.json.decodeFromJsonElement(CurrencySerializer, details.getValue("currency")),
                eventId = eventId,
                excess = requireNotNull(row.amountMinor) { "daily cap without excess" },
            )

            AnomalyKind.INVALID_EVENT -> LedgerAnomaly.InvalidEvent(
                eventId = eventId,
                violations = details.getValue("violations").jsonArray.map { ViolationCodec.decode(it.jsonObject) },
            )

            AnomalyKind.CONFLICTING_DUPLICATE -> LedgerAnomaly.ConflictingDuplicate(
                eventId = eventId,
                copies = details.getValue("copies").jsonArray.map { decodeCopy(it.jsonObject) },
            )
        }
    }

    private fun encodeCopy(copy: DuplicateCopy): JsonObject = buildJsonObject {
        put("hash", copy.hash)
        put("kidId", copy.kidId.value)
        put("authorUid", copy.authorUid.value)
        put("authorRole", copy.authorRole.name)
        put("authorDeviceId", copy.authorDeviceId.value)
        put("lamport", copy.lamport)
    }

    private fun decodeCopy(json: JsonObject): DuplicateCopy {
        fun text(key: String) = json.getValue(key).jsonPrimitive.content
        return DuplicateCopy(
            hash = text("hash"),
            kidId = KidId(text("kidId")),
            authorUid = UserId(text("authorUid")),
            authorRole = Role.valueOf(text("authorRole")),
            authorDeviceId = DeviceId(text("authorDeviceId")),
            lamport = json.getValue("lamport").jsonPrimitive.long,
        )
    }

    private fun details(order: Int, block: JsonObjectBuilder.() -> Unit): String = LedgerJson.json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put(ORDER, order)
            block()
        },
    )
}

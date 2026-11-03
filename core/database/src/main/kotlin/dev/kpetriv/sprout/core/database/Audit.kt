package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.AuditLogEntity
import dev.kpetriv.sprout.core.domainledger.EventAuthor
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject

/** An audit row for [author] acting on [target]. [details] must hold ids and codes only, never personal data. */
internal fun auditEntry(
    author: EventAuthor,
    atEpochMs: Long,
    action: AuditAction,
    target: String,
    details: JsonObjectBuilder.() -> Unit = {},
) = AuditLogEntity(
    atEpochMs = atEpochMs,
    actorUid = author.uid,
    deviceId = author.deviceId,
    action = action,
    target = target,
    detailsJson = LedgerJson.json.encodeToString(JsonObject.serializer(), buildJsonObject(details)),
)

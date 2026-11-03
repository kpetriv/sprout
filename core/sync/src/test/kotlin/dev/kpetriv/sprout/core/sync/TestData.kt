package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.domainledger.AttestationLevel
import dev.kpetriv.sprout.core.domainledger.DeviceOwner
import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSubmission
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.SubmissionId
import dev.kpetriv.sprout.core.model.SubmissionStatus
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal val HOUSEHOLD = HouseholdId("household-1")
internal val KID_1 = KidId("kid-1")
internal val KID_2 = KidId("kid-2")
internal val SUBMITTED_AT: Instant = Instant.fromEpochSeconds(1_800_000_000)

/** A device identity for tests: its key and its registry entry. */
internal class TestIdentity(
    id: String,
    uid: String,
    val role: Role,
    val kidId: KidId? = null,
    status: DeviceStatus = DeviceStatus.Active,
) {
    val key = SoftwareDeviceKey()
    val deviceId = DeviceId(id)
    val uid = UserId(uid)
    val registered =
        RegisteredDevice(deviceId, DeviceOwner(this.uid, role, kidId), key.publicKey, status, AttestationLevel.TEE)

    fun withStatus(status: DeviceStatus) =
        RegisteredDevice(deviceId, registered.owner, registered.publicKey, status, registered.attestation)

    fun envelope(
        docType: String,
        docId: String,
        lamport: Long,
        payload: JsonObject = buildJsonObject { put("name", docId) },
        deleted: Boolean = false,
    ): DocumentEnvelope = DocumentEnvelope(
        householdId = HOUSEHOLD,
        docType = docType,
        docId = docId,
        authorUid = uid,
        authorDeviceId = deviceId,
        lamport = lamport,
        deleted = deleted,
        payload = payload,
    )

    fun signed(
        docType: String,
        docId: String,
        lamport: Long,
        payload: JsonObject = buildJsonObject { put("name", docId) },
        deleted: Boolean = false,
    ): DocumentEnvelope = envelope(docType, docId, lamport, payload, deleted).signWith(key::sign)

    /** A minimal ledger event tree; core/sync only routes and deduplicates events. */
    fun event(eventId: String, lamport: Long, hash: String = "hash-$eventId"): JsonObject = buildJsonObject {
        put("type", "Test")
        put(
            "envelope",
            buildJsonObject {
                put("eventId", eventId)
                put("hash", hash)
                put("lamport", lamport)
                put("householdId", HOUSEHOLD.value)
                put("authorDeviceId", deviceId.value)
                put("authorUid", uid.value)
                put("authorRole", role.name)
                put("kidId", (kidId ?: KID_1).value)
            },
        )
    }
}

internal fun submission(
    status: SubmissionStatus = SubmissionStatus.SUBMITTED,
    kidId: KidId = KID_1,
    id: String = "sub-1",
    note: String? = null,
    decidedAtSeconds: Long = 60,
    decidedBy: String = "parent-uid",
): ChoreSubmission = ChoreSubmission(
    id = SubmissionId(id),
    choreId = ChoreId("chore-1"),
    dueOn = CalendarDate(2026, 11, 3),
    kidId = kidId,
    submittedAt = SUBMITTED_AT,
    note = note,
    status = status,
    decidedAt = if (status == SubmissionStatus.SUBMITTED) null else SUBMITTED_AT + decidedAtSeconds.seconds,
    decidedBy = if (status == SubmissionStatus.SUBMITTED) null else UserId(decidedBy),
)

internal fun ChoreSubmission.toPayload(): JsonObject =
    DocumentJsonCodec.json.encodeToJsonElement(ChoreSubmission.serializer(), this) as JsonObject

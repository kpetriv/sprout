package dev.kpetriv.sprout.core.syncfirebase

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.sync.DocumentEnvelope
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object TestData {
    val HID = HouseholdId("h1")
    const val UID = "parent-uid"
    val DEVICE = "a".repeat(64)
    val OTHER_DEVICE = "b".repeat(64)

    fun hash(seed: Int): String = seed.toString(16).padStart(64, '0')

    /** A `LedgerJson`-shaped event tree: polymorphic `type`, nested `envelope`, event fields. */
    fun event(
        id: String,
        hash: String = hash(id.hashCode().and(0xffff)),
        lamport: Long = 1,
        hid: String = HID.value,
        uid: String = UID,
        device: String = DEVICE,
    ) = buildJsonObject {
        put("type", "manual_adjustment")
        put(
            "envelope",
            buildJsonObject {
                put("eventId", id)
                put("householdId", hid)
                put("kidId", "kid-a")
                put("accountId", "kid-a.EUR.SPENDING")
                put("authorUid", uid)
                put("authorRole", "PARENT")
                put("authorDeviceId", device)
                put("createdAt", 1_762_000_000_000L)
                put("lamport", lamport)
                put("prevHash", JsonNull)
                put("hash", hash)
                put("signature", "MEUCIQ-sig")
                put("parentSignature", JsonNull)
                put("schemaVersion", 2)
            },
        )
        put("currency", "EUR")
        put("amount", 250)
        put("reason", "Birthday")
        put("causedBy", buildJsonArray { add(JsonPrimitive("evt-0")) })
    }

    fun document(
        docType: String = DocTypes.CHORE,
        docId: String = "doc-1",
        lamport: Long = 5,
        device: String = DEVICE,
        payload: JsonObject = buildJsonObject {
            put("id", docId)
            put("title", "Feed the cat")
            put("points", 3)
        },
        signature: String? = "MEUCIQ-doc-sig",
        hid: HouseholdId = HID,
        deleted: Boolean = false,
        uid: String = UID,
    ) = DocumentEnvelope(
        householdId = hid,
        docType = docType,
        docId = docId,
        authorUid = UserId(uid),
        authorDeviceId = DeviceId(device),
        lamport = lamport,
        deleted = deleted,
        payload = payload,
        signature = signature,
    )

    /** A `parent_key` document as an old client or a forger would build it; no such type exists any more. */
    fun parentKeyDocument(fingerprint: String = "c".repeat(64), validFrom: Long = 7) = document(
        docType = "parent_key",
        docId = fingerprint,
        payload = buildJsonObject {
            put("publicKey", "AAAA")
            put("validFromLamport", validFrom)
        },
    )

    /** A signed-in parent whose fresh claims name [hid] (null: the claim was removed). */
    fun member(hid: HouseholdId? = HID): AuthState =
        AuthState.SignedIn(UserId(UID), isAnonymous = false, claims = Claims(hid, Role.PARENT, kidId = null))
}

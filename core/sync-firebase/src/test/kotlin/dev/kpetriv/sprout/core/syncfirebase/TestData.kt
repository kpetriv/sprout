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

    /** Device ids are the SHA-256 of the device key (`StoredShapes.deviceOf` checks it): keys AQID and BAUG. */
    const val DEVICE = "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"
    const val DEVICE_KEY = "AQID"
    const val OTHER_DEVICE = "787c798e39a5bc1910355bae6d0cd87a36b2e10fd0202a83e3bb6b005da83472"

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

/** Chat wire shapes for the transport tests (signatures are opaque strings: the transport never verifies). */
object ChatData {
    val KID = dev.kpetriv.sprout.core.model.KidId("kid-a")

    fun message(
        id: String,
        seq: Long = 1,
        thread: String = KID.value,
        hid: HouseholdId = TestData.HID,
        ciphertext: ByteArray = ByteArray(40) { it.toByte() },
        kind: dev.kpetriv.sprout.core.domainledger.chat.MessageKind =
            dev.kpetriv.sprout.core.domainledger.chat.MessageKind.TEXT,
        signature: String? = "MEUCIQ-msg-sig",
    ): JsonObject = dev.kpetriv.sprout.core.domainledger.chat.MessageJson.toJsonObject(
        dev.kpetriv.sprout.core.domainledger.chat.MessageEnvelope(
            householdId = hid,
            threadId = dev.kpetriv.sprout.core.model.ThreadId(thread),
            messageId = dev.kpetriv.sprout.core.model.MessageId(id),
            authorUid = UserId(TestData.UID),
            authorDeviceId = DeviceId(TestData.DEVICE),
            authorRole = Role.PARENT,
            seq = seq,
            lamport = seq,
            createdAt = kotlin.time.Instant.fromEpochMilliseconds(1_762_000_000_123),
            epoch = 0,
            kind = kind,
            ciphertext = kotlin.io.encoding.Base64.Default.encode(ciphertext),
            blobId = if (kind.hasBlob) dev.kpetriv.sprout.core.model.BlobId("blob-$id") else null,
            signature = signature,
        ),
    )

    fun chunk(
        blobId: String,
        chunk: Int = 0,
        chunks: Int = 1,
        data: ByteArray = ByteArray(100) { (it * 3).toByte() },
        hid: HouseholdId = TestData.HID,
    ): dev.kpetriv.sprout.core.sync.BlobChunkJson {
        val header = dev.kpetriv.sprout.core.domainledger.chat.BlobChunkHeader(
            householdId = hid,
            blobId = dev.kpetriv.sprout.core.model.BlobId(blobId),
            threadId = dev.kpetriv.sprout.core.model.ThreadId(KID.value),
            kidId = KID,
            authorUid = UserId(TestData.UID),
            authorDeviceId = DeviceId(TestData.DEVICE),
            lamport = 3,
            chunk = chunk,
            chunks = chunks,
            sha256 = "ab".repeat(32),
            epoch = 0,
            signature = "MEUCIQ-blob-sig",
        )
        return dev.kpetriv.sprout.core.sync.BlobChunkJson(
            dev.kpetriv.sprout.core.domainledger.chat.BlobJson.toJsonObject(header),
            data,
        )
    }
}

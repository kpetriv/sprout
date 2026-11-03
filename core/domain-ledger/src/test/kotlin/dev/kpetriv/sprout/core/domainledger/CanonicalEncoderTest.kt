package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class CanonicalEncoderTest {
    private val golden = ChoreRewarded(
        envelope = Envelope(
            eventId = EventId("00000000-0000-4000-8000-000000000001"),
            householdId = HouseholdId("house-1"),
            kidId = KidId("kid-1"),
            accountId = AccountId("main-1"),
            authorUid = UserId("parent-uid"),
            authorRole = Role.PARENT,
            authorDeviceId = DeviceId("parent-phone"),
            createdAt = Instant.fromEpochMilliseconds(1_793_606_400_000),
            lamport = 1,
            prevHash = null,
            hash = "not-part-of-the-canonical-form",
            signature = "c2lnbmF0dXJl",
            parentSignature = "cGFyZW50",
        ),
        currency = Currency.Fiat("GBP"),
        amount = Money(150),
        choreId = "dishes",
        dueOn = CalendarDate(2026, 11, 2),
    )

    @Test
    fun goldenEncodingIsFrozen() {
        // Changing this string breaks every stored hash; bump Envelope.schemaVersion instead.
        val expected = """{"amount":150,"choreId":"dishes","currency":"GBP","dueOn":"2026-11-02",""" +
            """"envelope":{"accountId":"main-1","authorDeviceId":"parent-phone","authorRole":"PARENT",""" +
            """"authorUid":"parent-uid","createdAt":1793606400000,"eventId":"00000000-0000-4000-8000-000000000001",""" +
            """"householdId":"house-1","kidId":"kid-1","lamport":1,"prevHash":null,"schemaVersion":2},""" +
            """"type":"chore_rewarded"}"""
        assertEquals(2, golden.envelope.schemaVersion)
        assertEquals(expected, CanonicalEncoder.encodeToString(golden))
        assertContentEquals(expected.encodeToByteArray(), CanonicalEncoder.encode(golden))
    }

    @Test
    fun goldenHashIsFrozen() {
        // Independently computed: sha256(canonical || "0" * 64).
        assertEquals(V2_GOLDEN_HASH, HashChain().hashOf(golden))
    }

    @Test
    fun goldenV1DocumentStillHashesUnchanged() {
        val tree = Json.parseToJsonElement(V1_CHORE_DOCUMENT).jsonObject
        val expected = """{"amount":150,"choreId":"dishes","currency":"GBP","envelope":{"accountId":"main-1",""" +
            """"authorDeviceId":"parent-phone","authorRole":"PARENT","authorUid":"parent-uid",""" +
            """"createdAt":1793606400000,"eventId":"00000000-0000-4000-8000-000000000001","householdId":"house-1",""" +
            """"kidId":"kid-1","lamport":1,"prevHash":null,"schemaVersion":1},"type":"chore_rewarded"}"""
        assertEquals(expected, CanonicalEncoder.encodeToString(tree))
        assertEquals(V1_GOLDEN_HASH, HashChain().hashOf(tree, prevHash = null))
        // Missing, explicit null and set parentSignature canonicalise identically.
        for (parentSignature in listOf(JsonNull, JsonPrimitive("cGFyZW50"))) {
            val withField = tree.withEnvelopeField("parentSignature", parentSignature)
            assertEquals(expected, CanonicalEncoder.encodeToString(withField))
            assertEquals(V1_GOLDEN_HASH, HashChain().hashOf(withField, prevHash = null))
        }
        // dueOn became required in schema version 2, so this M1 chore no longer decodes into a class.
        assertNull(LedgerJson.decodeOrNull(V1_CHORE_DOCUMENT))
    }

    @Test
    fun aV1EventOfAnUnchangedTypeDecodesAndVerifiesUnchanged() {
        val tree = Json.parseToJsonElement(V1_PURCHASE_DOCUMENT).jsonObject
        val decoded = assertIs<RewardPurchased>(LedgerJson.decode(V1_PURCHASE_DOCUMENT))
        assertEquals(1, decoded.envelope.schemaVersion)
        assertNull(decoded.envelope.parentSignature)
        assertNull(decoded.minutesGranted)
        // The decoded class re-encodes to the stored canonical form, so both verification paths agree.
        assertContentEquals(CanonicalEncoder.encode(tree), CanonicalEncoder.encode(decoded))
        assertEquals(V1_PURCHASE_HASH, HashChain().hashOf(tree, prevHash = null))
        assertEquals(V1_PURCHASE_HASH, HashChain().hashOf(decoded))
        assertIs<ChainVerification.Valid>(HashChain().verifyChain(listOf(decoded)))
        assertContentEquals(signedPayloadOf(tree), decoded.signedPayload())
        // An absent minutesGranted stays absent when written back.
        assertFalse("minutesGranted" in LedgerJson.encode(decoded))
    }

    @Test
    fun aV2EventRoundTripsWithBothSignatures() {
        val stored = LedgerJson.encode(golden)
        val decoded = LedgerJson.decode(stored)
        assertEquals(golden, decoded)
        assertContentEquals("signature".encodeToByteArray(), decoded.signatureBytes())
        assertContentEquals("parent".encodeToByteArray(), decoded.parentSignatureBytes())
        assertEquals(V2_GOLDEN_HASH, HashChain().hashOf(Json.parseToJsonElement(stored).jsonObject, null))
        // The same canonical bytes under two different domains.
        val canonical = CanonicalEncoder.encode(golden)
        val device = decoded.signedPayload()
        val parent = decoded.parentSignedPayload()
        assertFalse(device.contentEquals(parent))
        assertContentEquals(LEDGER_SIGNATURE_DOMAIN.encodeToByteArray() + 0.toByte() + canonical, device)
        assertContentEquals(LEDGER_PARENT_SIGNATURE_DOMAIN.encodeToByteArray() + 0.toByte() + canonical, parent)
        assertContentEquals(parent, parentSignedPayloadOf(Json.parseToJsonElement(stored).jsonObject))
    }

    @Test
    fun hashAndSignaturesAreExcluded() {
        val base = CanonicalEncoder.encode(golden)
        val resigned = golden.mutateEnvelope { it.copy(hash = "other", signature = null, parentSignature = null) }
        assertContentEquals(base, CanonicalEncoder.encode(resigned))
        val text = CanonicalEncoder.encodeToString(golden)
        assertFalse("\"hash\"" in text)
        assertFalse("ignature" in text)
        // A tree without the parentSignature key at all is the same too.
        val tree = LedgerJson.encodeToJsonObject(golden)
        val envelope = tree.getValue("envelope").jsonObject
        assertContentEquals(
            base,
            CanonicalEncoder.encode(JsonObject(tree + ("envelope" to JsonObject(envelope - "parentSignature")))),
        )
    }

    @Test
    fun schemaVersionAndPrevHashAreHashed() {
        val base = CanonicalEncoder.encodeToString(golden)
        val v1 = CanonicalEncoder.encodeToString(golden.mutateEnvelope { it.copy(schemaVersion = 1) })
        val linked = CanonicalEncoder.encodeToString(golden.mutateEnvelope { it.copy(prevHash = "a".repeat(64)) })
        assertFalse(base == v1)
        assertFalse(base == linked)
    }

    @Test
    fun minutesGrantedIsHashedWhenPresent() {
        val purchase = DeviceWriter(Fixtures.kidTablet).screenTime(cost = 100, minutes = 30)
        assertTrue("\"minutesGranted\":30" in CanonicalEncoder.encodeToString(purchase))
        assertFalse(HashChain().hashOf(purchase.copy(minutesGranted = Money(31))) == purchase.envelope.hash)
        assertFalse(HashChain().hashOf(purchase.copy(minutesGranted = null)) == purchase.envelope.hash)
    }

    @Test
    fun everyEventTypeRoundTripsAndKeepsItsCanonicalForm() {
        for (event in allEventTypes()) {
            val stored = LedgerJson.encode(event)
            val decoded = LedgerJson.decode(stored)
            assertEquals(event, decoded)
            assertContentEquals(CanonicalEncoder.encode(event), CanonicalEncoder.encode(decoded))
            // Re-serialising the decoded event is byte-identical too.
            assertEquals(stored, LedgerJson.encode(decoded))
        }
    }

    @Test
    fun canonicalFormIsIndependentOfFieldOrder() {
        val random = Random(7)
        for (event in allEventTypes()) {
            val tree = Json.parseToJsonElement(LedgerJson.encode(event))
            repeat(5) {
                val shuffled = tree.shuffleKeys(random).toString()
                val decoded = LedgerJson.decode(shuffled)
                assertContentEquals(CanonicalEncoder.encode(event), CanonicalEncoder.encode(decoded))
            }
        }
    }

    @Test
    fun keysAreSortedAtEveryDepthWithNoWhitespace() {
        for (event in allEventTypes()) {
            val text = CanonicalEncoder.encodeToString(event)
            assertFalse(text.any { it == ' ' || it == '\n' }, text)
            assertSorted(Json.parseToJsonElement(text))
        }
    }

    @Test
    fun stringsAreEscapedDeterministically() {
        val writer = DeviceWriter(Fixtures.parentPhone)
        val tricky = "quote\" backslash\\ newline\n tab\t bell\u0007 pound£ emoji🌱"
        val event = writer.adjust(5, reason = tricky)
        val text = CanonicalEncoder.encodeToString(event)
        assertEquals(true, "\\\"" in text && "\\\\" in text && "\\n" in text && "\\t" in text && "\\u0007" in text)
        assertEquals(true, "£" in text && "🌱" in text)
        // The canonical form is valid JSON that decodes back to the same reason.
        val reparsed = Json.parseToJsonElement(text) as JsonObject
        assertEquals(tricky, (reparsed.getValue("reason") as JsonPrimitive).content)
    }

    @Test
    fun theTreeOfAnEventEqualsItsClassEncodingForTheCurrentSchema() {
        for (event in allEventTypes()) {
            val stored = Json.parseToJsonElement(LedgerJson.encode(event)).jsonObject
            assertContentEquals(CanonicalEncoder.encode(event), CanonicalEncoder.encode(stored))
            assertEquals(LedgerJson.encodeToJsonObject(event), stored)
        }
        val stored = Json.parseToJsonElement(LedgerJson.encode(golden)).jsonObject
        assertEquals(V2_GOLDEN_HASH, HashChain().hashOf(stored, null))
    }

    @Test
    fun unknownFutureFieldsAreDecodedPastButStillHashedAndSigned() {
        val event = DeviceWriter(Fixtures.parentPhone).chore(100)
        val tree = LedgerJson.encodeToJsonObject(event)
        val envelope = tree.getValue("envelope").jsonObject
        val future = JsonObject(
            tree + ("tip" to JsonPrimitive(5)) +
                ("envelope" to JsonObject(envelope + ("deviceModel" to JsonPrimitive("pixel")))),
        )
        val text = Json.encodeToString(JsonObject.serializer(), future)

        // An older client can still read it ...
        assertEquals(event, LedgerJson.decode(text))
        assertEquals(event, LedgerJson.decodeOrNull(text))
        // ... and the unknown fields stay covered by the hash and the signature input.
        val canonical = CanonicalEncoder.encodeToString(future)
        assertTrue("\"tip\":5" in canonical && "\"deviceModel\":\"pixel\"" in canonical, canonical)
        val reparsed = Json.parseToJsonElement(text).jsonObject
        assertFalse(HashChain().hashOf(reparsed, event.envelope.prevHash) == event.envelope.hash)
        assertFalse(signedPayloadOf(reparsed).contentEquals(event.signedPayload()))
        // Re-encoding the decoded event would silently drop them; that is why verification uses the stored tree.
        assertFalse("tip" in LedgerJson.encode(LedgerJson.decode(text)))
    }

    @Test
    fun decodeOrNullSkipsBadRowsInsteadOfThrowing() {
        val good = LedgerJson.encode(golden)
        val rows = listOf(
            good,
            "not json",
            "{}",
            good.replace("chore_rewarded", "from_the_future"),
            good.replace("\"kid-1\"", "\"kid 1\""),
            good.replace("\"lamport\":1", "\"lamport\":-1"),
            "[]",
        )
        assertEquals(listOf(golden), rows.mapNotNull(LedgerJson::decodeOrNull))
        assertEquals(golden, LedgerJson.decodeOrNull(Json.parseToJsonElement(good)))
        assertEquals(null, LedgerJson.decodeOrNull(Json.parseToJsonElement("[1]")))
    }

    @Test
    fun unpairedSurrogatesCannotBeEncoded() {
        val bad = golden.copy(choreId = "x\uD800")
        assertFailsWith<CharacterCodingException> { CanonicalEncoder.encode(bad) }
        assertFailsWith<CharacterCodingException> { HashChain().hashOf(bad) }
        assertFailsWith<CharacterCodingException> { bad.signedPayload() }
        // A paired surrogate (an emoji) is fine.
        CanonicalEncoder.encode(golden.copy(choreId = "plant \uD83C\uDF31"))
    }

    @Test
    fun nonCanonicalDocumentsAreRejected() {
        val tree = LedgerJson.encodeToJsonObject(golden)
        assertFailsWith<IllegalArgumentException> {
            CanonicalEncoder.encode(JsonObject(tree + ("amount" to JsonPrimitive(1.5))))
        }
        assertFailsWith<IllegalArgumentException> {
            CanonicalEncoder.encode(
                Json.parseToJsonElement(LedgerJson.encode(golden).replace(":150", ":1.5e2")).jsonObject,
            )
        }
        assertFailsWith<IllegalArgumentException> { CanonicalEncoder.encode(JsonObject(tree - "envelope")) }
    }

    private fun assertSorted(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                assertEquals(element.keys.sorted(), element.keys.toList())
                element.values.forEach(::assertSorted)
            }

            is JsonArray -> element.forEach(::assertSorted)

            else -> Unit
        }
    }

    private fun JsonObject.withEnvelopeField(key: String, value: JsonElement): JsonObject =
        JsonObject(this + ("envelope" to JsonObject(getValue("envelope").jsonObject + (key to value))))

    private fun JsonElement.shuffleKeys(random: Random): JsonElement = when (this) {
        is JsonObject -> JsonObject(entries.shuffled(random).associate { (k, v) -> k to v.shuffleKeys(random) })
        is JsonArray -> JsonArray(map { it.shuffleKeys(random) })
        else -> this
    }

    companion object {
        /** Independently computed v2 hash of [golden]. */
        const val V2_GOLDEN_HASH = "ce2c5c6d8d9bef89cff6b9d7ba4e16eaa85325f3d6c9b41826563588f67804ce"

        /** The M1 (schema version 1) golden hash; must never change. */
        const val V1_GOLDEN_HASH = "c99659253522d76f9b793725e9cc3453c77d50347a7ba97e37ff25ad0cbbe2d9"

        /** Independently computed hash of [V1_PURCHASE_DOCUMENT]. */
        const val V1_PURCHASE_HASH = "fa1100a8379b0fc065f84462ba980b94e45f8bd2b3fe3f49419c55ec3c074bf9"

        /** The M1 golden chore exactly as M1's [LedgerJson] stored it (no `dueOn`, no `parentSignature`). */
        val V1_CHORE_DOCUMENT = """{"type":"chore_rewarded","envelope":{"eventId":""" +
            """"00000000-0000-4000-8000-000000000001","householdId":"house-1","kidId":"kid-1",""" +
            """"accountId":"main-1","authorUid":"parent-uid","authorRole":"PARENT","authorDeviceId":"parent-phone",""" +
            """"createdAt":1793606400000,"lamport":1,"prevHash":null,"hash":"$V1_GOLDEN_HASH",""" +
            """"signature":"c2lnbmF0dXJl","schemaVersion":1},"currency":"GBP","amount":150,"choreId":"dishes"}"""

        /** A schema version 1 purchase as M1 stored it: a type whose v1 shape is still complete. */
        val V1_PURCHASE_DOCUMENT = """{"type":"reward_purchased","envelope":{"eventId":""" +
            """"00000000-0000-4000-8000-000000000002","householdId":"house-1","kidId":"kid-1",""" +
            """"accountId":"main-1","authorUid":"kid-uid","authorRole":"KID","authorDeviceId":"kid-tablet",""" +
            """"createdAt":1793606400000,"lamport":2,"prevHash":null,"hash":"$V1_PURCHASE_HASH",""" +
            """"signature":null,"schemaVersion":1},"currency":"GBP","amount":399,"rewardId":"sticker"}"""

        fun allEventTypes(): List<LedgerEvent> {
            val parent = DeviceWriter(Fixtures.parentPhone, parentKey = FakeParentAuthorization())
            val kid = DeviceWriter(Fixtures.kidTablet)
            val chore = parent.chore(100)
            val screenTime = kid.screenTime(cost = 50, minutes = 20)
            return listOf(
                chore,
                parent.allowance(500),
                kid.game(10),
                kid.purchase(50),
                screenTime,
                kid.timeCredit(20, grantedBy = screenTime),
                parent.timeCredit(15),
                parent.adjust(-20),
                kid.transfer(30),
                parent.interest(4),
                kid.timeStart("s1"),
                kid.timeStop("s1", 15),
                parent.compensate(25, listOf(chore.eventId)).withSignature(byteArrayOf(1, 2, 3)),
            )
        }
    }
}

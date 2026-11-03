package dev.kpetriv.sprout.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IdsTest {
    private val constructors: List<(String) -> Any> = listOf(
        ::HouseholdId,
        ::KidId,
        ::AccountId,
        ::DeviceId,
        ::UserId,
        ::MessageId,
        ::ThreadId,
        ::BlobId,
    )

    @Test
    fun idsSerialiseAsBareStrings() {
        assertEquals("\"h-1\"", Json.encodeToString(HouseholdId.serializer(), HouseholdId("h-1")))
        assertEquals(KidId("k-1"), Json.decodeFromString(KidId.serializer(), "\"k-1\""))
        assertEquals(AccountId("a"), Json.decodeFromString(AccountId.serializer(), "\"a\""))
        assertEquals(DeviceId("d"), Json.decodeFromString(DeviceId.serializer(), "\"d\""))
        assertEquals(UserId("u"), Json.decodeFromString(UserId.serializer(), "\"u\""))
    }

    @Test
    fun toStringIsTheRawValue() {
        assertEquals("abc", UserId("abc").toString())
    }

    @Test
    fun theFullAllowedAlphabetIsAccepted() {
        val alphabet = ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('.', '_', '-')
        for (create in constructors) {
            create(alphabet.joinToString(""))
            create("x".repeat(MAX_ID_LENGTH))
            create("0")
            // Firebase Auth uids and lower-case UUIDs are valid ids.
            create("Xy3kPq9ZbN2mLwT4vR8sJ1hG6fD0")
            create("00000000-0000-4000-8000-000000000001")
        }
    }

    @Test
    fun blankOverlongOrNonAsciiIdsAreRejected() {
        val bad = listOf(
            "",
            "   ",
            "a b",
            "households/x",
            "x".repeat(MAX_ID_LENGTH + 1),
            "tab\there",
            "new\nline",
            "colon:id",
            "plus+id",
            "café",
            "Аdmin", // Cyrillic A, a look-alike of Latin A
            "emoji🌱",
            "x\uD800",
            "nul\u0000",
        )
        for (create in constructors) {
            for (value in bad) {
                assertFailsWith<IllegalArgumentException>("accepted '$value'") { create(value) }
            }
        }
    }

    @Test
    fun asciiOrderEqualsByteOrder() {
        // Every allowed character is single-byte UTF-8, so String order is SQL BINARY (byte) order.
        val ids = listOf("a", "B", "_", "-", ".", "9", "Z", "z", "a.b", "a-b", "a_b", "A")
        val byString = ids.sorted()
        val byBytes = ids.sortedWith { x, y ->
            val a = x.encodeToByteArray()
            val b = y.encodeToByteArray()
            (0 until minOf(a.size, b.size)).firstNotNullOfOrNull { i ->
                (a[i].toInt() and 0xFF).compareTo(b[i].toInt() and 0xFF).takeIf { it != 0 }
            } ?: a.size.compareTo(b.size)
        }
        assertEquals(byBytes, byString)
        ids.forEach { assertEquals(it.length, it.encodeToByteArray().size) }
    }

    @Test
    fun decodingValidatesToo() {
        assertFailsWith<IllegalArgumentException> { Json.decodeFromString(KidId.serializer(), "\"\"") }
        assertFailsWith<IllegalArgumentException> { Json.decodeFromString(KidId.serializer(), "\"a b\"") }
    }

    @Test
    fun roleSerialisesByName() {
        assertEquals("\"PARENT\"", Json.encodeToString(Role.serializer(), Role.PARENT))
        assertEquals(Role.KID, Json.decodeFromString(Role.serializer(), "\"KID\""))
    }

    @Test
    fun chatIdsSerialiseAsBareStrings() {
        assertEquals("\"m-1\"", Json.encodeToString(MessageId.serializer(), MessageId("m-1")))
        assertEquals(ThreadId("k-1"), Json.decodeFromString(ThreadId.serializer(), "\"k-1\""))
        assertEquals(BlobId("b"), Json.decodeFromString(BlobId.serializer(), "\"b\""))
    }

    @Test
    fun threadIdIsTheKidId() {
        val kid = KidId("kid-7")
        assertEquals("kid-7", ThreadId.of(kid).value)
        assertEquals(kid, ThreadId.of(kid).kidId)
    }
}

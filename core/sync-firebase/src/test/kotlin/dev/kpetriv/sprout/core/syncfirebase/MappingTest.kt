package dev.kpetriv.sprout.core.syncfirebase

import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.sync.SyncError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class MappingTest {
    @Test
    fun `claims parse the backend's custom claims and ignore malformed ones`() {
        assertEquals(
            Claims(HouseholdId("h1"), Role.KID, KidId("kid-a")),
            Claims.from(mapOf("hid" to "h1", "role" to "KID", "kidId" to "kid-a", "iss" to "x")),
        )
        assertEquals(Claims.NONE, Claims.from(mapOf("hid" to "a/b", "role" to "ADMIN", "kidId" to 3)))
        assertEquals(
            Claims(HouseholdId("h1"), Role.PARENT, null),
            Claims.from(mapOf("hid" to "h1", "role" to "PARENT")),
        )
    }

    @Test
    fun `firestore errors map to sync errors`() {
        assertEquals(SyncError.Unauthenticated, FirebaseSyncTransport.syncErrorOf(FirestoreCode.UNAUTHENTICATED))
        assertEquals(
            SyncError.Permanent("permission-denied"),
            FirebaseSyncTransport.syncErrorOf(FirestoreCode.PERMISSION_DENIED),
        )
        assertEquals(
            SyncError.Permanent("invalid-argument"),
            FirebaseSyncTransport.syncErrorOf(FirestoreCode.INVALID_ARGUMENT),
        )
        assertEquals(SyncError.Transient(60_000), FirebaseSyncTransport.syncErrorOf(FirestoreCode.RESOURCE_EXHAUSTED))
        listOf(
            FirestoreCode.UNAVAILABLE,
            FirestoreCode.TIMEOUT,
            FirestoreCode.ABORTED,
            FirestoreCode.INTERNAL,
        ).forEach {
            assertEquals(SyncError.Transient(), FirebaseSyncTransport.syncErrorOf(it))
        }
        assertEquals(FirestoreCode.UNKNOWN, FirestoreCode.fromName("SOMETHING_NEW"))
    }

    @Test
    fun `cursors round-trip with nanosecond server times and reject foreign values`() {
        val position = QueryPosition(ServerTime(1_762_000_000, 123_456_000), "evt-1__abc")

        assertEquals(position, CursorCodec.decode(CursorCodec.encode(position)))
        assertEquals("2025-11-01T12:26:40.123456Z|evt-1__abc", CursorCodec.encode(position))
        assertNull(CursorCodec.decode("17"))
        assertNull(CursorCodec.decode("|x"))
        assertNull(CursorCodec.decode("2025-11-01T12:26:40Z|"))
        assertNull(CursorCodec.decode("yesterday|x"))
    }

    @Test
    fun `layout paths follow the spec`() {
        val hid = HouseholdId("h1")
        assertEquals("households/h1/events/e1", FirestoreLayout.event(hid, "e1"))
        assertEquals("households/h1/events/e1__ff", FirestoreLayout.conflictingEvent(hid, "e1", "ff"))
        assertEquals("choreSubmissions", FirestoreLayout.collectionOf(DocTypes.CHORE_SUBMISSION))
        assertNull(FirestoreLayout.collectionOf("parent_key"), "parent keys are registry entries, not documents")
        assertNull(FirestoreLayout.collectionOf("gadget"))
        assertEquals(
            listOf(
                "kids", "accounts", "householdPolicy", "chores", "choreSubmissions", "rewards", "allowanceSchedules",
                "savingsGoals", "interestPolicy",
            ),
            FirestoreLayout.documentCollections.map { it.second },
        )
    }

    @Test
    fun `plain values keep integers as longs both ways`() {
        val tree = JsonObject(mapOf("a" to JsonPrimitive(3), "b" to JsonPrimitive(1.5), "c" to JsonPrimitive(true)))

        val plain = PlainValues.toPlainMap(tree)

        assertEquals(mapOf("a" to 3L, "b" to 1.5, "c" to true), plain)
        assertEquals(tree, PlainValues.toJsonObject(plain))
        assertEquals(JsonPrimitive(1_762_000_600_000), PlainValues.toJson(1.7620006E12, wholeDoublesAsLongs = true))
        assertEquals(JsonPrimitive(2.0), PlainValues.toJson(2.0))
    }

    @Test
    fun `the registry digest changes with any field and ignores order`() {
        val a = FacadeDocument("a", mapOf("status" to "ACTIVE", "x" to mapOf("k" to 1L, "j" to 2L)))
        val b = FacadeDocument("b", mapOf("status" to "PENDING"))

        assertEquals(StoredShapes.digestOf(listOf(a, b)), StoredShapes.digestOf(listOf(b, a)))
        assertEquals(
            StoredShapes.digestOf(listOf(a)),
            StoredShapes.digestOf(
                listOf(
                    FacadeDocument(
                        "a",
                        mapOf(
                            "x" to mapOf("j" to 2L, "k" to 1L),
                            "status" to "ACTIVE",
                        ),
                    ),
                ),
            ),
        )
        assertNotEquals(
            StoredShapes.digestOf(listOf(a, b)),
            StoredShapes.digestOf(listOf(a, b.copy(data = mapOf("status" to "ACTIVE")))),
        )
    }
}

package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.model.Role
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The fake server refuses what `firestore/firestore.rules` refuses for registry collections. */
class FakeBackendRulesTest {
    private val parent = TestIdentity("parent-device", "parent-uid", Role.PARENT)
    private val backend = FakeBackend(HOUSEHOLD).apply { registerDevice(parent.registered) }
    private val transport = FakeSyncTransport(backend, parent.deviceId, parent.uid, parent.role)

    @Test
    fun aParentCannotWriteRegistryDocuments() = runTest {
        val documents = FakeBackend.SERVER_ONLY.map { parent.signed(it, "c".repeat(64), lamport = 1) }

        val result = transport.push(emptyList(), documents)

        assertTrue(result.accepted.isEmpty())
        assertEquals(
            documents.map { PushItemId.ofDocument(it) to SyncError.Permanent(FakeBackend.PERMISSION_DENIED) },
            result.rejected,
        )
        assertTrue(backend.documents().isEmpty())
    }

    @Test
    fun everyClientTypeIsWritableByAParent() = runTest {
        val documents = DocTypes.all.map { parent.signed(it, "doc-1", lamport = 1) }

        val result = transport.push(emptyList(), documents)

        assertEquals(emptyList(), result.rejected)
        assertEquals(DocTypes.all.toSet(), backend.documents().map { it.docType }.toSet())
    }

    @Test
    fun noClientTypeIsAServerOnlyCollection() {
        assertEquals(9, DocTypes.all.size)
        assertTrue(DocTypes.all.none { it in FakeBackend.SERVER_ONLY })
    }

    @Test
    fun anUnknownTypeIsRefusedBeforeTheRules() = runTest {
        val document = parent.signed("gift_card", "doc-1", lamport = 1)

        val result = transport.push(emptyList(), listOf(document))

        assertEquals(listOf(SyncError.Permanent(FakeBackend.UNKNOWN_DOC_TYPE)), result.rejected.map { it.second })
    }
}

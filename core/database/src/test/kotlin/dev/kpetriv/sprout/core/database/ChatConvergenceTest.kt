package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.MessageStatus
import dev.kpetriv.sprout.core.domainledger.JvmSha256Hasher
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.domainledger.chat.ChatPhotoRef
import dev.kpetriv.sprout.core.domainledger.chat.MessagePlaintext
import dev.kpetriv.sprout.core.domainledger.toHex
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.sync.FakeBackend
import dev.kpetriv.sprout.core.sync.FakeSyncClock
import dev.kpetriv.sprout.core.sync.FakeSyncTransport
import dev.kpetriv.sprout.core.sync.SyncEngine
import dev.kpetriv.sprout.core.sync.SyncReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Chat between two (then three) real Room devices over one [FakeBackend] (M4 §7): parent and kid exchange messages
 * and a photo; the backend only ever holds ciphertext; revoking the kid device rotates the thread key so the revoked
 * device cannot read the next epoch; a parent device added later reads the current epoch only (D-M4-10).
 */
@RunWith(RobolectricTestRunner::class)
class ChatConvergenceTest {
    private val parent = parentDevice()
    private val kid = kidDevice()
    private val secondParent = TestHousehold(
        RuntimeEnvironment.getApplication(),
        sessionFor = TestHousehold.parentSession(HOME, "parent-2"),
    )
    private val backend = FakeBackend(HOME)
    private val clock = FakeSyncClock()
    private val parentTransport = FakeSyncTransport(backend, parent.deviceId, parent.session.uid, Role.PARENT)
    private val parentEngine = SyncEngine(parentTransport, parent.outbox, parent.applier, clock)
    private val kidEngine = SyncEngine(
        FakeSyncTransport(backend, kid.deviceId, kid.session.uid, Role.KID),
        kid.outbox,
        kid.applier,
        clock,
    )
    private val secondEngine = SyncEngine(
        FakeSyncTransport(backend, secondParent.deviceId, secondParent.session.uid, Role.PARENT),
        secondParent.outbox,
        secondParent.applier,
        clock,
    )

    @After
    fun close() {
        parent.close()
        kid.close()
        secondParent.close()
    }

    private suspend fun SyncEngine.synced(): SyncReport = syncOnce().also { assertNull(it.error, it.toString()) }

    /** Both devices registered with their encryption keys; the parent creates the kid and its thread's epoch 0. */
    private suspend fun household() {
        backend.registerDevice(parent.registeredWithKey())
        backend.registerDevice(kid.registeredWithKey())
        parentEngine.synced()
        parent.addKid(KID_X.value)
        assertEquals(1, parent.threadKeyMaintenance())
        parentEngine.synced()
        kidEngine.synced()
    }

    /** Everything the backend stores for chat, as text. */
    private fun backendText(): String = (
        backend.messages().map { LedgerJson.json.encodeToString(JsonObject.serializer(), it) } +
            backend.documents().filter { it.docType == DocTypes.THREAD_KEY }.map { it.payload.toString() } +
            backend.blobs().map { LedgerJson.json.encodeToString(JsonObject.serializer(), it.header) }
        ).joinToString("\n")

    @Test
    fun parentAndKidExchangeMessagesAndAPhotoWhileTheBackendHoldsOnlyCiphertext() = runTest {
        household()
        val fromParent = parent.messageWrites.send(THREAD_X, text("Dinner at six, love"))
        parentEngine.synced()
        kidEngine.synced()
        val preset = kid.messageWrites.send(THREAD_X, MessagePlaintext.Preset("thank-you", "🌟"))
        val photo = Random(11).nextBytes(48 * 1024)
        val blobId = kid.blobWrites.store(BlobTarget.Thread(THREAD_X), photo)
        val photoMessage = kid.messageWrites.send(
            THREAD_X,
            MessagePlaintext.Photo(ChatPhotoRef(JvmSha256Hasher.sha256(photo).toHex(), 320, 240), "My drawing"),
            blobId,
        )
        kidEngine.synced()
        parentEngine.synced()

        assertEquals(text("Dinner at six, love"), kid.row(fromParent.value)?.content())
        assertEquals(MessagePlaintext.Preset("thank-you", "🌟"), parent.row(preset.value)?.content())
        assertEquals(MessageStatus.RECEIVED, parent.row(photoMessage.value)?.status)
        val fetched = parentTransport.fetchBlob(blobId).getOrThrow()
        assertContentEquals(photo, parent.chatBlobs.ingest(blobId, fetched))

        val stored = backendText()
        for (secret in listOf("Dinner at six", "thank-you", "My drawing")) assertFalse(secret in stored, secret)
        val key = requireNotNull(parent.database.threadKeyDao().all().single().key)
        assertFalse(Base64.Default.encode(key) in stored, "the thread key never travels in the clear")
        val window = photo.copyOfRange(0, 32)
        assertTrue(
            backend.blobs().none { chunk ->
                chunk.data.toList().windowed(window.size).any { it == window.toList() }
            },
            "blob bytes are ciphertext",
        )
    }

    @Test
    fun revokingTheKidRotatesSoItCannotReadTheNextEpochAndALaterParentReadsOnlyTheCurrentOne() = runTest {
        household()
        val before = parent.messageWrites.send(THREAD_X, text("before"))
        parentEngine.synced()
        kidEngine.synced()
        assertEquals(text("before"), kid.row(before.value)?.content())

        backend.revoke(kid.deviceId, atLamport = 10_000)
        parentEngine.synced()
        assertEquals(1, parent.threadKeyMaintenance())
        val after = parent.messageWrites.send(THREAD_X, text("after"))
        parentEngine.synced()

        // Whatever the revoked device still gets to see (a copy of the backend), it cannot read epoch 1.
        kid.applier.applyDocuments(backend.documents())
        kid.applier.applyMessages(backend.messages())
        assertEquals(1, kid.row(after.value)?.epoch)
        assertEquals(MessageStatus.LOCKED, kid.row(after.value)?.status)
        assertTrue(kid.database.threadKeyDao().all().filter { it.epoch == 1L }.all { it.key == null })

        // A second parent device joins: the parent re-wraps the current epoch for it.
        backend.registerDevice(secondParent.registeredWithKey())
        parentEngine.synced()
        assertEquals(1, parent.threadKeyMaintenance())
        parentEngine.synced()
        secondEngine.synced()

        assertEquals(text("after"), secondParent.row(after.value)?.content())
        assertEquals(MessageStatus.LOCKED, secondParent.row(before.value)?.status, "older epochs are not re-wrapped")
    }
}

package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.TinkX25519KeyAgreement
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.MessageStatus
import dev.kpetriv.sprout.core.database.entity.ThreadKeyEntity
import dev.kpetriv.sprout.core.database.usecase.WipeLocalDataUseCase
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
import kotlinx.coroutines.Dispatchers
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
import kotlin.time.Instant

/**
 * Chat between two (then three) real Room devices over one [FakeBackend] (M4 §7): parent and kid exchange messages
 * and a photo; the backend only ever holds ciphertext; revoking the kid device rotates the thread key so the revoked
 * device cannot read the next epoch; a parent device added later reads the current epoch only (D-M4-10); a parent
 * phone signed out and restored reads what the server kept and goes on above what the kid has seen (D-REL-25).
 */
@RunWith(RobolectricTestRunner::class)
class ChatConvergenceTest {
    private val parent = parentDevice()

    /** The kid's phone runs Tink's X25519 (an API 28-32 device, D-M4-1); every parent runs JCE. */
    private val kid = TestHousehold(
        RuntimeEnvironment.getApplication(),
        sessionFor = TestHousehold.kidSession(HOME, KID_UID, KID_X),
        agreement = ::TinkX25519KeyAgreement,
    )
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

    /** A parent's sync as the app runs it: the sync, then the thread key upkeep (`ChatMaintenance.afterSync`). */
    private suspend fun TestHousehold.syncWith(engine: SyncEngine): Int {
        engine.synced()
        return threadKeyMaintenance()
    }

    /** Both devices registered with their encryption keys; the parent creates the kid and its thread's epoch 0. */
    private suspend fun household() {
        backend.registerDevice(parent.registeredWithKey())
        backend.registerDevice(kid.registeredWithKey())
        parentEngine.synced()
        parent.addKid(KID_X.value)
        assertEquals(1, parent.threadKeyMaintenance())
        // Pushed; the upkeep after that sync confirms the key (the push was accepted).
        assertEquals(0, parent.syncWith(parentEngine))
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
        val expected = BlobExpectation(
            BlobTarget.Thread(THREAD_X),
            kid.deviceId,
            JvmSha256Hasher.sha256(photo).toHex(),
        )
        assertContentEquals(photo, parent.chatBlobs.ingest(blobId, fetched, expected))

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
        assertEquals(1, parent.syncWith(parentEngine))
        assertEquals(0, parent.syncWith(parentEngine))
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
        assertEquals(1, parent.syncWith(parentEngine))
        parentEngine.synced()
        secondEngine.synced()

        assertEquals(text("after"), secondParent.row(after.value)?.content())
        assertEquals(MessageStatus.LOCKED, secondParent.row(before.value)?.status, "older epochs are not re-wrapped")
    }

    @Test
    fun twoParentsRotatingAtOnceBothKeepWorkingAndNothingStaysLocked() = runTest {
        // Two parent phones and the kid, all with keys; the first parent creates epoch 0.
        backend.registerDevice(parent.registeredWithKey())
        backend.registerDevice(secondParent.registeredWithKey())
        backend.registerDevice(kid.registeredWithKey())
        parentEngine.synced()
        parent.addKid(KID_X.value)
        assertEquals(1, parent.threadKeyMaintenance())
        assertEquals(0, parent.syncWith(parentEngine))
        secondEngine.synced()
        kidEngine.synced()
        assertEquals(0, secondParent.threadKeyMaintenance(), "the first key already wraps for everyone")

        // A fourth device that both parents see revoked before either has seen the other's rotation.
        val oldKid = kidDevice()
        backend.registerDevice(oldKid.registeredWithKey())
        assertEquals(1, parent.syncWith(parentEngine), "re-wrap of epoch 0 for the old kid phone")
        secondEngine.synced()
        backend.revoke(oldKid.deviceId, atLamport = 10_000)
        parentEngine.synced()
        secondEngine.synced()
        assertEquals(1, parent.threadKeyMaintenance())
        assertEquals(1, secondParent.threadKeyMaintenance())
        val epochOne: suspend (TestHousehold) -> List<ThreadKeyEntity> =
            { device -> device.database.threadKeyDao().all().filter { it.epoch == 1L } }
        assertEquals(1, epochOne(parent).size)
        assertEquals(1, epochOne(secondParent).size)

        // Both push (each confirmed by its own upkeep) and each sends under its own key.
        parent.syncWith(parentEngine)
        secondParent.syncWith(secondEngine)
        val fromFirst = parent.messageWrites.send(THREAD_X, text("from the first phone"))
        val fromSecond = secondParent.messageWrites.send(THREAD_X, text("from the second phone"))
        parent.syncWith(parentEngine)
        secondParent.syncWith(secondEngine)
        parent.syncWith(parentEngine)
        kidEngine.synced()
        val fromKid = kid.messageWrites.send(THREAD_X, text("from the kid"))
        kidEngine.synced()
        parent.syncWith(parentEngine)
        secondParent.syncWith(secondEngine)

        // Two documents for epoch 1, both kept everywhere; nothing stays locked and nobody rotates again.
        assertEquals(
            2,
            backend.documents().count {
                it.docType == DocTypes.THREAD_KEY &&
                    it.docId.startsWith("${KID_X.value}.1.")
            },
        )
        for (device in listOf(parent, secondParent, kid)) {
            assertEquals(2, epochOne(device).count { it.key != null }, "both epoch 1 keys on every member")
            assertTrue(device.database.messageDao().all().none { it.status == MessageStatus.LOCKED })
        }
        assertEquals(text("from the second phone"), parent.row(fromSecond.value)?.content())
        assertEquals(text("from the first phone"), secondParent.row(fromFirst.value)?.content())
        assertEquals(text("from the kid"), parent.row(fromKid.value)?.content())
        assertEquals(text("from the kid"), secondParent.row(fromKid.value)?.content())
        assertEquals(text("from the first phone"), kid.row(fromFirst.value)?.content())
        assertEquals(text("from the second phone"), kid.row(fromSecond.value)?.content())
        assertEquals(0, parent.threadKeyMaintenance())
        assertEquals(0, secondParent.threadKeyMaintenance())
        oldKid.close()
    }

    /**
     * The parent's sign-out wipe keeping its device identity, then the same parent restoring the same phone: a fresh
     * database for the same device key and secret store, seeded from the kept record, with its own sync engine.
     */
    private suspend fun signOutAndRestoreParent(): Pair<TestHousehold, SyncEngine> {
        val wipe = WipeLocalDataUseCase(
            parent.database,
            parent.sessions,
            parent.files,
            parent.chatIdentity,
            Dispatchers.Unconfined,
        )
        assertTrue(wipe(keepDeviceIdentity = true))
        val restored = TestHousehold(
            RuntimeEnvironment.getApplication(),
            keyStore = parent.keyStore,
            sessionFor = TestHousehold.parentSession(HOME, PARENT_UID),
            secretStore = parent.secretStore,
        )
        assertEquals(parent.deviceId, restored.deviceId)
        restored.chatIdentity.seed(restored.session)
        assertNull(restored.chatIdentity.kept(), "the record is used once")
        val engine = SyncEngine(
            FakeSyncTransport(backend, restored.deviceId, restored.session.uid, Role.PARENT),
            restored.outbox,
            restored.applier,
            clock,
        )
        return restored to engine
    }

    /**
     * The parent signs out (Settings → Sign out: a wipe that keeps the device identity, D-REL-6) after the server lost
     * its own messages (here "Delete chat history now"; a retention prune does the same), and signs in again on the
     * same phone: a fresh database for the same device id and the same secret store (D-REL-25, review H2, M1).
     */
    @Test
    fun aParentRestoredAfterASignOutReadsTheHistoryLeftAndSendsAboveWhatTheKidHasSeen() = runTest {
        var serverNow = 1_800_000_000_000L
        backend.nowEpochMs = { serverNow }
        household()
        val first = parent.messageWrites.send(THREAD_X, text("one"))
        val second = parent.messageWrites.send(THREAD_X, text("two"))
        parent.syncWith(parentEngine)
        kidEngine.synced()
        assertEquals(text("two"), kid.row(second.value)?.content())
        val seenByKid = requireNotNull(kid.database.messageDao().seq(THREAD_X.value, parent.deviceId))
        assertEquals(3, seenByKid.nextSeq)
        serverNow += 60_000
        val fromKid = kid.messageWrites.send(THREAD_X, text("from the kid"))
        kidEngine.synced()
        parent.syncWith(parentEngine)

        // Everything the parent sent is deleted on the server; the kid's later message stays.
        backend.pruneMessages(parent.deviceId, Instant.fromEpochMilliseconds(serverNow - 30_000), purge = true)
            .getOrThrow()
        assertEquals(1, backend.messages().size)

        val (restored, restoredEngine) = signOutAndRestoreParent()
        try {
            restored.syncWith(restoredEngine)

            // The history the server still has comes back readable; what it deleted does not.
            assertEquals(MessageStatus.RECEIVED, restored.row(fromKid.value)?.status)
            assertEquals(text("from the kid"), restored.row(fromKid.value)?.content())
            assertNull(restored.row(first.value))

            val third = restored.messageWrites.send(THREAD_X, text("three"))
            val sent = requireNotNull(restored.row(third.value))
            assertEquals(3, sent.seq, "never a seq the kid already has from this device")
            assertTrue(sent.lamport > seenByKid.lastLamport, "and a lamport above the last one the kid accepted")
            restored.syncWith(restoredEngine)
            kidEngine.synced()

            assertEquals(MessageStatus.RECEIVED, kid.row(third.value)?.status)
            assertEquals(text("three"), kid.row(third.value)?.content())
            assertEquals(0, kid.database.auditLogDao().countSince(AuditAction.MESSAGE_EQUIVOCATION, third.value, 0))
            assertEquals(0, kid.database.auditLogDao().countSince(AuditAction.MESSAGE_QUARANTINED, third.value, 0))
        } finally {
            restored.close()
        }
    }
}

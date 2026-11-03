package dev.kpetriv.sprout.chat

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import dev.kpetriv.sprout.core.database.TestHousehold
import dev.kpetriv.sprout.core.database.entity.OutboxEntity
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.domainledger.AttestationLevel
import dev.kpetriv.sprout.core.domainledger.DeviceOwner
import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.model.BlobId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.sync.BlobChunkJson
import dev.kpetriv.sprout.core.sync.DocumentJsonCodec
import dev.kpetriv.sprout.core.sync.SyncError
import dev.kpetriv.sprout.core.sync.SyncException
import dev.kpetriv.sprout.core.sync.SyncTransport
import dev.kpetriv.sprout.feature.chat.ChatMode
import dev.kpetriv.sprout.feature.chat.ChatViewModel
import dev.kpetriv.sprout.feature.chat.MessageDirection
import dev.kpetriv.sprout.feature.chat.MessageKind
import dev.kpetriv.sprout.feature.chat.OutgoingMessage
import dev.kpetriv.sprout.feature.chat.SendState
import dev.kpetriv.sprout.feature.chat.ThreadKeyState
import dev.kpetriv.sprout.feature.chat.VoiceClip
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The chat port adapters over two real Room devices (a parent's phone and the kid's), as the screens use them. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class) // Not the Hilt app: its startup opens the encrypted database.
class ChatAdaptersTest {
    private val home = HouseholdId("home-1")
    private val kidId = KidId("kid-leo")
    private val context = RuntimeEnvironment.getApplication()
    private val parent = TestHousehold(context, sessionFor = TestHousehold.parentSession(home, "parent-uid"))
    private val kid = TestHousehold(context, sessionFor = TestHousehold.kidSession(home, "kid-uid", kidId))

    @After
    fun close() {
        parent.close()
        kid.close()
    }

    private fun perspective(mode: ChatMode?) =
        ChatPerspective(SavedStateHandle(mode?.let { mapOf(ChatViewModel.MODE_KEY to it.name) }.orEmpty()))

    private fun TestHousehold.reader(mode: ChatMode?) =
        RoomMessageReader(messageReader, chatThreads, sessions, perspective(mode))

    private fun TestHousehold.sender(mode: ChatMode?) = RoomMessageSender(messageWrites, blobWrites, perspective(mode))

    private suspend fun TestHousehold.registered() = RegisteredDevice(
        deviceId = deviceId,
        owner = DeviceOwner(session.uid, session.role, session.kidId),
        publicKey = keyStore.publicKey().encoded,
        status = DeviceStatus.Active,
        attestation = AttestationLevel.NONE,
        encryptionKey = boundEncryptionKey(),
    )

    private suspend fun TestHousehold.queued(kind: OutboxKind) = database.outboxDao().all().filter { it.kind == kind }

    private suspend fun TestHousehold.pullFrom(other: TestHousehold) {
        val documents = other.queued(OutboxKind.DOCUMENT)
            .map { requireNotNull(DocumentJsonCodec.decodeOrNull(it.payload)) }
        applier.applyDocuments(documents)
        applier.applyMessages(other.queued(OutboxKind.MESSAGE).map { it.json() })
    }

    private fun OutboxEntity.json() = LedgerJson.json.parseToJsonElement(payload) as JsonObject

    private suspend fun linked() {
        val devices = listOf(parent.registered(), kid.registered())
        parent.applier.applyRegistry(devices, emptyList())
        kid.applier.applyRegistry(devices, emptyList())
        parent.addKid(kidId.value)
        parent.threadKeyMaintenance()
        kid.pullFrom(parent)
    }

    /** A transport that serves [from]'s queued blob chunks, as the backend would after its push. */
    private fun transportServing(from: TestHousehold?) = object : SyncTransport {
        override suspend fun fetchBlob(blobId: BlobId): Result<List<BlobChunkJson>> = if (from == null) {
            Result.failure(SyncException(SyncError.Transient()))
        } else {
            Result.success(
                from.queued(OutboxKind.BLOB_CHUNK)
                    .map { BlobChunkJson(it.json(), requireNotNull(it.data)) }
                    .filter { (it.header["blobId"] as? JsonPrimitive)?.content == blobId.value },
            )
        }

        override suspend fun isAvailable(): Boolean = true
    }

    @Test
    fun `parent and kid see each other's messages from their own side`() = runTest {
        linked()
        assertEquals(ThreadKeyState.USABLE, RoomThreadKeyStatus(kid.threadKeyStatus).observe(kidId).first())

        assertTrue(parent.sender(ChatMode.PARENT).send(kidId, OutgoingMessage.Text("Dinner at six")).isSuccess)
        val mine = parent.reader(ChatMode.PARENT).observe(kidId, 50).first().messages.single()
        assertEquals(MessageDirection.MINE, mine.direction)
        assertEquals("Dinner at six", mine.text)
        assertEquals(SendState.SENDING, mine.sendState, "queued until the outbox is pushed")

        kid.pullFrom(parent)
        val received = kid.reader(ChatMode.KID).observe(kidId, 50).first().messages.single()
        assertEquals(MessageDirection.THEIRS, received.direction)
        assertEquals("Dinner at six", received.text)
        assertNull(received.authorName)

        assertTrue(kid.sender(ChatMode.KID).send(kidId, OutgoingMessage.Preset("love_you", "❤️")).isSuccess)
        parent.pullFrom(kid)
        val preset = parent.reader(ChatMode.PARENT).observe(kidId, 50).first().messages.last()
        assertEquals(MessageDirection.THEIRS, preset.direction)
        assertEquals(MessageKind.PRESET, preset.kind)
        assertEquals("love_you", preset.presetId)
        assertEquals("❤️", preset.emoji)
        assertEquals("Leo", preset.authorName)

        val threads = RoomChatThreads(parent.chatThreads)
        assertEquals(1, threads.observeThread(kidId).first()?.unreadCount)
        threads.markRead(kidId)
        assertEquals(0, threads.observeThreads().first().single().unreadCount)
    }

    @Test
    fun `kid mode on a parent's phone writes as the kid (D-M3-12)`() = runTest {
        linked()
        assertTrue(parent.sender(ChatMode.KID).send(kidId, OutgoingMessage.Text("from kid mode")).isSuccess)

        val inKidMode = parent.reader(ChatMode.KID).observe(kidId, 50).first().messages.single()
        assertEquals(MessageDirection.MINE, inKidMode.direction)
        val asParent = parent.reader(ChatMode.PARENT).observe(kidId, 50).first().messages.single()
        assertEquals(MessageDirection.THEIRS, asParent.direction)
        assertEquals("Leo", asParent.authorName)
        kid.pullFrom(parent)
        assertEquals(
            MessageDirection.MINE,
            kid.reader(ChatMode.KID).observe(kidId, 50).first().messages.single().direction,
            "the kid's own phone shows it as the kid's",
        )
    }

    @Test
    fun `a photo is stored as a blob first and the other side loads it through the transport`() = runTest {
        linked()
        val jpeg = ByteArrayOutputStream().also { out ->
            ImageIO.write(BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "jpg", out)
        }.toByteArray()
        val sent = parent.sender(ChatMode.PARENT).send(kidId, OutgoingMessage.Photo(jpeg, "Look"))
        assertTrue(sent.isSuccess, sent.exceptionOrNull()?.toString())
        val photo = parent.reader(ChatMode.PARENT).observe(kidId, 50).first().messages.single()
        assertEquals(MessageKind.PHOTO, photo.kind)
        assertEquals("Look", photo.text)
        val blobId = requireNotNull(photo.blobId)

        // The sender's own cache serves it without the network.
        val offline = TransportBlobLoader(parent.chatBlobs, transportServing(null))
        assertContentEquals(jpeg, offline.load(blobId).getOrThrow())
        kid.pullFrom(parent)
        val loader = TransportBlobLoader(kid.chatBlobs, transportServing(parent))
        assertContentEquals(jpeg, loader.load(blobId).getOrThrow())
        assertTrue(TransportBlobLoader(kid.chatBlobs, transportServing(null)).load("missing-blob").isFailure)
    }

    @Test
    fun `a voice note from the kid reaches the parent with its length and plays from the fetched blob`() = runTest {
        linked()
        val clip = VoiceClip(byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112), durationMs = 4_200)
        assertTrue(kid.sender(ChatMode.KID).send(kidId, OutgoingMessage.Voice(clip)).isSuccess)
        parent.pullFrom(kid)

        val voice = parent.reader(ChatMode.PARENT).observe(kidId, 50).first().messages.single()
        assertEquals(MessageKind.VOICE, voice.kind)
        assertEquals(4_200L, voice.durationMs)
        assertEquals(MessageDirection.THEIRS, voice.direction)
        val loader = TransportBlobLoader(parent.chatBlobs, transportServing(kid))
        assertContentEquals(clip.bytes, loader.load(requireNotNull(voice.blobId)).getOrThrow())
    }

    @Test
    fun `a device without a thread key waits for a parent device and cannot send`() = runTest {
        val status = RoomThreadKeyStatus(kid.threadKeyStatus).observe(kidId).first()
        assertEquals(ThreadKeyState.WAITING_FOR_PARENT, status)
        assertTrue(kid.sender(ChatMode.KID).send(kidId, OutgoingMessage.Text("hello?")).isFailure)
    }
}

package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.domainledger.JvmSha256Hasher
import dev.kpetriv.sprout.core.domainledger.chat.ChatPhotoRef
import dev.kpetriv.sprout.core.domainledger.chat.MessageKind
import dev.kpetriv.sprout.core.domainledger.chat.MessagePlaintext
import dev.kpetriv.sprout.core.domainledger.toHex
import dev.kpetriv.sprout.core.model.Role
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The readable chat export (M7 §3, D-M7-9): [ChatExportReader] on a parent device. */
@RunWith(RobolectricTestRunner::class)
class ChatExportReaderTest {
    private val parent = parentDevice()
    private val kid = kidDevice()

    @After
    fun close() {
        parent.close()
        kid.close()
    }

    private suspend fun linked() {
        registerChatDevices(parent, kid)
        parent.addKid(KID_X.value)
        parent.threadKeyMaintenance()
        parent.confirmThreadKeys()
        kid.applier.applyDocuments(parent.queuedDocuments())
    }

    private fun photo(seed: Int): Pair<ByteArray, MessagePlaintext.Photo> {
        val bytes = Random(seed).nextBytes(256)
        return bytes to MessagePlaintext.Photo(ChatPhotoRef(JvmSha256Hasher.sha256(bytes).toHex(), 640, 480), "look")
    }

    /** A [MediaSink] that keeps copies of what it was given. */
    private class Collected : MediaSink {
        val files = linkedMapOf<String, ByteArray>()

        override suspend fun write(fileName: String, bytes: ByteArray) {
            files[fileName] = bytes.copyOf()
        }
    }

    @Test
    fun aThreadIsExportedReadableInOrderWithItsMedia() = runTest {
        linked()
        kid.messageWrites.send(THREAD_X, text("hi mum"))
        kid.messageWrites.send(THREAD_X, MessagePlaintext.Preset("hungry", "🍕"))
        val (cachedBytes, cachedPhoto) = photo(1)
        val (_, cachedBlob) = kid.messageWrites.sendWithBlob(THREAD_X, cachedPhoto, cachedBytes)
        val voice = Random(2).nextBytes(64)
        val (_, voiceBlob) = kid.messageWrites.sendWithBlob(THREAD_X, MessagePlaintext.Voice(1_500), voice)
        parent.applier.applyMessages(kid.queuedMessages())
        parent.messageWrites.send(THREAD_X, text("on my way"))
        // Only the photo reached the parent's cache; the voice note is fetched (best effort) or listed as missing.
        parent.chatBlobs.ingest(
            cachedBlob,
            kid.queuedBlobChunks().filter { it.blobId == cachedBlob.value },
            BlobExpectation(BlobTarget.Thread(THREAD_X), kid.deviceId),
        )

        assertEquals(listOf(THREAD_X), parent.chatExport.threads())
        val thread = parent.chatExport.read(THREAD_X)

        val messages = thread.messages
        assertEquals(
            listOf(MessageKind.TEXT, MessageKind.PRESET, MessageKind.PHOTO, MessageKind.VOICE, MessageKind.TEXT),
            messages.map { it.kind },
        )
        assertEquals(listOf(Role.KID, Role.KID, Role.KID, Role.KID, Role.PARENT), messages.map { it.authorRole })
        assertEquals("hi mum", messages[0].text)
        assertEquals("hungry", messages[1].presetId)
        assertEquals("🍕", messages[1].emoji)
        assertEquals("look", messages[2].text)
        assertEquals("${cachedBlob.value}.jpg", messages[2].media)
        assertEquals(1_500L, messages[3].durationMs)
        assertEquals("${voiceBlob.value}.m4a", messages[3].media)
        assertEquals("on my way", messages[4].text)
        assertTrue(messages.none { it.locked })
        assertEquals(messages.sortedBy { it.sentAt }.map { it.messageId }, messages.map { it.messageId })

        val offline = Collected()
        val missing = parent.chatExport.exportMedia(thread, offline)
        assertEquals(listOf("${cachedBlob.value}.jpg"), offline.files.keys.toList())
        assertContentEquals(cachedBytes, offline.files.getValue("${cachedBlob.value}.jpg"))
        val voiceMissing =
            MissingMedia(messages[3].messageId, "${voiceBlob.value}.m4a", MissingMedia.Reason.NOT_AVAILABLE)
        assertEquals(listOf(voiceMissing), missing)

        val online = Collected()
        val none = parent.chatExport.exportMedia(thread, online) { blobId ->
            kid.queuedBlobChunks().filter { it.blobId == blobId.value }
        }
        assertEquals(emptyList(), none)
        assertContentEquals(voice, online.files.getValue("${voiceBlob.value}.m4a"))

        // The JSON the zip carries: readable text, no ciphertext.
        val json = ChatExportReader.toJson(thread)
        assertEquals(THREAD_X.value, json.getValue("threadId").jsonPrimitive.content)
        val first = json.getValue("messages").jsonArray[0].jsonObject
        assertEquals("hi mum", first.getValue("text").jsonPrimitive.content)
        assertEquals("KID", first.getValue("authorRole").jsonPrimitive.content)
        assertEquals(false, first.getValue("locked").jsonPrimitive.boolean)
        assertTrue("ciphertext" !in json.toString())
        val listed = ChatExportReader.toJson(missing).single().jsonObject
        assertEquals("NOT_AVAILABLE", listed.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun aMessageNoKeyOpensIsExportedLockedWithoutContent() = runTest {
        linked()
        kid.messageWrites.send(THREAD_X, text("secret"))
        val (bytes, photo) = photo(3)
        kid.messageWrites.sendWithBlob(THREAD_X, photo, bytes)
        // A second parent device that has no key for the thread yet.
        val other = TestHousehold(
            RuntimeEnvironment.getApplication(),
            sessionFor = TestHousehold.parentSession(HOME, PARENT_UID),
        )
        try {
            registerChatDevices(parent, kid, other)
            other.applier.applyMessages(kid.queuedMessages())

            val thread = other.chatExport.read(THREAD_X)

            assertTrue(thread.messages.all { it.locked })
            assertNull(thread.messages[0].text)
            val missing = other.chatExport.exportMedia(thread, Collected())
            assertEquals(listOf(MissingMedia.Reason.LOCKED), missing.map { it.reason })
        } finally {
            other.close()
        }
    }

    @Test
    fun onlyAParentSessionExports() = runTest {
        linked()
        kid.messageWrites.send(THREAD_X, text("hi"))
        assertFailsWith<IllegalStateException> { kid.chatExport.threads() }
        assertFailsWith<IllegalStateException> { kid.chatExport.read(THREAD_X) }
    }
}

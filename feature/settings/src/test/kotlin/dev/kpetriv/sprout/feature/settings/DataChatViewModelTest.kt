package dev.kpetriv.sprout.feature.settings

import dev.kpetriv.sprout.core.domainledger.chat.MessageKind
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.ThreadId
import dev.kpetriv.sprout.core.time.FakeClock
import dev.kpetriv.sprout.core.time.HouseholdCalendar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.ZoneOffset
import java.util.zip.ZipInputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private val TEST_CALENDAR = HouseholdCalendar(FakeClock(Instant.parse("2026-11-03T10:00:00Z"))) { ZoneOffset.UTC }

/** A [DataViewModel] over fakes; pass the ones a test inspects. */
@Suppress("LongParameterList")
fun newDataViewModel(
    data: FakeHouseholdData = FakeHouseholdData(Result.success(JsonObject(emptyMap()))),
    writer: FakeExportWriter = FakeExportWriter(),
    calendar: HouseholdCalendar = TEST_CALENDAR,
    rules: FakeHouseholdRules = FakeHouseholdRules(),
    purger: FakeChatHistoryPurger = FakeChatHistoryPurger(),
    chat: ChatExportSource = FakeChatExportSource(),
    confirmation: FakeParentConfirmation = FakeParentConfirmation(),
    account: AccountControl = FakeAccountControl(),
    dispatcher: CoroutineDispatcher,
): DataViewModel = DataViewModel(
    data,
    writer,
    calendar,
    account,
    rules,
    purger,
    chat,
    confirmation,
    dispatcher,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DataChatViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val photo = "blob-photo-1.jpg"
    private val voice = "blob-voice-2.m4a"
    private val leoThread = ChatExportThread(
        ThreadId("thread-leo"),
        KidId("kid-leo"),
        listOf(
            message("08:00", Role.PARENT, MessageKind.TEXT, text = "Well done!"),
            message("08:01", Role.KID, MessageKind.PRESET, presetId = "thanks", emoji = "🙏"),
            message("08:02", Role.KID, MessageKind.PHOTO, media = photo),
            message("08:03", Role.PARENT, MessageKind.VOICE, media = voice, durationMs = 4_000),
            message("08:04", Role.KID, MessageKind.PHOTO, media = photo),
            message("08:05", Role.KID, MessageKind.TEXT, locked = true),
        ),
    )
    private val bundle = Json.parseToJsonElement("""{"events":[]}""") as JsonObject

    @Suppress("LongParameterList")
    private fun message(
        time: String,
        author: Role,
        kind: MessageKind,
        text: String? = null,
        presetId: String? = null,
        emoji: String? = null,
        durationMs: Long? = null,
        media: String? = null,
        locked: Boolean = false,
    ) = ChatExportMessage(
        Instant.parse("2026-11-01T$time:00Z"),
        author,
        kind,
        text,
        presetId,
        emoji,
        durationMs,
        media,
        locked,
    )

    @Test
    fun `export asks for the parent first, then zips the readable chat with the media it could get`() = runTest {
        val confirmation = FakeParentConfirmation()
        val chat = FakeChatExportSource(listOf(leoThread), mutableMapOf(photo to byteArrayOf(1, 2, 3)))
        val viewModel = newDataViewModel(
            FakeHouseholdData(Result.success(bundle)),
            chat = chat,
            confirmation = confirmation,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        viewModel.onEvent(DataEvent.Export)

        assertEquals(listOf(ConfirmationPurpose.EXPORT_HOUSEHOLD), confirmation.asked)
        assertEquals(listOf(ThreadId("thread-leo")), chat.mediaRequests)
        assertNull(viewModel.state.value.exportProgress)
        val entries = unzip(assertNotNull(viewModel.state.value.pendingExport).bytes)
        assertEquals(
            setOf(
                "household.json",
                "ledger.csv",
                "chat/kid-leo.json",
                "chat/media/blob-photo-1.jpg",
                "chat/missing.json",
            ),
            entries.keys,
        )
        assertContentEquals(byteArrayOf(1, 2, 3), entries["chat/media/blob-photo-1.jpg"], "copied before the wipe")
        assertLeoThread(entries.getValue("chat/kid-leo.json"))
        val missing = Json.parseToJsonElement(entries.getValue("chat/missing.json").decodeToString()).jsonObject
        assertEquals(
            JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "file" to JsonPrimitive("media/blob-voice-2.m4a"),
                            "reason" to JsonPrimitive("NOT_AVAILABLE"),
                        ),
                    ),
                ),
            ),
            missing["missing"],
        )
    }

    /** The decoded messages of [leoThread] as `chat/kid-leo.json` has them. */
    private fun assertLeoThread(json: ByteArray) {
        val thread = Json.parseToJsonElement(json.decodeToString()).jsonObject
        assertEquals("kid-leo", thread["kidId"]?.jsonPrimitive?.content)
        val messages = thread.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(6, messages.size)
        assertEquals(
            JsonObject(
                mapOf(
                    "sentAt" to JsonPrimitive("2026-11-01T08:00:00Z"),
                    "author" to JsonPrimitive("PARENT"),
                    "kind" to JsonPrimitive("text"),
                    "text" to JsonPrimitive("Well done!"),
                ),
            ),
            messages[0],
        )
        assertEquals("thanks", messages[1]["presetId"]?.jsonPrimitive?.content)
        assertEquals("🙏", messages[1]["emoji"]?.jsonPrimitive?.content)
        assertEquals("media/blob-photo-1.jpg", messages[2]["media"]?.jsonPrimitive?.content)
        assertEquals("media/blob-voice-2.m4a", messages[3]["media"]?.jsonPrimitive?.content)
        assertEquals("4000", messages[3]["durationMs"]?.jsonPrimitive?.content)
        assertEquals(
            JsonObject(
                mapOf(
                    "sentAt" to JsonPrimitive("2026-11-01T08:05:00Z"),
                    "author" to JsonPrimitive("KID"),
                    "kind" to JsonPrimitive("text"),
                    "locked" to JsonPrimitive(true),
                ),
            ),
            messages[5],
        )
    }

    @Test
    fun `media names never escape the chat folder, and names that sanitise alike stay distinct`() {
        val evil = "../../etc/passwd.j/p.g"
        val twin = "..%2F..%2Fetc%2Fpasswd.jpg"
        val sentAt = Instant.parse("2026-11-01T08:00:00Z")
        val thread = ChatExportThread(
            ThreadId("t1"),
            KidId(".."),
            listOf(
                ChatExportMessage(sentAt, Role.KID, MessageKind.PHOTO, media = evil),
                ChatExportMessage(sentAt, Role.KID, MessageKind.PHOTO, media = twin),
                ChatExportMessage(sentAt, Role.KID, MessageKind.PHOTO, media = "a/b.jpg"),
                ChatExportMessage(sentAt, Role.KID, MessageKind.PHOTO, media = "a_b.jpg"),
            ),
        )
        val other = ChatExportThread(ThreadId("t2"), KidId("__"), emptyList())
        val zip = HouseholdExport.zip(
            bundle,
            ChatExportContent(
                listOf(thread, other),
                mapOf(evil to byteArrayOf(1), twin to byteArrayOf(2), "a/b.jpg" to byteArrayOf(3)),
                listOf(ChatMissingMedia("a_b.jpg", "LOCKED")),
            ),
        )
        val names = unzip(zip).keys
        names.forEach { name ->
            assertFalse(".." in name, name)
            assertFalse(name.startsWith("/"), name)
        }
        assertEquals(
            setOf(
                "household.json",
                "ledger.csv",
                "chat/__.json",
                "chat/__-2.json",
                "chat/media/______etc_passwd_j_p.g",
                "chat/media/___2F___2Fetc_2Fpasswd.jpg",
                "chat/media/a_b.jpg",
                "chat/missing.json",
            ),
            names,
        )
        val missing = Json.parseToJsonElement(unzip(zip).getValue("chat/missing.json").decodeToString()).jsonObject
        val missingFile = missing.getValue("missing").jsonArray.single().jsonObject.getValue("file")
        assertEquals("media/a_b-2.jpg", missingFile.jsonPrimitive.content)
    }

    @Test
    fun `a cancelled confirmation exports nothing and says nothing, a failed one says so`() = runTest {
        val data = FakeHouseholdData(Result.success(bundle))
        val confirmation = FakeParentConfirmation(ConfirmationOutcome.CANCELLED)
        val viewModel = newDataViewModel(
            data,
            confirmation = confirmation,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        viewModel.onEvent(DataEvent.Export)
        assertEquals(0, data.exports)
        assertNull(viewModel.state.value.exportProgress)
        assertNull(viewModel.state.value.message)

        confirmation.outcome = ConfirmationOutcome.FAILED
        viewModel.onEvent(DataEvent.Export)
        assertEquals(0, data.exports)
        assertEquals(DataMessage.NOT_CONFIRMED, viewModel.state.value.message)
        assertNull(viewModel.state.value.pendingExport)
    }

    @Test
    fun `an unreadable chat fails the export`() = runTest {
        val chat = FakeChatExportSource().apply { failThreads = true }
        val viewModel = newDataViewModel(
            FakeHouseholdData(Result.success(bundle)),
            chat = chat,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        viewModel.onEvent(DataEvent.Export)
        assertEquals(DataMessage.EXPORT_FAILED, viewModel.state.value.message)
        assertNull(viewModel.state.value.pendingExport)
        assertNull(viewModel.state.value.exportProgress)
    }

    @Test
    fun `export progress counts the media as they arrive, and a failing thread lists its media as missing`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val mia = ChatExportThread(
            ThreadId("thread-mia"),
            KidId("kid-mia"),
            listOf(message("09:00", Role.KID, MessageKind.PHOTO, media = "blob-mia.jpg")),
        )
        val chat = object : ChatExportSource {
            override suspend fun threads() = listOf(leoThread, mia)

            override suspend fun media(thread: ChatExportThread, sink: ChatMediaSink): List<ChatMissingMedia> {
                if (thread == mia) error("cannot open")
                sink.write(photo, byteArrayOf(0))
                gate.await()
                return listOf(ChatMissingMedia(voice, "LOCKED"))
            }
        }
        val viewModel = newDataViewModel(
            FakeHouseholdData(Result.success(bundle)),
            chat = chat,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        viewModel.onEvent(DataEvent.Export)
        val progress = assertNotNull(viewModel.state.value.exportProgress)
        assertEquals(ExportProgress(ExportStage.MEDIA, done = 1, total = 3), progress)
        assertEquals(1f / 3, progress.fraction)
        viewModel.onEvent(DataEvent.Export)
        gate.complete(Unit)
        assertNull(viewModel.state.value.exportProgress)
        val entries = unzip(assertNotNull(viewModel.state.value.pendingExport).bytes)
        val missing = Json.parseToJsonElement(entries.getValue("chat/missing.json").decodeToString()).jsonObject
        assertEquals(
            listOf("media/blob-voice-2.m4a" to "LOCKED", "media/blob-mia.jpg" to "NOT_AVAILABLE"),
            missing.getValue("missing").jsonArray.map {
                it.jsonObject.getValue("file").jsonPrimitive.content to
                    it.jsonObject.getValue("reason").jsonPrimitive.content
            },
        )
    }

    @Test
    fun `a longer retention saves at once, a shorter one asks first`() = runTest {
        val rules = FakeHouseholdRules(HouseholdRulesState(chatRetentionDays = 90, dailyGameCapMinor = null))
        val viewModel = newDataViewModel(rules = rules, dispatcher = UnconfinedTestDispatcher(testScheduler))
        assertEquals(RetentionUi(days = 90), viewModel.state.value.retention)
        assertEquals(listOf(30, 90, 365, null), viewModel.state.value.retention?.options)

        viewModel.onEvent(DataEvent.RetentionChosen(365))
        assertEquals(listOf<Int?>(365), rules.retentionWrites)
        assertEquals(DataMessage.RETENTION_SAVED, viewModel.state.value.message)

        viewModel.onEvent(DataEvent.RetentionChosen(30))
        assertEquals(RetentionChoice(30), viewModel.state.value.retention?.pending)
        assertEquals(listOf<Int?>(365), rules.retentionWrites, "nothing written before the confirmation")
        viewModel.onEvent(DataEvent.DismissRetention)
        assertNull(viewModel.state.value.retention?.pending)

        viewModel.onEvent(DataEvent.RetentionChosen(30))
        viewModel.onEvent(DataEvent.ConfirmRetention)
        assertEquals(listOf<Int?>(365, 30), rules.retentionWrites)
        assertEquals(RetentionUi(days = 30), viewModel.state.value.retention)

        viewModel.onEvent(DataEvent.RetentionChosen(null))
        assertEquals(listOf(365, 30, null), rules.retentionWrites, "forever is never shorter")
    }

    @Test
    fun `from forever any period asks first, an odd period from another phone is offered, failures say so`() = runTest {
        val rules = FakeHouseholdRules(HouseholdRulesState(chatRetentionDays = 45, dailyGameCapMinor = null))
        val viewModel = newDataViewModel(rules = rules, dispatcher = UnconfinedTestDispatcher(testScheduler))
        assertEquals(listOf(30, 45, 90, 365, null), viewModel.state.value.retention?.options)
        rules.state.value = HouseholdRulesState(chatRetentionDays = null, dailyGameCapMinor = null)
        viewModel.onEvent(DataEvent.RetentionChosen(365))
        assertEquals(RetentionChoice(365), viewModel.state.value.retention?.pending)
        rules.failWrites = true
        viewModel.onEvent(DataEvent.ConfirmRetention)
        assertEquals(DataMessage.RETENTION_FAILED, viewModel.state.value.message)
        assertEquals(RetentionUi(days = null), viewModel.state.value.retention)
        viewModel.onEvent(DataEvent.RetentionChosen(0))
        viewModel.onEvent(DataEvent.RetentionChosen(HouseholdRulesState.MAX_RETENTION_DAYS + 1))
        assertNull(viewModel.state.value.retention?.pending, "out-of-range periods are ignored")
    }

    @Test
    fun `delete chat history needs the word typed and the parent, then purges`() = runTest {
        val purger = FakeChatHistoryPurger()
        val confirmation = FakeParentConfirmation()
        val viewModel = newDataViewModel(
            purger = purger,
            confirmation = confirmation,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        viewModel.onEvent(DataEvent.AskDeleteChat)
        assertEquals("", viewModel.state.value.chatDeleteTyped)
        viewModel.onEvent(DataEvent.ChatDeleteTyped("DELET"))
        viewModel.onEvent(DataEvent.ConfirmDeleteChat)
        assertTrue(confirmation.asked.isEmpty())
        assertEquals(0, purger.purges)

        viewModel.onEvent(DataEvent.ChatDeleteTyped(" delete "))
        assertTrue(viewModel.state.value.canConfirmChatDelete)
        viewModel.onEvent(DataEvent.ConfirmDeleteChat)
        assertEquals(listOf(ConfirmationPurpose.DELETE_CHAT_HISTORY), confirmation.asked)
        assertEquals(1, purger.purges)
        assertNull(viewModel.state.value.chatDeleteTyped)
        assertFalse(viewModel.state.value.purgingChat)
        assertEquals(DataMessage.CHAT_DELETED, viewModel.state.value.message)
    }

    @Test
    fun `a cancelled prompt keeps the dialog, a failed one purges nothing, offline purges say so`() = runTest {
        val purger = FakeChatHistoryPurger(ChatPurgeOutcome.PURGED_ON_THIS_PHONE)
        val confirmation = FakeParentConfirmation(ConfirmationOutcome.CANCELLED)
        val viewModel = newDataViewModel(
            purger = purger,
            confirmation = confirmation,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        viewModel.onEvent(DataEvent.AskDeleteChat)
        viewModel.onEvent(DataEvent.ChatDeleteTyped("DELETE"))
        viewModel.onEvent(DataEvent.ConfirmDeleteChat)
        assertEquals("DELETE", viewModel.state.value.chatDeleteTyped)
        assertNull(viewModel.state.value.message)

        confirmation.outcome = ConfirmationOutcome.FAILED
        viewModel.onEvent(DataEvent.ConfirmDeleteChat)
        assertEquals(0, purger.purges)
        assertEquals(DataMessage.NOT_CONFIRMED, viewModel.state.value.message)
        assertNull(viewModel.state.value.chatDeleteTyped)

        confirmation.outcome = ConfirmationOutcome.CONFIRMED
        viewModel.onEvent(DataEvent.AskDeleteChat)
        viewModel.onEvent(DataEvent.ChatDeleteTyped("DELETE"))
        viewModel.onEvent(DataEvent.ConfirmDeleteChat)
        assertEquals(DataMessage.CHAT_DELETED_ON_THIS_PHONE, viewModel.state.value.message)
        purger.outcome = ChatPurgeOutcome.FAILED
        viewModel.onEvent(DataEvent.AskDeleteChat)
        viewModel.onEvent(DataEvent.ChatDeleteTyped("DELETE"))
        viewModel.onEvent(DataEvent.ConfirmDeleteChat)
        assertEquals(DataMessage.CHAT_DELETE_FAILED, viewModel.state.value.message)
    }

    @Test
    fun `retention is written only after the parent confirms, and a dismissed prompt can be retried`() = runTest {
        val rules = FakeHouseholdRules(HouseholdRulesState(chatRetentionDays = 90, dailyGameCapMinor = null))
        val confirmation = FakeParentConfirmation(ConfirmationOutcome.CANCELLED)
        val viewModel = newDataViewModel(
            rules = rules,
            confirmation = confirmation,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        viewModel.onEvent(DataEvent.RetentionChosen(30))
        viewModel.onEvent(DataEvent.ConfirmRetention)
        assertTrue(rules.retentionWrites.isEmpty())
        assertEquals(RetentionUi(days = 90, pending = RetentionChoice(30)), viewModel.state.value.retention)

        confirmation.outcome = ConfirmationOutcome.FAILED
        viewModel.onEvent(DataEvent.ConfirmRetention)
        assertTrue(rules.retentionWrites.isEmpty())
        assertEquals(DataMessage.NOT_CONFIRMED, viewModel.state.value.message)
        assertEquals(RetentionUi(days = 90), viewModel.state.value.retention)

        confirmation.outcome = ConfirmationOutcome.CONFIRMED
        viewModel.onEvent(DataEvent.RetentionChosen(365))
        assertEquals(listOf<Int?>(365), rules.retentionWrites)
        assertEquals(List(3) { ConfirmationPurpose.CHANGE_CHAT_RETENTION }, confirmation.asked)
    }

    @Test
    fun `media past the export budget are listed as too large, and the saved zip is wiped`() = runTest {
        val big = "blob-photo-big.jpg"
        val thread = ChatExportThread(
            ThreadId("thread-leo"),
            KidId("kid-leo"),
            listOf(
                message("08:00", Role.KID, MessageKind.PHOTO, media = photo),
                message("08:01", Role.KID, MessageKind.PHOTO, media = big),
                message("08:02", Role.KID, MessageKind.PHOTO, media = big),
            ),
        )
        val chat = FakeChatExportSource(listOf(thread), mutableMapOf(photo to ByteArray(6) { 1 }, big to ByteArray(8)))
        val writer = FakeExportWriter()
        val viewModel = newDataViewModel(
            FakeHouseholdData(Result.success(bundle)),
            writer = writer,
            chat = chat,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        viewModel.mediaBudgetBytes = 10

        viewModel.onEvent(DataEvent.Export)

        val pending = assertNotNull(viewModel.state.value.pendingExport)
        val entries = unzip(pending.bytes)
        assertContentEquals(ByteArray(6) { 1 }, entries.getValue("chat/media/blob-photo-1.jpg"))
        assertFalse("chat/media/blob-photo-big.jpg" in entries)
        val missing = Json.parseToJsonElement(entries.getValue(HouseholdExport.MISSING_NAME).decodeToString())
            .jsonObject.getValue("missing").jsonArray.map { it.jsonObject }
        assertEquals(1, missing.size, "listed once, however many messages name it")
        assertEquals("TOO_LARGE", missing.single().getValue("reason").jsonPrimitive.content)

        viewModel.onEvent(DataEvent.ExportDestination("content://saved"))
        assertNull(viewModel.state.value.pendingExport)
        assertTrue(writer.written.getValue("content://saved").all { it == 0.toByte() }, "wiped after the save")
    }

    @Test
    fun `a cancelled save wipes the zip`() = runTest {
        val viewModel = newDataViewModel(
            FakeHouseholdData(Result.success(bundle)),
            chat = FakeChatExportSource(listOf(leoThread), mutableMapOf(photo to byteArrayOf(1, 2, 3))),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        viewModel.onEvent(DataEvent.Export)
        val pending = assertNotNull(viewModel.state.value.pendingExport)

        viewModel.onEvent(DataEvent.ExportDestination(null))

        assertNull(viewModel.state.value.pendingExport)
        assertTrue(pending.bytes.all { it == 0.toByte() })
    }

    @Test
    fun `running out of memory fails the export instead of the app`() = runTest {
        val chat = FakeChatExportSource(listOf(leoThread)).apply { outOfMemory = true }
        val viewModel = newDataViewModel(
            FakeHouseholdData(Result.success(bundle)),
            chat = chat,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        viewModel.onEvent(DataEvent.Export)

        assertNull(viewModel.state.value.exportProgress)
        assertNull(viewModel.state.value.pendingExport)
        assertEquals(DataMessage.EXPORT_TOO_LARGE, viewModel.state.value.message)
    }

    @Test
    fun `a phone without the household policy yet says so instead of writing one`() = runTest {
        val rules = FakeHouseholdRules().apply { noPolicyYet = true }
        val viewModel = newDataViewModel(rules = rules, dispatcher = UnconfinedTestDispatcher(testScheduler))

        viewModel.onEvent(DataEvent.RetentionChosen(30))
        viewModel.onEvent(DataEvent.ConfirmRetention)

        assertEquals(DataMessage.POLICY_NOT_SYNCED, viewModel.state.value.message)
        assertTrue(rules.retentionWrites.isEmpty())
        assertNull(viewModel.state.value.retention?.pending)
    }

    @Test
    fun `an account flow that fails does not freeze the screen`() = runTest {
        val account = object : AccountControl by FakeAccountControl() {
            override fun observe(): Flow<AccountInfo> = flow { throw IllegalStateException("no account") }
        }
        val rules = FakeHouseholdRules(HouseholdRulesState(chatRetentionDays = 90, dailyGameCapMinor = null))
        val viewModel = newDataViewModel(
            rules = rules,
            account = account,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertEquals(90, viewModel.state.value.retention?.days)
        assertNull(viewModel.state.value.householdId)
    }

    @Test
    fun `a kid id that sanitises to missing does not collide with the missing list`() {
        val thread =
            ChatExportThread(
                ThreadId("missing"),
                KidId("missing"),
                listOf(message("08:00", Role.KID, MessageKind.TEXT, text = "hi")),
            )
        val other =
            ChatExportThread(
                ThreadId("m"),
                KidId("kid-missing"),
                listOf(message("08:01", Role.KID, MessageKind.TEXT, text = "yo")),
            )

        val entries =
            unzip(HouseholdExport.zip(bundle, ChatExportContent(listOf(thread, other), emptyMap(), emptyList())))

        assertTrue("chat/missing.json" in entries)
        assertTrue("chat/kid-missing.json" in entries)
        assertTrue("chat/kid-missing-2.json" in entries)
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes())
            }
        }
    }
}

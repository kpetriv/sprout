package dev.kpetriv.sprout.feature.chat

import androidx.lifecycle.SavedStateHandle
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.time.FakeClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("LargeClass") // One test per ChatViewModel behaviour, sharing the fakes below.
class ChatViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val threads = FakeChatThreads()
    private val reader = FakeMessageReader()
    private val sender = FakeMessageSender()
    private val keys = FakeKeyStatus()
    private val blobs = FakeBlobLoader()
    private val recorder = FakeVoiceRecorder()
    private val player = FakeVoicePlayer()
    private val filter = FakeFilter()
    private val pickPhoto = FakePickPhoto()

    private fun args(mode: ChatMode = ChatMode.PARENT, tier: KidUiTier? = null) = SavedStateHandle(
        buildMap {
            put(ChatViewModel.THREAD_ID_KEY, ChatTestData.leo.value)
            put(ChatViewModel.MODE_KEY, mode.name)
            if (tier != null) put(ChatViewModel.TIER_KEY, tier.name)
        },
    )

    private fun viewModel(mode: ChatMode = ChatMode.PARENT, tier: KidUiTier? = null) = viewModel(args(mode, tier))

    private fun viewModel(handle: SavedStateHandle): ChatViewModel {
        return ChatViewModel(
            savedStateHandle = handle,
            threads = threads,
            reader = reader,
            sender = sender,
            keyStatus = keys,
            blobs = blobs,
            recorder = recorder,
            player = player,
            filter = filter,
            pickPhoto = pickPhoto,
            clock = FakeClock(ChatTestData.start),
        )
    }

    private fun TestScope.ready(vm: ChatViewModel): () -> ChatUiState.Ready {
        val state = subscribe(vm.state)
        return { assertIs<ChatUiState.Ready>(state.value) }
    }

    private fun kid(tier: KidUiTier) = viewModel(ChatMode.KID, tier)

    @Test
    fun `a parent sees the kid's name, the messages and their photos`() = runTest {
        blobs.blobs["blob-ok"] = byteArrayOf(1, 2, 3)
        reader.messages.value = listOf(
            ChatTestData.text("m1", "Hi"),
            ChatTestData.photo("m2", "blob-ok"),
            ChatTestData.photo("m3", "blob-missing"),
            ChatTestData.text("m4", "secret").copy(locked = true),
        )

        val state = ready(viewModel())()

        assertEquals("Leo", state.kidName)
        assertNull(state.tier)
        assertEquals(listOf("m1", "m2", "m3", "m4"), state.messages.map { it.id })
        assertEquals(PhotoUi.Loaded(byteArrayOf(1, 2, 3)), state.messages[1].photo)
        assertEquals(PhotoUi.Unavailable, state.messages[2].photo)
        assertTrue(state.messages[3].locked)
        assertNull(state.messages[3].text, "a locked message carries no content")
        assertEquals(listOf("blob-ok", "blob-missing"), blobs.loads)
    }

    @Test
    fun `photos are fetched once however often the page changes`() = runTest {
        blobs.blobs["blob"] = byteArrayOf(1)
        reader.messages.value = listOf(ChatTestData.photo("m1", "blob"))
        ready(viewModel())

        reader.messages.value += ChatTestData.text("m2", "and another")

        assertEquals(listOf("blob"), blobs.loads)
    }

    @Test
    fun `the thread is marked read when a new message from someone else arrives`() = runTest {
        reader.messages.value = listOf(ChatTestData.text("m1", "Hi"))
        ready(viewModel())
        reader.messages.value += ChatTestData.text("m2", "Mine", MessageDirection.MINE)
        reader.messages.value += ChatTestData.text("m3", "Again")

        assertEquals(2, threads.markedRead.size)
    }

    @Test
    fun `a missing thread and a failing reader are errors, and reload recovers`() = runTest {
        threads.threads.value = emptyList()
        val vm = viewModel()
        val state = subscribe(vm.state)
        assertEquals(ChatUiState.Error(ChatError.THREAD_NOT_FOUND), state.value)

        threads.threads.value = listOf(ChatTestData.summary())
        reader.failure = IllegalStateException("db closed")
        vm.onEvent(ChatEvent.Reload)
        assertEquals(ChatUiState.Error(ChatError.LOAD_FAILED), state.value)

        reader.failure = null
        vm.onEvent(ChatEvent.Reload)
        assertIs<ChatUiState.Ready>(state.value)
    }

    @Test
    fun `a parent's text shows as sending until the sender has stored it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        sender.gate = gate
        val vm = viewModel()
        val state = ready(vm)

        vm.onEvent(ChatEvent.DraftChanged("  Back by five  "))
        vm.onEvent(ChatEvent.SendText)

        val sending = state().messages.single()
        assertEquals(SendState.SENDING, sending.sendState)
        assertTrue(sending.pending)
        assertEquals("Back by five", sending.text)
        assertEquals("", state().draft)
        assertEquals(listOf<OutgoingMessage>(OutgoingMessage.Text("Back by five")), sender.sent)
        assertTrue(filter.checked.isEmpty(), "parents are not filtered")

        gate.complete(Unit)
        assertTrue(state().messages.isEmpty())
    }

    @Test
    fun `a failed send can be retried or discarded`() = runTest {
        sender.result = Result.failure(IllegalStateException("no key"))
        val vm = viewModel()
        val state = ready(vm)
        vm.onEvent(ChatEvent.DraftChanged("one"))
        vm.onEvent(ChatEvent.SendText)
        vm.onEvent(ChatEvent.DraftChanged("two"))
        vm.onEvent(ChatEvent.SendText)
        val (first, second) = state().messages
        assertEquals(SendState.FAILED, first.sendState)

        sender.result = Result.success(Unit)
        vm.onEvent(ChatEvent.Retry(first.id))
        vm.onEvent(ChatEvent.Discard(second.id))

        assertTrue(state().messages.isEmpty())
        assertEquals(3, sender.sent.size)
    }

    @Test
    fun `a stored failed message is retried through the sender`() = runTest {
        reader.messages.value = listOf(
            ChatTestData.text("m1", "Hi", MessageDirection.MINE).copy(sendState = SendState.FAILED),
        )
        val vm = viewModel()
        val state = ready(vm)
        sender.result = Result.failure(IllegalStateException("rejected"))

        vm.onEvent(ChatEvent.Retry("m1"))

        assertEquals(listOf("m1"), sender.retried)
        assertEquals(ChatNotice.RETRY_FAILED, state().notice)
        vm.onEvent(ChatEvent.NoticeShown)
        assertNull(state().notice)
    }

    @Test
    fun `nothing is sent or recorded while the device waits for a parent device`() = runTest {
        keys.state.value = ThreadKeyState.WAITING_FOR_PARENT
        val vm = kid(KidUiTier.SEEDLING)
        val state = ready(vm)

        vm.onEvent(ChatEvent.SelectPreset("hello"))
        vm.onEvent(ChatEvent.SendSelection)
        vm.onEvent(ChatEvent.StartRecording)

        assertFalse(state().canSend)
        assertTrue(sender.sent.isEmpty())
        assertTrue(recorder.calls.isEmpty())
        assertNull(state().recording)
    }

    @Test
    fun `a replaced chat key on this phone is shown and nothing is sent (I2)`() = runTest {
        keys.state.value = ThreadKeyState.KEY_CONFLICT
        val vm = kid(KidUiTier.SPROUT)
        val state = ready(vm)

        vm.onEvent(ChatEvent.DraftChanged("hello"))
        vm.onEvent(ChatEvent.SendText)

        assertEquals(ThreadKeyState.KEY_CONFLICT, state().keyState)
        assertFalse(state().canSend)
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `sprout text goes through the kindness filter and a nudge never blocks`() = runTest {
        filter.verdict = FilterVerdict.Nudge("Can we talk later?")
        val vm = kid(KidUiTier.SPROUT)
        val state = ready(vm)

        vm.onEvent(ChatEvent.DraftChanged("You never listen"))
        vm.onEvent(ChatEvent.SendText)

        assertEquals(listOf("You never listen" to KidUiTier.SPROUT), filter.checked)
        assertEquals(NudgeUi("You never listen", "Can we talk later?"), state().nudge)
        assertTrue(sender.sent.isEmpty())
        assertFalse(state().canSendDraft)

        vm.onEvent(ChatEvent.NudgeSendAnyway)
        assertEquals(listOf<OutgoingMessage>(OutgoingMessage.Text("You never listen")), sender.sent)
        assertNull(state().nudge)
    }

    @Test
    fun `taking the suggestion puts it in the draft for the kid to send`() = runTest {
        filter.verdict = FilterVerdict.Nudge("Can we talk later?")
        val vm = kid(KidUiTier.SAPLING)
        val state = ready(vm)
        vm.onEvent(ChatEvent.DraftChanged("Whatever"))
        vm.onEvent(ChatEvent.SendText)

        vm.onEvent(ChatEvent.NudgeUseSuggestion)

        assertEquals("Can we talk later?", state().draft)
        assertNull(state().nudge)
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `a filter that fails lets the text through`() = runTest {
        filter.failure = IllegalStateException("model missing")
        val vm = kid(KidUiTier.SPROUT)
        ready(vm)
        vm.onEvent(ChatEvent.DraftChanged("Hello"))
        vm.onEvent(ChatEvent.SendText)

        assertEquals(listOf<OutgoingMessage>(OutgoingMessage.Text("Hello")), sender.sent)
    }

    @Test
    fun `a filter that throws its own cancellation fails open and never leaves the chat checking (review M6)`() =
        runTest {
            // An ML Kit future cancelled by AICore while this coroutine is still active.
            filter.failure = CancellationException("aicore cancelled the request")
            filter.delayMillis = 100
            val vm = kid(KidUiTier.SPROUT)
            val state = ready(vm)
            vm.onEvent(ChatEvent.DraftChanged("Hello"))
            vm.onEvent(ChatEvent.SendText)
            assertTrue(state().checking)

            advanceTimeBy(101)
            assertFalse(state().checking)
            assertNull(state().nudge)
            assertEquals(listOf<OutgoingMessage>(OutgoingMessage.Text("Hello")), sender.sent)
            assertEquals("", state().draft)
        }

    @Test
    fun `the draft cannot change while the check runs and the checked text is the one sent (review M7)`() = runTest {
        filter.delayMillis = 1_000
        val vm = kid(KidUiTier.SAPLING)
        val state = ready(vm)
        vm.onEvent(ChatEvent.DraftChanged("See you at the park"))
        vm.onEvent(ChatEvent.SendText)

        vm.onEvent(ChatEvent.DraftChanged("See you at the pool"))
        assertEquals("See you at the park", state().draft, "edits are ignored while checking")

        advanceTimeBy(1_001)
        assertEquals(listOf("See you at the park" to KidUiTier.SAPLING), filter.checked)
        assertEquals(listOf<OutgoingMessage>(OutgoingMessage.Text("See you at the park")), sender.sent)
        assertEquals("", state().draft)

        vm.onEvent(ChatEvent.DraftChanged("Next one"))
        assertEquals("Next one", state().draft, "editing works again once the check is over")
    }

    @Test
    fun `a check in progress shows checking and blocks a second send`() = runTest {
        filter.delayMillis = 1_000
        val vm = kid(KidUiTier.SPROUT)
        val state = ready(vm)
        vm.onEvent(ChatEvent.DraftChanged("See you at the park"))
        vm.onEvent(ChatEvent.SendText)

        assertTrue(state().checking)
        assertFalse(state().canSendDraft)
        vm.onEvent(ChatEvent.SendText)
        assertEquals(1, filter.checked.size, "one check at a time")

        advanceTimeBy(1_001)
        assertFalse(state().checking)
        assertEquals(listOf<OutgoingMessage>(OutgoingMessage.Text("See you at the park")), sender.sent)
        assertEquals("", state().draft)
    }

    @Test
    fun `a check that takes longer than the budget fails open`() = runTest {
        filter.verdict = FilterVerdict.Nudge("never shown")
        filter.delayMillis = 10_000
        val vm = kid(KidUiTier.SAPLING)
        val state = ready(vm)
        vm.onEvent(ChatEvent.DraftChanged("Hello"))
        vm.onEvent(ChatEvent.SendText)

        advanceTimeBy(ChatViewModel.FILTER_TIMEOUT_MILLIS - 1)
        assertTrue(state().checking)
        assertTrue(sender.sent.isEmpty())

        advanceTimeBy(2)
        assertFalse(state().checking)
        assertNull(state().nudge)
        assertEquals(listOf<OutgoingMessage>(OutgoingMessage.Text("Hello")), sender.sent)
    }

    @Test
    fun `a check that answers within the budget is honoured`() = runTest {
        filter.verdict = FilterVerdict.Nudge("Can we talk later?")
        filter.delayMillis = ChatViewModel.FILTER_TIMEOUT_MILLIS - 100
        val vm = kid(KidUiTier.SPROUT)
        val state = ready(vm)
        vm.onEvent(ChatEvent.DraftChanged("Whatever"))
        vm.onEvent(ChatEvent.SendText)

        advanceTimeBy(ChatViewModel.FILTER_TIMEOUT_MILLIS)
        assertEquals(NudgeUi("Whatever", "Can we talk later?"), state().nudge)
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `an accepted suggestion is sent without a second check (D-M6-2)`() = runTest {
        filter.verdict = FilterVerdict.Nudge("Can we talk later?")
        val vm = kid(KidUiTier.SPROUT)
        val state = ready(vm)
        vm.onEvent(ChatEvent.DraftChanged("Whatever"))
        vm.onEvent(ChatEvent.SendText)
        vm.onEvent(ChatEvent.NudgeUseSuggestion)

        vm.onEvent(ChatEvent.SendText)

        assertEquals(listOf("Whatever" to KidUiTier.SPROUT), filter.checked, "the suggestion is not checked again")
        assertEquals(listOf<OutgoingMessage>(OutgoingMessage.Text("Can we talk later?")), sender.sent)
        assertEquals("", state().draft)
        assertNull(state().nudge)

        // The next message is checked as usual.
        vm.onEvent(ChatEvent.DraftChanged("Can we talk later?"))
        vm.onEvent(ChatEvent.SendText)
        assertEquals(2, filter.checked.size)
    }

    @Test
    fun `an edited suggestion is checked again`() = runTest {
        filter.verdict = FilterVerdict.Nudge("Can we talk later?")
        val vm = kid(KidUiTier.SAPLING)
        val state = ready(vm)
        vm.onEvent(ChatEvent.DraftChanged("Whatever"))
        vm.onEvent(ChatEvent.SendText)
        vm.onEvent(ChatEvent.NudgeUseSuggestion)

        vm.onEvent(ChatEvent.DraftChanged("Can we talk later? Idiot"))
        vm.onEvent(ChatEvent.SendText)

        assertEquals("Can we talk later? Idiot" to KidUiTier.SAPLING, filter.checked.last())
        assertTrue(sender.sent.isEmpty())
        assertEquals(NudgeUi("Can we talk later? Idiot", "Can we talk later?"), state().nudge)

        // Editing back to exactly the suggestion does not restore the bypass: it was cleared by the edit.
        vm.onEvent(ChatEvent.DraftChanged("Can we talk later?"))
        vm.onEvent(ChatEvent.SendText)
        assertEquals(3, filter.checked.size)
    }

    @Test
    fun `seedling has no free text and sends its picked preset with the picked emoji`() = runTest {
        val vm = kid(KidUiTier.SEEDLING)
        val state = ready(vm)
        vm.onEvent(ChatEvent.DraftChanged("typed somehow"))
        vm.onEvent(ChatEvent.SendText)
        assertTrue(sender.sent.isEmpty())

        vm.onEvent(ChatEvent.SelectPreset("hello"))
        vm.onEvent(ChatEvent.SelectPreset("hungry"))
        vm.onEvent(ChatEvent.SelectEmoji("🐶"))
        assertTrue(state().canSendSelection)
        vm.onEvent(ChatEvent.SendSelection)
        vm.onEvent(ChatEvent.SelectEmoji("❤️"))
        vm.onEvent(ChatEvent.SendSelection)

        assertEquals(
            listOf(OutgoingMessage.Preset("hungry", "🐶"), OutgoingMessage.Text("❤️")),
            sender.sent,
        )
        assertNull(state().selectedPreset)
        assertNull(state().selectedEmoji)
    }

    @Test
    fun `picking the same tile again un-picks it`() = runTest {
        val vm = kid(KidUiTier.SEEDLING)
        val state = ready(vm)
        vm.onEvent(ChatEvent.SelectPreset("hello"))
        vm.onEvent(ChatEvent.SelectPreset("hello"))
        vm.onEvent(ChatEvent.SendSelection)

        assertNull(state().selectedPreset)
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `sprout chips send known presets only`() = runTest {
        val vm = kid(KidUiTier.SPROUT)
        ready(vm)
        vm.onEvent(ChatEvent.SendPreset("not-a-preset"))
        vm.onEvent(ChatEvent.SendPreset("yes"))

        assertEquals(listOf<OutgoingMessage>(OutgoingMessage.Preset("yes", null)), sender.sent)
    }

    @Test
    fun `holding the microphone records and releasing sends the voice note`() = runTest {
        sender.gate = CompletableDeferred()
        val vm = kid(KidUiTier.SEEDLING)
        val state = ready(vm)

        vm.onEvent(ChatEvent.StartRecording)
        assertEquals(RecordingUi(0), state().recording)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(5_000, state().recording?.elapsedMs)
        assertEquals(25_000, state().recording?.remainingMs)
        vm.onEvent(ChatEvent.StopRecording)

        assertEquals(listOf("start", "stop"), recorder.calls)
        assertEquals(listOf<OutgoingMessage>(OutgoingMessage.Voice(recorder.clip)), sender.sent)
        assertNull(state().recording)
        assertEquals(MessageKind.VOICE, state().messages.single().kind)
    }

    @Test
    fun `recording stops and sends by itself at thirty seconds`() = runTest {
        val vm = kid(KidUiTier.SEEDLING)
        val state = ready(vm)
        recorder.clip = VoiceClip(byteArrayOf(7), durationMs = VoiceRecorder.MAX_DURATION_MS)

        vm.onEvent(ChatEvent.StartRecording)
        advanceTimeBy(VoiceRecorder.MAX_DURATION_MS + 1)

        assertEquals(listOf("start", "stop"), recorder.calls)
        assertEquals(1, sender.sent.size)
        assertNull(state().recording)
    }

    @Test
    fun `sliding away cancels the recording`() = runTest {
        val vm = kid(KidUiTier.SEEDLING)
        val state = ready(vm)
        vm.onEvent(ChatEvent.StartRecording)
        vm.onEvent(ChatEvent.CancelRecording)
        vm.onEvent(ChatEvent.StopRecording)

        assertEquals(listOf("start", "cancel"), recorder.calls)
        assertTrue(sender.sent.isEmpty())
        assertNull(state().recording)
    }

    @Test
    fun `a tap too short to be a voice note says how to record`() = runTest {
        recorder.clip = VoiceClip(byteArrayOf(1), durationMs = 100)
        val vm = kid(KidUiTier.SEEDLING)
        val state = ready(vm)
        vm.onEvent(ChatEvent.StartRecording)
        vm.onEvent(ChatEvent.StopRecording)

        assertTrue(sender.sent.isEmpty())
        assertEquals(ChatNotice.VOICE_TOO_SHORT, state().notice)
    }

    @Test
    fun `a release while the recorder is still starting stops it once it has started`() = runTest {
        val gate = CompletableDeferred<Unit>()
        recorder.startGate = gate
        val vm = kid(KidUiTier.SEEDLING)
        ready(vm)
        vm.onEvent(ChatEvent.StartRecording)
        vm.onEvent(ChatEvent.StopRecording)
        gate.complete(Unit)

        assertEquals(listOf("start", "stop"), recorder.calls)
    }

    @Test
    fun `an unavailable microphone is a notice, and a refused permission too`() = runTest {
        recorder.startResult = Result.failure(IllegalStateException("busy"))
        val vm = kid(KidUiTier.SEEDLING)
        val state = ready(vm)
        vm.onEvent(ChatEvent.StartRecording)
        assertEquals(ChatNotice.MIC_UNAVAILABLE, state().notice)
        assertNull(state().recording)

        vm.onEvent(ChatEvent.MicPermissionDenied)
        assertEquals(ChatNotice.MIC_PERMISSION_DENIED, state().notice)
    }

    @Test
    fun `a voice note plays from its blob and a second tap stops it`() = runTest {
        blobs.blobs["voice"] = byteArrayOf(4, 2)
        reader.messages.value = listOf(ChatTestData.voice("m1", "voice"))
        val vm = kid(KidUiTier.SPROUT)
        val state = ready(vm)

        vm.onEvent(ChatEvent.PlayVoice("m1"))
        assertEquals("m1", state().playingId)
        assertTrue(player.played.single().contentEquals(byteArrayOf(4, 2)))

        vm.onEvent(ChatEvent.PlayVoice("m1"))
        assertNull(state().playingId)
        assertEquals(1, player.stops)
    }

    @Test
    fun `playback ends by itself and a missing blob is a notice`() = runTest {
        blobs.blobs["voice"] = byteArrayOf(1)
        reader.messages.value = listOf(ChatTestData.voice("m1", "voice"), ChatTestData.voice("m2", "gone"))
        val vm = viewModel()
        val state = ready(vm)

        vm.onEvent(ChatEvent.PlayVoice("m1"))
        player.playback.complete(Unit)
        assertNull(state().playingId)

        vm.onEvent(ChatEvent.PlayVoice("m2"))
        assertEquals(ChatNotice.PLAYBACK_FAILED, state().notice)
    }

    @Test
    fun `a parent's picked photo is read and sent, a kid cannot attach`() = runTest {
        sender.gate = CompletableDeferred()
        val parent = viewModel()
        val parentState = ready(parent)
        parent.onEvent(ChatEvent.PhotoPicked("content://picked/1"))

        assertEquals(listOf<OutgoingMessage>(OutgoingMessage.Photo(byteArrayOf(9, 9))), sender.sent)
        assertEquals(PhotoUi.Loaded(byteArrayOf(9, 9)), parentState().messages.single().photo)

        val child = kid(KidUiTier.SAPLING)
        ready(child)
        child.onEvent(ChatEvent.PhotoPicked("content://picked/2"))
        assertEquals(listOf("content://picked/1"), pickPhoto.reads)
    }

    @Test
    fun `a photo that cannot be read is a notice`() = runTest {
        pickPhoto.result = Result.failure(IllegalArgumentException("not an image"))
        val vm = viewModel()
        val state = ready(vm)
        vm.onEvent(ChatEvent.PhotoPicked("content://picked/1"))

        assertEquals(ChatNotice.PHOTO_FAILED, state().notice)
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `older messages load a page at a time while there are more`() = runTest {
        reader.messages.value = (1..(ChatViewModel.PAGE_SIZE + 5)).map { ChatTestData.text("m$it", "n") }
        val vm = viewModel()
        val state = ready(vm)
        assertTrue(state().hasMore)
        assertEquals(ChatViewModel.PAGE_SIZE, state().messages.size)

        vm.onEvent(ChatEvent.LoadOlder)
        assertFalse(state().hasMore)
        vm.onEvent(ChatEvent.LoadOlder)

        assertEquals(listOf(ChatViewModel.PAGE_SIZE, ChatViewModel.PAGE_SIZE * 2), reader.limits)
    }

    @Test
    fun `the draft is capped at the text limit`() = runTest {
        val vm = viewModel()
        val state = ready(vm)
        vm.onEvent(ChatEvent.DraftChanged("a".repeat(OutgoingMessage.MAX_TEXT_LENGTH + 10)))

        assertEquals(OutgoingMessage.MAX_TEXT_LENGTH, state().draft.length)
    }

    @Test
    fun `a draft cut at the limit never splits an emoji`() = runTest {
        val vm = viewModel()
        val state = ready(vm)
        // The 1,000th UTF-16 unit is the first half of 🌱.
        val text = "a".repeat(OutgoingMessage.MAX_TEXT_LENGTH - 1) + "🌱"
        vm.onEvent(ChatEvent.DraftChanged(text))

        assertEquals("a".repeat(OutgoingMessage.MAX_TEXT_LENGTH - 1), state().draft)
        assertFalse(Character.isHighSurrogate(state().draft.last()))
        assertEquals("ab", "ab🌱".truncatedTo(3))
        assertEquals("ab🌱", "ab🌱".truncatedTo(4))
    }

    @Test
    fun `a suggestion cut at the limit never splits an emoji`() = runTest {
        filter.verdict = FilterVerdict.Nudge("b".repeat(OutgoingMessage.MAX_TEXT_LENGTH - 1) + "😀 more")
        val vm = kid(KidUiTier.SAPLING)
        val state = ready(vm)
        vm.onEvent(ChatEvent.DraftChanged("mean"))
        vm.onEvent(ChatEvent.SendText)
        vm.onEvent(ChatEvent.NudgeUseSuggestion)

        assertEquals("b".repeat(OutgoingMessage.MAX_TEXT_LENGTH - 1), state().draft)
    }

    @Test
    fun `leaving the screen cancels a recording instead of sending it`() = runTest {
        val vm = kid(KidUiTier.SEEDLING)
        val state = ready(vm)
        vm.onEvent(ChatEvent.StartRecording)
        advanceTimeBy(3_000)
        vm.onEvent(ChatEvent.ScreenStopped)
        runCurrent()

        assertEquals(listOf("start", "cancel"), recorder.calls)
        assertTrue(sender.sent.isEmpty())
        assertNull(state().recording)
    }

    @Test
    fun `leaving the screen while the recorder is still starting cancels once it has started`() = runTest {
        val gate = CompletableDeferred<Unit>()
        recorder.startGate = gate
        val vm = kid(KidUiTier.SEEDLING)
        ready(vm)
        vm.onEvent(ChatEvent.StartRecording)
        vm.onEvent(ChatEvent.ScreenStopped)
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf("start", "cancel"), recorder.calls)
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `a photo is loaded with the message's author and hash as its expectation`() = runTest {
        blobs.blobs["blob-1"] = byteArrayOf(1)
        reader.messages.value =
            listOf(ChatTestData.photo("m1", "blob-1", author = "device-a", sha256 = "cd".repeat(32)))
        ready(viewModel())()

        assertEquals(listOf(BlobExpectation(ChatTestData.leo, "device-a", "cd".repeat(32))), blobs.expectations)
    }

    @Test
    fun `reload asks again for photos that were unavailable`() = runTest {
        reader.messages.value = listOf(ChatTestData.photo("m1", "blob-late"))
        val vm = viewModel()
        val state = ready(vm)
        assertEquals(PhotoUi.Unavailable, state().messages.single().photo)

        blobs.blobs["blob-late"] = byteArrayOf(4)
        vm.onEvent(ChatEvent.Reload)
        runCurrent()

        assertEquals(PhotoUi.Loaded(byteArrayOf(4)), state().messages.single().photo)
        assertEquals(listOf("blob-late", "blob-late"), blobs.loads)
    }

    @Test
    fun `a blocked message can only be discarded, through the sender`() = runTest {
        reader.messages.value = listOf(
            ChatTestData.text("m1", "refused", MessageDirection.MINE).copy(sendState = SendState.BLOCKED),
            ChatTestData.text("m2", "fine", MessageDirection.MINE),
        )
        val vm = viewModel()
        val state = ready(vm)
        assertEquals(SendState.BLOCKED, state().messages.first().sendState)

        vm.onEvent(ChatEvent.Discard("m2"))
        vm.onEvent(ChatEvent.Retry("m1"))
        vm.onEvent(ChatEvent.Discard("m1"))
        runCurrent()

        assertEquals(listOf("m1"), sender.discarded)
        assertTrue(sender.retried.isEmpty())
        sender.result = Result.failure(IllegalStateException("not blocked"))
        vm.onEvent(ChatEvent.Discard("m1"))
        runCurrent()
        assertEquals(ChatNotice.DISCARD_FAILED, state().notice)
    }

    @Test
    fun `a kid's message from a parent's phone is labelled`() = runTest {
        reader.messages.value = listOf(ChatTestData.text("m1", "hi").copy(fromParentPhone = true))
        val state = ready(viewModel())

        assertTrue(state().messages.single().fromParentPhone)
    }

    @Test
    fun `a message that arrived late is labelled (A2-L1)`() = runTest {
        reader.messages.value = listOf(
            ChatTestData.text("m1", "hi"),
            ChatTestData.text("m2", "late", minute = 1).copy(late = true),
        )
        val state = ready(viewModel())

        assertEquals(listOf(false, true), state().messages.map { it.late })
    }

    @Test
    fun `a kid's draft survives process death and is cleared from the saved state on send (D-V1-2)`() = runTest {
        val handle = args(ChatMode.KID, KidUiTier.SPROUT)
        ready(viewModel(handle))().let { assertEquals("", it.draft) }
        viewModel(handle).onEvent(ChatEvent.DraftChanged("See you at the park"))

        // A new ViewModel from the same saved state stands in for the one the system killed.
        val restored = viewModel(handle)
        val state = ready(restored)
        assertEquals("See you at the park", state().draft)

        restored.onEvent(ChatEvent.SendText)
        assertEquals(listOf<OutgoingMessage>(OutgoingMessage.Text("See you at the park")), sender.sent)
        assertEquals("", state().draft)
        assertNull(handle.get<String>(ChatViewModel.DRAFT_KEY))
        assertEquals("", ready(viewModel(handle))().draft)
    }

    @Test
    fun `an accepted suggestion survives process death and is still sent unchecked (D-V1-2, D-M6-2)`() = runTest {
        filter.verdict = FilterVerdict.Nudge("Can we talk later?")
        val handle = args(ChatMode.KID, KidUiTier.SAPLING)
        val vm = viewModel(handle)
        ready(vm)
        vm.onEvent(ChatEvent.DraftChanged("Whatever"))
        vm.onEvent(ChatEvent.SendText)
        vm.onEvent(ChatEvent.NudgeUseSuggestion)

        val restored = viewModel(handle)
        val state = ready(restored)
        assertEquals("Can we talk later?", state().draft)
        assertNull(state().nudge)
        restored.onEvent(ChatEvent.SendText)

        assertEquals(listOf("Whatever" to KidUiTier.SAPLING), filter.checked)
        assertEquals(listOf<OutgoingMessage>(OutgoingMessage.Text("Can we talk later?")), sender.sent)
        assertNull(handle.get<String>(ChatViewModel.ACCEPTED_SUGGESTION_KEY))
    }

    @Test
    fun `a saved draft is capped and a stale marker is dropped on restore`() = runTest {
        val handle = args(ChatMode.KID, KidUiTier.SPROUT)
        handle[ChatViewModel.DRAFT_KEY] = "a".repeat(OutgoingMessage.MAX_TEXT_LENGTH + 5)
        handle[ChatViewModel.ACCEPTED_SUGGESTION_KEY] = "something else"
        val vm = viewModel(handle)
        val state = ready(vm)
        assertEquals(OutgoingMessage.MAX_TEXT_LENGTH, state().draft.length)

        vm.onEvent(ChatEvent.SendText)
        assertEquals(1, filter.checked.size, "a marker that is not the draft gives no bypass")
    }

    @Test
    fun `message text never shows up in toString`() = runTest {
        val secret = "my secret plan"
        reader.messages.value = listOf(ChatTestData.text("m1", secret))
        filter.verdict = FilterVerdict.Nudge(secret)
        val vm = kid(KidUiTier.SPROUT)
        val state = ready(vm)
        vm.onEvent(ChatEvent.DraftChanged(secret))
        vm.onEvent(ChatEvent.SendText)

        val strings = listOf(
            state().toString(),
            state().messages.toString(),
            state().nudge.toString(),
            reader.messages.value.toString(),
            ChatEvent.DraftChanged(secret).toString(),
            OutgoingMessage.Text(secret).toString(),
            FilterVerdict.Nudge(secret).toString(),
            ChatTestData.summary(name = secret).toString(),
        )
        strings.forEach { assertFalse(secret in it, it) }
        assertNotNull(state().nudge)
    }
}

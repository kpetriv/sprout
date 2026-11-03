package dev.kpetriv.sprout.feature.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.ReadAloud
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Renders the chat screens under Robolectric and checks what they send. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rGB-w411dp-h1200dp")
class ChatScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<ChatEvent>()
    private val spoken = mutableListOf<String>()
    private var attached = 0

    private fun show(state: ChatUiState) {
        compose.setContent {
            SproutTheme {
                ChatScreen(
                    state = state,
                    onEvent = { events += it },
                    onAttachPhoto = { attached++ },
                    readAloud = ReadAloud { spoken += it },
                )
            }
        }
    }

    @Test
    fun `seedling tiles are big, picking one reads it aloud, and send sends the selection`() {
        show(ChatPreviewData.kidEmpty(KidUiTier.SEEDLING).copy(selectedPreset = "hello"))

        val tile = compose.onNodeWithContentDescription("I love you")
        val size = tile.fetchSemanticsNode().size
        assertTrue(with(compose.density) { minOf(size.width, size.height).toDp().value } >= 72f)
        tile.performClick()
        compose.onNodeWithText("✉️ Send").performClick()

        assertEquals(listOf(ChatEvent.SelectPreset("love_you"), ChatEvent.SendSelection), events)
        assertEquals(listOf("I love you"), spoken)
    }

    @Test
    fun `seedling bubbles read aloud on tap`() {
        show(ChatPreviewData.kidMessages(KidUiTier.SEEDLING))

        compose.onNodeWithContentDescription("Mum: Dinner is at six").performClick()

        assertEquals(listOf("Dinner is at six"), spoken)
    }

    @Test
    fun `holding the microphone records, releasing sends and sliding away cancels`() {
        show(ChatPreviewData.kidEmpty(KidUiTier.SEEDLING))
        val mic = compose.onNodeWithContentDescription(
            "Record a voice note. Hold to talk, or tap to start and tap again to send.",
        )

        mic.performTouchInput {
            down(center)
            moveBy(Offset(0f, -10f))
            up()
        }
        mic.performTouchInput {
            down(center)
            moveBy(Offset(-600f, 0f))
            up()
        }

        assertEquals(
            listOf(
                ChatEvent.StartRecording,
                ChatEvent.StopRecording,
                ChatEvent.StartRecording,
                ChatEvent.CancelRecording,
            ),
            events,
        )
    }

    @Test
    fun `screen readers start and send a voice note with a plain click`() {
        show(ChatPreviewData.kidRecording(KidUiTier.SEEDLING))

        compose.onNodeWithContentDescription("Recording. Tap to send.").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf<ChatEvent>(ChatEvent.StopRecording), events)
        compose.onNodeWithText("Recording… 22 seconds left").assertIsDisplayed()
    }

    @Test
    fun `sprout chips send presets and the composer sends text`() {
        show(ChatPreviewData.kidEmpty(KidUiTier.SPROUT).copy(draft = "Hi"))

        compose.onNodeWithText("👋 Hello!").performClick()
        compose.onNodeWithText("Send").performClick()

        assertEquals(listOf(ChatEvent.SendPreset("hello"), ChatEvent.SendText), events)
    }

    @Test
    fun `the nudge offers the suggestion, editing and sending anyway`() {
        show(ChatPreviewData.kidNudge(KidUiTier.SAPLING))

        compose.onNodeWithText("Maybe: “Can we talk about it later?”").assertIsDisplayed()
        compose.onNodeWithText("Use this").performClick()
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("Send anyway").performClick()

        assertEquals(
            listOf(ChatEvent.NudgeUseSuggestion, ChatEvent.NudgeDismiss, ChatEvent.NudgeSendAnyway),
            events,
        )
    }

    @Test
    fun `a failed message can be retried or deleted`() {
        show(ChatPreviewData.kidFailed(KidUiTier.SAPLING))

        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("Delete").performClick()

        assertEquals(listOf(ChatEvent.Retry("local-0"), ChatEvent.Discard("local-0")), events)
    }

    @Test
    fun `waiting for a parent device disables the composer and explains why`() {
        show(ChatPreviewData.kidLocked(KidUiTier.SPROUT))

        compose.onNodeWithText(
            "Waiting for a parent device. You can send messages once a parent's phone has set up this chat.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Send").assertIsNotEnabled()
    }

    @Test
    fun `a parent types, sends, attaches a photo and plays a voice note`() {
        show(ChatPreviewData.parentEmpty())

        compose.onNodeWithText("Write a message").performTextInput("Hello")
        compose.onNodeWithContentDescription("Add a photo").performClick()

        assertEquals(listOf<ChatEvent>(ChatEvent.DraftChanged("Hello")), events)
        assertEquals(1, attached)
    }

    @Test
    fun `voice notes play from the bubble`() {
        show(ChatPreviewData.parentThread())

        compose.onNodeWithContentDescription("Play voice note").performClick()

        assertEquals(listOf<ChatEvent>(ChatEvent.PlayVoice("m5")), events)
    }

    @Test
    fun `the parent inbox opens a thread`() {
        val opened = mutableListOf<KidId>()
        compose.setContent {
            SproutTheme {
                ChatThreadListScreen(
                    ChatPreviewData.threads(),
                    onEvent = {},
                    onOpenThread = { opened += it },
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("2 new messages").assertIsDisplayed()
        compose.onNodeWithText("Mia").performClick()

        assertEquals(listOf(KidId("kid-mia")), opened)
    }
}

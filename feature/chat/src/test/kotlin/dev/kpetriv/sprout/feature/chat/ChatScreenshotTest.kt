package dev.kpetriv.sprout.feature.chat

import android.graphics.Bitmap
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotTest
import dev.kpetriv.sprout.core.ui.ReadAloud
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Golden screenshots of the chat screens, in src/test/screenshots. The kid thread covers every tier in every state
 * (empty, messages, locked, recording, failed); Sprout and Sapling have no microphone, so their "recording" golden is
 * the thread unchanged. Parent screens are captured light and dark. Re-record with
 * `./gradlew :feature:chat:recordRoborazziDebug`.
 */
class ChatScreenshotTest : ScreenshotTest() {
    @Test
    fun `kid, empty, every tier`() = screenshot.captureTiers("chat_kid_empty") { tier ->
        ChatScreen(state = ChatPreviewData.kidEmpty(tier), onEvent = {}, readAloud = speech)
    }

    @Test
    fun `kid, messages, every tier`() = screenshot.captureTiers("chat_kid_messages") { tier ->
        ChatScreen(state = ChatPreviewData.kidMessages(tier, PhotoUi.Loaded(photo)), onEvent = {}, readAloud = speech)
    }

    @Test
    fun `kid, locked and waiting for a parent device, every tier`() =
        screenshot.captureTiers("chat_kid_locked") { tier ->
            ChatScreen(state = ChatPreviewData.kidLocked(tier), onEvent = {}, readAloud = speech)
        }

    @Test
    fun `kid, recording, every tier`() = screenshot.captureTiers("chat_kid_recording") { tier ->
        ChatScreen(state = ChatPreviewData.kidRecording(tier), onEvent = {}, readAloud = speech)
    }

    @Test
    fun `kid, sending failed, every tier`() = screenshot.captureTiers("chat_kid_failed") { tier ->
        ChatScreen(state = ChatPreviewData.kidFailed(tier), onEvent = {}, readAloud = speech)
    }

    @Test
    fun `kid, kindness nudge, sprout and sapling`() = screenshot.captureTiers(
        "chat_kid_nudge",
        tiers = listOf(KidUiTier.SPROUT, KidUiTier.SAPLING),
    ) { tier ->
        ChatScreen(state = ChatPreviewData.kidNudge(tier), onEvent = {})
    }

    @Test
    fun `kid, voice note playing, every tier`() = screenshot.captureTiers("chat_kid_voice_playing") { tier ->
        ChatScreen(state = ChatPreviewData.kidVoicePlaying(tier), onEvent = {}, readAloud = speech)
    }

    @Test
    fun `kid, kindness check running, sprout and sapling`() = screenshot.captureTiers(
        "chat_kid_checking",
        tiers = listOf(KidUiTier.SPROUT, KidUiTier.SAPLING),
    ) { tier ->
        ChatScreen(state = ChatPreviewData.kidChecking(tier), onEvent = {})
    }

    @Test
    fun `parent thread, sending, failed and blocked`() = screenshot.captureLightDark("chat_parent_send_states") {
        ChatScreen(state = ChatPreviewData.parentSendStates(), onEvent = {})
    }

    @Test
    fun `parent thread, photo loading and unavailable`() = screenshot.captureLightDark("chat_parent_photos") {
        ChatScreen(state = ChatPreviewData.parentPhotos(), onEvent = {})
    }

    @Test
    fun `parent inbox`() = screenshot.captureLightDark("chat_threads") {
        ChatThreadListScreen(ChatPreviewData.threads(), onEvent = {}, onOpenThread = {}, onBack = {})
    }

    @Test
    fun `parent inbox, empty`() = screenshot.captureLightDark("chat_threads_empty") {
        ChatThreadListScreen(ChatThreadListUiState.Ready(emptyList()), onEvent = {}, onOpenThread = {}, onBack = {})
    }

    @Test
    fun `parent thread`() = screenshot.captureLightDark("chat_parent_thread") {
        ChatScreen(state = ChatPreviewData.parentThread(PhotoUi.Loaded(photo)), onEvent = {})
    }

    @Test
    fun `parent thread, empty`() = screenshot.captureLightDark("chat_parent_empty") {
        ChatScreen(state = ChatPreviewData.parentEmpty(), onEvent = {})
    }

    @Test
    fun `parent thread, locked and waiting for the key`() = screenshot.captureLightDark("chat_parent_locked") {
        ChatScreen(state = ChatPreviewData.parentLocked(), onEvent = {})
    }

    private companion object {
        // Shows that Seedling bubbles are tappable for read-aloud; nothing is drawn differently.
        val speech = ReadAloud { }

        /** A fixed two-tone PNG: the same bytes on every run. */
        val photo: ByteArray by lazy {
            val width = 64
            val height = 48
            val pixels = IntArray(width * height) { index ->
                if ((index % width) < width / 2) 0xFF8BD88F.toInt() else 0xFF00658E.toInt()
            }
            val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
    }
}

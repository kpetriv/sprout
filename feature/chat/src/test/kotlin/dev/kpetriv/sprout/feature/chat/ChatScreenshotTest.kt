package dev.kpetriv.sprout.feature.chat

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotTest
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotWindow
import dev.kpetriv.sprout.core.ui.LocalWindowWidthClass
import dev.kpetriv.sprout.core.ui.ReadAloud
import dev.kpetriv.sprout.core.ui.WindowWidthClass
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Golden screenshots of the chat screens, in src/test/screenshots. The kid thread covers every tier in every state
 * (empty, messages, locked, recording, failed); Sprout and Sapling have no microphone, so their "recording" golden is
 * the thread unchanged. Parent screens are captured light and dark. Re-record with
 * `./gradlew :feature:chat:recordRoborazziDebug`.
 */
class ChatScreenshotTest : ScreenshotTest(checkAccessibility = true) {
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
    fun `kid, this phone's chat key was replaced, sprout`() = screenshot.captureTiers(
        "chat_kid_key_conflict",
        tiers = listOf(KidUiTier.SPROUT),
    ) { tier ->
        ChatScreen(state = ChatPreviewData.kidKeyConflict(tier), onEvent = {})
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
    fun `parent thread, sending, failed, blocked and arrived late`() =
        screenshot.captureLightDark("chat_parent_send_states") {
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

    @Test
    fun `parent thread, this phone's chat key no longer matches`() =
        screenshot.captureLightDark("chat_parent_key_conflict") {
            ChatScreen(state = ChatPreviewData.parentKeyConflict(), onEvent = {})
        }

    // One dark golden per kid screen family (docs/SCREENSHOT_TESTS.md).
    @Test
    fun `kid, messages, dark theme, every tier`() =
        screenshot.captureTiers("chat_kid_messages_dark", darkTheme = true) { tier ->
            ChatScreen(state = ChatPreviewData.kidMessages(tier), onEvent = {}, readAloud = speech)
        }

    @Test
    fun `parent thread at font scale 2`() = screenshot.captureFontScale("chat_parent_thread") {
        ChatScreen(state = ChatPreviewData.parentThread(PhotoUi.Loaded(photo)), onEvent = {})
    }

    // The inbox and thread panes as the app's list-detail scene lays them out on a tablet (M7 §5): a 360 dp list pane
    // at the phone layout, the open thread beside it.
    @Test
    fun `parent inbox and thread side by side on a tablet`() = screenshot.captureWidthClasses(
        name = "chat_threads_detail",
        windows = listOf(ScreenshotWindow.TABLET),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalWindowWidthClass provides WindowWidthClass.Compact) {
                ChatThreadListScreen(
                    state = ChatPreviewData.threads(),
                    onEvent = {},
                    onOpenThread = {},
                    onBack = {},
                    modifier = Modifier.width(LIST_PANE_WIDTH),
                    selectedThreadId = KidId("kid-leo"),
                )
            }
            VerticalDivider()
            ChatScreen(
                state = ChatPreviewData.parentThread(PhotoUi.Loaded(photo)),
                onEvent = {},
                modifier = Modifier.weight(1f),
            )
        }
    }

    @Test
    fun `parent inbox with the thread placeholder on a tablet`() = screenshot.captureWidthClasses(
        name = "chat_threads_placeholder",
        windows = listOf(ScreenshotWindow.TABLET),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalWindowWidthClass provides WindowWidthClass.Compact) {
                ChatThreadListScreen(
                    state = ChatPreviewData.threads(),
                    onEvent = {},
                    onOpenThread = {},
                    onBack = {},
                    modifier = Modifier.width(LIST_PANE_WIDTH),
                )
            }
            VerticalDivider()
            ChatThreadPlaceholder(modifier = Modifier.weight(1f))
        }
    }

    private companion object {
        /** Material's default list pane width in a list-detail scene. */
        val LIST_PANE_WIDTH = 360.dp

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

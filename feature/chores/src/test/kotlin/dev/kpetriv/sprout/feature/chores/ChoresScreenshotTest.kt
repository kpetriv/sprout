package dev.kpetriv.sprout.feature.chores

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotTest
import dev.kpetriv.sprout.core.ui.ReadAloud
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Golden screenshots of the chores screens, in src/test/screenshots. Kid screens cover every tier; parent screens
 * are captured light and dark. Re-record with `./gradlew :feature:chores:recordRoborazziDebug`.
 */
class ChoresScreenshotTest : ScreenshotTest() {
    @Test
    @Config(qualifiers = TALL)
    fun `kid today, nothing submitted, every tier`() = screenshot.captureTiers("chores_today") { tier ->
        KidTodayScreen(state = ChoresPreviewData.today(tier), onEvent = {}, readAloud = speech)
    }

    @Test
    @Config(qualifiers = TALL)
    fun `kid today, waiting and done, every tier`() = screenshot.captureTiers("chores_today_pending") { tier ->
        KidTodayScreen(state = ChoresPreviewData.today(tier, pending = true), onEvent = {}, readAloud = speech)
    }

    @Test
    fun `approvals inbox, empty`() = screenshot.captureLightDark("chores_approvals_empty") {
        ApprovalsInboxScreen(state = ApprovalsUiState.Ready(emptyList(), emptyList()), onEvent = {})
    }

    @Test
    @Config(qualifiers = TALL)
    fun `approvals inbox, with items`() = screenshot.captureLightDark("chores_approvals") {
        val state = ChoresPreviewData.approvals()
        val withPhoto = state.submissions.mapIndexed { index, item ->
            if (index == 0) item.copy(photo = PhotoUi.Loaded(photo)) else item
        }
        ApprovalsInboxScreen(state = state.copy(submissions = withPhoto), onEvent = {})
    }

    @Test
    @Config(qualifiers = TALL)
    fun `chore editor, valid`() = screenshot.captureLightDark("chores_editor") {
        ChoreEditorScreen(state = ChoresPreviewData.editor(), onEvent = {})
    }

    @Test
    @Config(qualifiers = TALL)
    fun `chore editor, with errors`() = screenshot.captureLightDark("chores_editor_errors") {
        ChoreEditorScreen(
            state = ChoresPreviewData.editor(
                setOf(EditorError.NO_KID, EditorError.TITLE_BLANK, EditorError.MONEY_INVALID, EditorError.NO_DAYS),
            ),
            onEvent = {},
        )
    }

    @Test
    fun `chore list`() = screenshot.captureLightDark("chores_list") {
        ChoreListScreen(state = ChoresPreviewData.choreList(), onEvent = {})
    }

    private companion object {
        const val TALL = "+h1500dp"

        // Shows Seedling's read-aloud buttons, which are hidden without a speech implementation.
        val speech = ReadAloud { }

        /** A fixed two-tone "photo": the same pixels on every run. */
        val photo by lazy {
            val width = 64
            val height = 48
            val pixels = IntArray(width * height) { index ->
                if ((index % width) < width / 2) 0xFF8BD88F.toInt() else 0xFF00658E.toInt()
            }
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
}

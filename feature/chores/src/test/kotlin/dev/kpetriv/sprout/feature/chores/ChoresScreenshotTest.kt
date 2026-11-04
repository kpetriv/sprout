package dev.kpetriv.sprout.feature.chores

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotTest
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotWindow
import dev.kpetriv.sprout.core.ui.LocalWindowWidthClass
import dev.kpetriv.sprout.core.ui.ReadAloud
import dev.kpetriv.sprout.core.ui.WindowWidthClass
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Golden screenshots of the chores screens, in src/test/screenshots. Kid screens cover every tier; parent screens
 * are captured light and dark. Re-record with `./gradlew :feature:chores:recordRoborazziDebug`.
 */
class ChoresScreenshotTest : ScreenshotTest(checkAccessibility = true) {
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
    fun `approvals inbox, photo on the kid's phone (D-M3-9)`() =
        screenshot.captureLightDark("chores_approvals_photo_remote") {
            val state = ChoresPreviewData.approvals()
            val first = state.submissions.first().copy(photo = PhotoUi.OnKidDevice)
            ApprovalsInboxScreen(state = state.copy(submissions = listOf(first), proposals = emptyList()), onEvent = {})
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
    fun `chore editor, suggestions sheet`() = screenshot.captureLightDark("chores_editor_suggestions") {
        ChoreSuggestionsContent(
            sheet = SuggestionsUi.Ready(ChoresPreviewData.suggestions),
            kidName = "Leo",
            currency = ChoresPreviewData.gbp,
            onEvent = {},
            modifier = Modifier.padding(24.dp),
        )
    }

    @Test
    fun `chore list`() = screenshot.captureLightDark("chores_list") {
        ChoreListScreen(state = ChoresPreviewData.choreList(), onEvent = {})
    }

    @Test
    fun `kid today, loading, every tier`() = screenshot.captureTiers("chores_today_loading") {
        KidTodayScreen(state = KidTodayUiState.Loading, onEvent = {})
    }

    @Test
    fun `kid today, could not load, every tier`() = screenshot.captureTiers("chores_today_error") {
        KidTodayScreen(state = KidTodayUiState.Error(KidTodayError.LOAD_FAILED), onEvent = {})
    }

    @Test
    fun `kid today, nothing due, every tier`() = screenshot.captureTiers("chores_today_empty") { tier ->
        KidTodayScreen(state = ChoresPreviewData.todayEmpty(tier), onEvent = {}, readAloud = speech)
    }

    @Test
    @Config(qualifiers = TALL)
    fun `kid today, a chore not accepted, every tier`() = screenshot.captureTiers("chores_today_rejected") { tier ->
        KidTodayScreen(state = ChoresPreviewData.todayRejected(tier), onEvent = {}, readAloud = speech)
    }

    // Seedling has no sheet: "Done!" submits in one tap.
    @Test
    fun `kid today, submit sheet, sprout and sapling`() = screenshot.captureTiers(
        name = "chores_today_submit_sheet",
        tiers = listOf(KidUiTier.SPROUT, KidUiTier.SAPLING),
        screen = true,
    ) { tier ->
        KidTodayScreen(state = ChoresPreviewData.today(tier).copy(sheet = ChoresPreviewData.submitSheet), onEvent = {})
    }

    // Only Sapling kids can propose chores (ChoreProposalRules).
    @Test
    fun `kid today, propose sheet, sapling`() = screenshot.captureTiers(
        name = "chores_today_propose_sheet",
        tiers = listOf(KidUiTier.SAPLING),
        screen = true,
    ) { tier ->
        KidTodayScreen(
            state = ChoresPreviewData.today(tier).copy(propose = ChoresPreviewData.proposeSheet),
            onEvent = {},
        )
    }

    // The list and editor panes as the app's list-detail scene lays them out on a tablet (M7 §5): a 360 dp list pane
    // at the phone layout, the editor beside it.
    @Test
    fun `chore list and editor side by side on a tablet`() = screenshot.captureWidthClasses(
        name = "chores_list_detail",
        windows = listOf(ScreenshotWindow.TABLET),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalWindowWidthClass provides WindowWidthClass.Compact) {
                ChoreListScreen(
                    state = ChoresPreviewData.choreList(),
                    onEvent = {},
                    selectedChoreId = ChoreId("car"),
                    modifier = Modifier.width(LIST_PANE_WIDTH),
                )
            }
            VerticalDivider()
            ChoreEditorScreen(state = ChoresPreviewData.editor(), onEvent = {}, modifier = Modifier.weight(1f))
        }
    }

    @Test
    fun `chore list with the editor placeholder on a tablet`() = screenshot.captureWidthClasses(
        name = "chores_list_placeholder",
        windows = listOf(ScreenshotWindow.TABLET),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalWindowWidthClass provides WindowWidthClass.Compact) {
                ChoreListScreen(
                    state = ChoresPreviewData.choreList(),
                    onEvent = {},
                    modifier = Modifier.width(LIST_PANE_WIDTH),
                )
            }
            VerticalDivider()
            ChoreEditorPlaceholder(modifier = Modifier.weight(1f))
        }
    }

    private companion object {
        const val TALL = "+h1500dp"

        /** Material's default list pane width in a list-detail scene. */
        val LIST_PANE_WIDTH = 360.dp

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

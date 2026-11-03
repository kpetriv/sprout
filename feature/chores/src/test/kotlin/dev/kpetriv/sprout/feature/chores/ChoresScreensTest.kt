package dev.kpetriv.sprout.feature.chores

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.SubmissionId
import dev.kpetriv.sprout.core.ui.ReadAloud
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Renders each chores screen's key states under Robolectric. A tall window keeps every row composed. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rGB-w411dp-h2400dp")
class ChoresScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val kidEvents = mutableListOf<KidTodayEvent>()
    private val spoken = mutableListOf<String>()

    private fun showToday(state: KidTodayUiState, readAloud: ReadAloud = ReadAloud { spoken += it }) {
        compose.setContent {
            SproutTheme { KidTodayScreen(state = state, onEvent = { kidEvents += it }, readAloud = readAloud) }
        }
    }

    @Test
    fun `seedling shows three picture cards with done buttons and read aloud`() {
        showToday(ChoresPreviewData.today(KidUiTier.SEEDLING))

        compose.onNodeWithText("Hi Mia!").assertIsDisplayed()
        assertEquals(3, compose.onAllNodesWithText("Done!").fetchSemanticsNodes().size)
        compose.onNodeWithText("Tidy toys").assertDoesNotExist()
        compose.onNodeWithText("Next 7 days").assertDoesNotExist()

        compose.onAllNodesWithText("Done!")[0].performClick()
        assertEquals(KidTodayEvent.Done(ChoreId("bed")), kidEvents.last())

        compose.onNodeWithContentDescription("Read Make your bed to me").performClick()
        assertEquals(listOf("Make your bed. You get 0 coins."), spoken)
    }

    @Test
    fun `seedling shows waiting and done states and hides read aloud without speech`() {
        showToday(ChoresPreviewData.today(KidUiTier.SEEDLING, pending = true), readAloud = ReadAloud.None)

        compose.onNodeWithText("Waiting for a parent").assertIsDisplayed()
        compose.onNodeWithText("Done, +1 coin + 15 min").assertIsDisplayed()
        compose.onNodeWithContentDescription("Read Make your bed to me").assertDoesNotExist()
    }

    @Test
    fun `sprout lists today with rewards, upcoming days and no proposals`() {
        showToday(ChoresPreviewData.today(KidUiTier.SPROUT, pending = true))

        compose.onNodeWithText("Leo's chores").assertIsDisplayed()
        compose.onAllNodesWithText("£0.50")[0].assertIsDisplayed()
        compose.onNodeWithText("Next 7 days").assertIsDisplayed()
        compose.onNodeWithText("Tomorrow").assertIsDisplayed()
        compose.onNodeWithText("Propose a chore").assertDoesNotExist()

        compose.onNodeWithContentDescription("I did Make your bed").performClick()
        assertEquals(KidTodayEvent.OpenSubmit(ChoreId("bed")), kidEvents.last())
    }

    @Test
    fun `sapling shows its ideas and can propose`() {
        showToday(ChoresPreviewData.today(KidUiTier.SAPLING))

        compose.onNodeWithText("Walk the dog").assertIsDisplayed()
        compose.onNodeWithText("Propose a chore").performClick()
        assertEquals(KidTodayEvent.OpenPropose, kidEvents.last())
    }

    @Test
    fun `submit form takes a note, a photo and sends`() {
        var photoRequests = 0
        compose.setContent {
            SproutTheme {
                SubmitForm(
                    sheet = SubmitSheetUi(ChoreId("bed"), "Make bed"),
                    tier = KidUiTier.SPROUT,
                    onEvent = { kidEvents += it },
                    onAddPhoto = { photoRequests++ },
                )
            }
        }
        compose.onNodeWithText("Note for your parent (optional)").performTextInput("All done")
        compose.onNodeWithText("Add photo").performClick()
        compose.onNodeWithText("Send").performClick()

        assertEquals(KidTodayEvent.NoteChanged("All done"), kidEvents.first())
        assertEquals(1, photoRequests)
        assertEquals(KidTodayEvent.ConfirmSubmit, kidEvents.last())
    }

    @Test
    fun `approvals inbox shows submissions and proposals and sends decisions`() {
        val events = mutableListOf<ApprovalsEvent>()
        compose.setContent {
            SproutTheme { ApprovalsInboxScreen(state = ChoresPreviewData.approvals(), onEvent = { events += it }) }
        }

        compose.onNodeWithText("Leo: Make bed").assertIsDisplayed()
        compose.onNodeWithText("“Did it before school!”").assertIsDisplayed()
        compose.onNodeWithText("£2.50 + 15 min").assertIsDisplayed()
        compose.onNodeWithText("The photo can't be shown.").assertIsDisplayed()
        compose.onNodeWithText("Ava suggests: Walk the dog").assertIsDisplayed()

        compose.onNodeWithContentDescription("Approve Make bed for Leo").performClick()
        compose.onNodeWithContentDescription("Reject Tidy toys for Mia").performClick()
        compose.onNodeWithText("Accept").performClick()
        assertEquals(
            listOf(
                ApprovalsEvent.Approve(SubmissionId("sub-1")),
                ApprovalsEvent.OpenReject(SubmissionId("sub-2")),
                ApprovalsEvent.AcceptProposal(ChoreId("idea")),
            ),
            events,
        )
    }

    @Test
    fun `approvals inbox disables decisions while one is in progress`() {
        compose.setContent {
            SproutTheme {
                ApprovalsInboxScreen(state = ChoresPreviewData.approvals().copy(busy = "sub-1"), onEvent = {})
            }
        }
        compose.onNodeWithContentDescription("Reject Make bed for Leo").assertIsNotEnabled()
        compose.onNodeWithText("Decline").assertIsNotEnabled()
    }

    @Test
    fun `empty approvals inbox`() {
        compose.setContent {
            SproutTheme {
                ApprovalsInboxScreen(state = ApprovalsUiState.Ready(emptyList(), emptyList()), onEvent = {})
            }
        }
        compose.onNodeWithText("Nothing to approve right now.").assertIsDisplayed()
    }

    @Test
    fun `editor shows validation errors`() {
        compose.setContent {
            SproutTheme {
                ChoreEditorScreen(
                    state = ChoresPreviewData.editor(
                        setOf(
                            EditorError.NO_KID,
                            EditorError.TITLE_BLANK,
                            EditorError.MONEY_INVALID,
                            EditorError.NO_DAYS,
                        ),
                    ),
                    onEvent = {},
                )
            }
        }
        compose.onNodeWithText("Choose who this chore is for.").assertIsDisplayed()
        compose.onNodeWithText("Give the chore a title.").assertIsDisplayed()
        compose.onNodeWithText("Enter an amount like 2.50.").assertIsDisplayed()
        compose.onNodeWithText("Pick at least one day.").assertIsDisplayed()
    }

    @Test
    fun `editor sends edits and save`() {
        val events = mutableListOf<ChoreEditorEvent>()
        compose.setContent {
            SproutTheme { ChoreEditorScreen(state = ChoresPreviewData.editor(), onEvent = { events += it }) }
        }
        compose.onNodeWithText("Mia").performClick()
        compose.onNodeWithText("Monthly").performClick()
        compose.onNodeWithText("Save").performClick()
        assertTrue(events.any { it is ChoreEditorEvent.KidChanged })
        assertTrue(ChoreEditorEvent.ScheduleKindChanged(ScheduleKind.MONTHLY) in events)
        assertEquals(ChoreEditorEvent.Save, events.last())
    }

    @Test
    fun `chore list groups by kid with state chips and archive`() {
        val events = mutableListOf<ChoreListEvent>()
        val edited = mutableListOf<ChoreId>()
        compose.setContent {
            SproutTheme {
                ChoreListScreen(
                    state = ChoresPreviewData.choreList(),
                    onEvent = { events += it },
                    onEditChore = { edited += it },
                )
            }
        }
        compose.onNodeWithText("Approvals (2)").assertIsDisplayed()
        compose.onNodeWithText("Proposed").assertIsDisplayed()
        compose.onNodeWithText("No chores for Mia yet.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Archive Make bed").performClick()
        assertEquals(ChoreListEvent.Archive(ChoreId("bed")), events.last())
    }
}

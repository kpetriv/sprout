package dev.kpetriv.sprout.feature.parenthome

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Renders [ParentHomeScreen] under Robolectric. A tall window keeps every card composed. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h3000dp")
class ParentHomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<String>()
    private val events = mutableListOf<ParentHomeEvent>()
    private var signNow = 0

    private val links = ParentHomeLinks(
        onOpenChores = { opened += "chores" },
        onOpenApprovals = { opened += "approvals" },
        onOpenShop = { opened += "shop" },
        onOpenSettings = { opened += "settings" },
        onSwitchToKidMode = { opened += "kid mode" },
        onOpenKid = { opened += "kid ${it.value}" },
        onRegisterSigningKey = { opened += "security" },
        onOpenAiSettings = { opened += "ai settings" },
    )

    private fun show(state: ParentHomeUiState) = compose.setContent {
        SproutTheme { ParentHomeScreen(state, onEvent = { events += it }, onSignNow = { signNow++ }, links = links) }
    }

    @Test
    fun `a busy household shows every card`() {
        show(ParentHomePreviewData.busy())

        compose.onNodeWithText("4 chores are waiting for you").assertIsDisplayed()
        compose.onNodeWithText("2 allowances and 1 interest payment waiting for your fingerprint").assertIsDisplayed()
        compose.onNodeWithText(
            "1 entry needs a look (an overdraft, a conflict or a rejected event)",
        ).assertIsDisplayed()
        compose.onNodeWithText("£12.50").assertIsDisplayed()
        compose.onNodeWithText("1 h 20 min").assertIsDisplayed()
        compose.onNodeWithText("1 issue to review").assertIsDisplayed()
        compose.onNodeWithText("No allowance set").assertIsDisplayed()
        compose.onNodeWithText("Sapling").assertIsDisplayed()
        compose.onAllNodes(hasText("Next allowance:", substring = true)).assertCountEquals(2)
        compose.onNodeWithText("Weekly summary").assertIsDisplayed()
    }

    @Test
    fun `the summary card toggles weeks and offers the model download`() {
        show(ParentHomePreviewData.busy())

        compose.onNodeWithText("On-device").assertIsDisplayed()
        compose.onNodeWithText("So far this week", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Last week").performClick()
        compose.onNodeWithText("Download the on-device model").performClick()

        assertEquals(ParentHomeEvent.SummaryWeekChanged(SummaryWeek.LAST_WEEK), events.last())
        assertEquals(listOf("ai settings"), opened)
    }

    @Test
    fun `the summary card fails soft`() {
        show(ParentHomePreviewData.empty().copy(summary = WeeklySummaryUi.Unavailable))
        compose.onNodeWithText("The summary is not available right now.").assertIsDisplayed()
        compose.onNodeWithText("Download the on-device model").assertDoesNotExist()
    }

    @Test
    fun `no download action without an offer`() {
        show(ParentHomePreviewData.empty().copy(summary = ParentHomePreviewData.summary(offerModelDownload = false)))
        compose.onNodeWithText("So far this week", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Download the on-device model").assertDoesNotExist()
    }

    @Test
    fun `an unregistered signing key is shown and Retry opens security settings`() {
        show(ParentHomePreviewData.signingKeyNotRegistered())

        compose.onNodeWithText("Signing key not registered with the household").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()

        assertEquals(listOf("security"), opened)
    }

    @Test
    fun `signing off is shown and Turn on opens security settings`() {
        show(ParentHomePreviewData.signingOff())

        compose.onNodeWithText("Fingerprint signing is off").assertIsDisplayed()
        compose.onNodeWithText("Turn on").performClick()

        assertEquals(listOf("security"), opened)
    }

    @Test
    fun `a blocked sync names a human reason, never the server's code`() {
        show(ParentHomePreviewData.syncBlocked())

        compose.onAllNodes(hasText("permission-denied", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("isn’t allowed to make it", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a registered signing key shows no such card`() {
        show(ParentHomePreviewData.household())

        compose.onAllNodes(hasText("Signing key not registered with the household")).assertCountEquals(0)
    }

    @Test
    fun `sign now, review and the quick links call back`() {
        show(ParentHomePreviewData.busy())

        compose.onNodeWithText("Sign now").performClick()
        compose.onNodeWithText("Chores").performClick()
        compose.onNodeWithText("Approvals").performClick()
        compose.onNodeWithText("Shop (parent view)").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Switch to kid mode").performClick()
        compose.onNodeWithText("Leo").performClick()

        assertEquals(1, signNow)
        assertEquals(listOf("chores", "approvals", "shop", "settings", "kid mode", "kid kid-leo"), opened)
    }

    @Test
    fun `while signing the progress shows and sign now is disabled`() {
        show(ParentHomePreviewData.busy(signing = SigningProgress(2, 3)))

        compose.onNodeWithText("2 of 3 signed").assertIsDisplayed()
        compose.onNodeWithText("Sign now").assertIsNotEnabled()
    }

    @Test
    fun `a stopped signing run is announced and acknowledged`() {
        show(ParentHomePreviewData.busy().copy(signResult = SignResult.Stopped(signed = 1, total = 3)))

        compose.onNodeWithText(
            "Signing stopped: 1 of 3 signed. The rest wait until you tap Sign now again.",
        ).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(SNACKBAR_MILLIS)
        compose.waitForIdle()
        assertEquals(listOf<ParentHomeEvent>(ParentHomeEvent.SignResultShown), events)
    }

    @Test
    fun `an empty household explains what to do`() {
        show(ParentHomePreviewData.empty())

        compose.onNodeWithText("Nothing waiting. Nice.").assertIsDisplayed()
        compose.onNodeWithText("No payments waiting for your fingerprint.").assertIsDisplayed()
        compose.onNodeWithText("Every balance adds up.").assertIsDisplayed()
        compose.onNodeWithText("No kids yet. Add one in Settings.").assertIsDisplayed()
        compose.onNodeWithText("Sign now").assertDoesNotExist()
    }

    @Test
    fun `the anomalies card opens the first kid with anomalies`() {
        show(ParentHomePreviewData.busy())

        // The approvals card's button comes first, then the anomalies card's.
        val reviews = compose.onAllNodes(hasText("Review") and hasClickAction())
        reviews[0].performClick()
        reviews[1].performClick()

        assertEquals(listOf("approvals", "kid ${ParentHomePreviewData.leo.id.value}"), opened)
    }

    @Test
    fun `payments that need attention are listed and can be dismissed`() {
        show(ParentHomePreviewData.needsAttention())

        compose.onNodeWithText("Payments that need attention").assertIsDisplayed()
        compose.onNodeWithText("Zoe: £0.21 savings interest for 2026-10").assertIsDisplayed()
        compose.onNodeWithText("Couldn't be paid: it breaks a ledger rule").assertIsDisplayed()
        compose.onNodeWithText("Leo: £3.00 allowance for 2026-W30").assertIsDisplayed()
        compose.onNodeWithText("Skipped: too far in the past to catch up").assertIsDisplayed()
        compose.onAllNodes(hasText("Dismiss") and hasClickAction())[1].performClick()

        assertEquals(
            listOf<ParentHomeEvent>(ParentHomeEvent.Dismiss(ParentHomePreviewData.SKIPPED_KEY)),
            events,
        )
    }

    private companion object {
        const val SNACKBAR_MILLIS = 10_000L
    }
}

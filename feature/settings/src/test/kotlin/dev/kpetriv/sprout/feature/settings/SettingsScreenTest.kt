package dev.kpetriv.sprout.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.KidUiTier
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Renders the settings screens under Robolectric. A tall window keeps every section composed. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2400dp")
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) = compose.setContent { SproutTheme { content() } }

    @Test
    fun `the hub lists every section and opens the one tapped`() {
        val opened = mutableListOf<SettingsSection>()
        show { SettingsScreen(onOpen = { opened += it }, onBack = {}) }

        listOf("Kids", "Allowance", "Savings goals", "Interest", "Parent security", "On-device AI", "About").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithText("Interest").performClick()

        assertEquals(listOf(SettingsSection.INTEREST), opened)
    }

    @Test
    fun `on-device AI shows the status, the switch and the download consent`() {
        val events = mutableListOf<AiSettingsEvent>()
        show { AiSettingsScreen(SettingsPreviewData.ai(), onEvent = { events += it }, onBack = {}) }

        compose.onNodeWithText("This phone can run Gemini Nano", substring = true).assertIsDisplayed()
        compose.onNodeWithText("What it is used for").assertIsDisplayed()
        compose.onNodeWithText("Applies to this phone only", substring = true).assertIsDisplayed()
        compose.onNodeWithText("the kindness check in chat uses the built-in rules", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Download the model").performClick()
        compose.onNodeWithText("Use on-device AI").performClick()

        assertEquals(
            listOf(AiSettingsEvent.DownloadConsentChanged(true), AiSettingsEvent.EnabledChanged(false)),
            events,
        )
    }

    @Test
    fun `on-device AI on a phone without AICore says so and offers no download`() {
        show { AiSettingsScreen(SettingsPreviewData.ai(AiModelStatus.UNAVAILABLE), onEvent = {}, onBack = {}) }

        compose.onNodeWithText("Not available on this device", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Download the model").assertDoesNotExist()
    }

    @Test
    fun `kids list shows age and tier override, and add opens the sheet`() {
        val events = mutableListOf<KidsSettingsEvent>()
        show { KidsSettingsScreen(SettingsPreviewData.kids(), onEvent = { events += it }, onBack = {}) }

        compose.onNodeWithText("Age 9 · Sprout").assertIsDisplayed()
        compose.onNodeWithText("Age 13 · Sprout (suggested: Sapling)").assertIsDisplayed()
        compose.onNodeWithText("Zoe").performClick()
        compose.onNodeWithText("Add kid").performClick()

        assertEquals(KidsSettingsEvent.EditKid(SettingsPreviewData.zoe.id), events[0])
        assertEquals(KidsSettingsEvent.AddKid, events[1])
    }

    @Test
    fun `kid form shows the suggestion and archives only after confirming`() {
        val events = mutableListOf<KidsSettingsEvent>()
        val editor = KidEditorUi(
            kidId = SettingsPreviewData.zoe.id,
            firstName = "Zoe",
            birthYear = 2013,
            birthMonth = 9,
            tier = KidUiTier.SPROUT,
            tierChosen = true,
            suggestedTier = KidUiTier.SAPLING,
            years = (2026 downTo 2008).toList(),
        )
        show { KidEditorForm(editor, onEvent = { events += it }) }

        compose.onNodeWithText("Suggested: Sapling").assertIsDisplayed()
        compose.onNodeWithText("Sapling").performClick()
        compose.onNodeWithText("Archive kid").performScrollTo().performClick()
        assertTrue(KidsSettingsEvent.Archive !in events)
        compose.onNodeWithText("Archive").performClick()

        assertEquals(listOf(KidsSettingsEvent.TierChosen(KidUiTier.SAPLING), KidsSettingsEvent.Archive), events)
    }

    @Test
    fun `allowance form shows the next payment and sends edits`() {
        val events = mutableListOf<AllowanceSettingsEvent>()
        val editor = AllowanceEditorUi(
            kidId = SettingsPreviewData.leo.id,
            kidName = "Leo",
            scheduleId = null,
            currency = SettingsPreviewData.gbp,
            amountText = "",
            cadence = CadenceKind.WEEKLY,
            dayOfWeek = DayOfWeek.SATURDAY,
            dayOfMonth = 1,
            startsOn = CalendarDate(2026, 11, 3),
            active = true,
            nextDue = CalendarDate(2026, 11, 7),
            amountError = AmountError.REQUIRED,
        )
        show { AllowanceEditorForm(editor, onEvent = { events += it }) }

        compose.onNodeWithText("Allowance for Leo").assertIsDisplayed()
        compose.onNode(hasText("Next payment:", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Required").assertIsDisplayed()
        compose.onNodeWithText("Amount (GBP)").performTextInput("5")
        compose.onNodeWithText("Monthly").performClick()
        compose.onNodeWithText("Save").performClick()

        assertEquals(
            listOf(
                AllowanceSettingsEvent.AmountChanged("5"),
                AllowanceSettingsEvent.CadenceChanged(CadenceKind.MONTHLY),
                AllowanceSettingsEvent.Save,
            ),
            events,
        )
    }

    @Test
    fun `interest shows the preview and saves only after a change`() {
        val events = mutableListOf<InterestSettingsEvent>()
        show { InterestSettingsScreen(SettingsPreviewData.interest(), onEvent = { events += it }, onBack = {}) }

        compose.onNodeWithText("On £100.00 saved for a month you'd earn about £0.21.").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Yearly rate (%)").performTextReplacement("3")

        assertEquals(listOf<InterestSettingsEvent>(InterestSettingsEvent.RateChanged("3")), events)
    }

    @Test
    fun `interest with a changed rate can be saved`() {
        val events = mutableListOf<InterestSettingsEvent>()
        show {
            InterestSettingsScreen(SettingsPreviewData.interest("3"), onEvent = { events += it }, onBack = {})
        }
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        assertEquals(listOf<InterestSettingsEvent>(InterestSettingsEvent.Save), events)
    }

    @Test
    fun `a rate above twenty percent shows the limit`() {
        show { InterestSettingsScreen(SettingsPreviewData.interest("21"), onEvent = {}, onBack = {}) }

        compose.onNodeWithText("At most 20.00%").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun `without a strong biometric the D-M2-1 warning is shown`() {
        show {
            ParentSecurityScreen(
                SettingsPreviewData.security(ParentAuthAvailability.NO_HARDWARE, hasParentKey = false),
                onEvent = {},
                onBack = {},
            )
        }

        compose.onNodeWithText("Money changes on this device can't be fingerprint-signed").assertIsDisplayed()
        compose.onNode(hasText("no strong fingerprint", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Household policy: unsigned parent changes accepted").assertIsDisplayed()
        compose.onNodeWithText("Turn on fingerprint signing").assertDoesNotExist()
    }

    @Test
    fun `fingerprint signing can be turned on when available and not set up`() {
        val events = mutableListOf<ParentSecurityEvent>()
        show {
            ParentSecurityScreen(
                SettingsPreviewData.security(hasParentKey = false),
                onEvent = { events += it },
                onBack = {},
            )
        }

        compose.onNodeWithText("Turn on fingerprint signing").performClick()
        assertEquals(listOf<ParentSecurityEvent>(ParentSecurityEvent.StartSigningKeyChange), events)
    }

    @Test
    fun `a changed fingerprint offers renewal`() {
        val events = mutableListOf<ParentSecurityEvent>()
        show {
            ParentSecurityScreen(
                SettingsPreviewData.security(keyNeedsRenewal = true),
                onEvent = { events += it },
                onBack = {},
            )
        }

        compose.onNodeWithText("Fingerprint changed: renew signing key").assertIsDisplayed()
        compose.onNodeWithText("Renew signing key").performClick()
        assertEquals(listOf<ParentSecurityEvent>(ParentSecurityEvent.StartSigningKeyChange), events)
    }

    @Test
    fun `the signing PIN is typed on a pad`() {
        val events = mutableListOf<ParentSecurityEvent>()
        show {
            ParentSecurityScreen(
                SettingsPreviewData.security(
                    keyNeedsRenewal = true,
                    signingPin = SigningPinUi(SigningPinPurpose.RENEW, enteredDigits = 2),
                ),
                onEvent = { events += it },
                onBack = {},
            )
        }

        compose.onNodeWithText("Enter your PIN to renew the signing key").assertIsDisplayed()
        compose.onNodeWithContentDescription("2 of 6 digits entered").assertIsDisplayed()
        compose.onAllNodesWithText("4")[0].performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(
            listOf(ParentSecurityEvent.SigningPinDigit('4'), ParentSecurityEvent.CancelSigningKeyChange),
            events,
        )
    }

    @Test
    fun `the change-PIN form is a pad with no text field`() {
        val events = mutableListOf<ParentSecurityEvent>()
        show {
            ParentSecurityScreen(
                SettingsPreviewData.security(changePin = ChangePinUi(ChangePinStep.NEW, enteredDigits = 3)),
                onEvent = { events += it },
                onBack = {},
            )
        }

        compose.onNodeWithText("Enter a new PIN (6 digits)").assertIsDisplayed()
        compose.onNodeWithContentDescription("3 of 6 digits entered").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("7").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Delete digit").performScrollTo().performClick()
        compose.onNodeWithText("Start over").performScrollTo().performClick()

        assertEquals(
            listOf(
                ParentSecurityEvent.ChangePinDigit('7'),
                ParentSecurityEvent.ChangePinBackspace,
                ParentSecurityEvent.ChangePinStartOver,
            ),
            events,
        )
    }

    @Test
    fun `PIN errors are explained`() {
        show {
            ParentSecurityScreen(
                SettingsPreviewData.security(pinError = PinChangeError.Invalid(NewPinProblem.SEQUENCE)),
                onEvent = {},
                onBack = {},
            )
        }
        compose.onNodeWithText("Too easy to guess: avoid runs like 123456").assertIsDisplayed()
    }
}

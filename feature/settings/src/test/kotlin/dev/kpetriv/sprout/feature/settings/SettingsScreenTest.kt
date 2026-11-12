package dev.kpetriv.sprout.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
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
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.testing.accessibility.AccessibilityRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Renders the settings screens under Robolectric. A tall window keeps every section composed. Every test ends with
 * the accessibility rule (M7 §8, D-M7-5) over whatever is left on screen, dialogs included.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2400dp")
class SettingsScreenTest {
    @get:Rule(order = 0)
    val compose = createComposeRule()

    @get:Rule(order = 1)
    val a11y = AccessibilityRule(compose)

    private fun show(content: @Composable () -> Unit) = compose.setContent { SproutTheme { content() } }

    @Test
    fun `the hub lists every section and opens the one tapped`() {
        val opened = mutableListOf<SettingsSection>()
        show { SettingsScreen(onOpen = { opened += it }, onBack = null) }

        listOf("Kids", "Allowance", "Savings goals", "Interest", "Parent security", "On-device AI", "About").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithText("Interest").performClick()

        assertEquals(listOf(SettingsSection.INTEREST), opened)
    }

    @Test
    fun `the hub as the Settings tab root has no back arrow (M9 §5-2)`() {
        show { SettingsScreen(onOpen = {}, onBack = null) }

        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    fun `a section keeps its back arrow to the hub`() {
        var backs = 0
        show { InterestSettingsScreen(SettingsPreviewData.interest(), onEvent = {}, onBack = { backs++ }) }

        compose.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backs)
    }

    @Test
    fun `on-device AI shows the status, the switch and the download consent`() {
        val events = mutableListOf<AiSettingsEvent>()
        show { AiSettingsScreen(SettingsPreviewData.ai(), onEvent = { events += it }, onBack = {}) }

        compose.onNodeWithText("This phone can run Gemini Nano", substring = true).assertIsDisplayed()
        compose.onNodeWithText("What it is used for").assertIsDisplayed()
        compose.onNodeWithText("Applies to this phone only", substring = true).assertIsDisplayed()
        compose.onNodeWithText("the kindness check in chat uses the built-in rules", substring = true)
            .assertIsDisplayed()
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

    private val household = HouseholdId("hh7f3k2q9xabc")

    @Test
    fun `the data section offers chat retention and sends the period picked`() {
        val events = mutableListOf<DataEvent>()
        show {
            DataScreen(
                DataUiState(householdId = household, retention = RetentionUi(days = 90)),
                onEvent = { events += it },
                onBack = {},
            )
        }

        compose.onNodeWithText("Keep chat messages for").assertIsDisplayed()
        compose.onNodeWithContentDescription("Keep chat messages for, 90 days").performClick()
        compose.onNodeWithText("30 days").performClick()
        compose.onNodeWithText("Delete chat history now").performScrollTo().performClick()

        assertEquals(listOf(DataEvent.RetentionChosen(30), DataEvent.AskDeleteChat), events)
    }

    @Test
    fun `a shorter retention asks before deleting older messages`() {
        val events = mutableListOf<DataEvent>()
        show {
            DataScreen(
                DataUiState(
                    householdId = household,
                    retention = RetentionUi(days = null, pending = RetentionChoice(30)),
                ),
                onEvent = { events += it },
                onBack = {},
            )
        }

        compose.onNodeWithText("Keep chat messages for 30 days?").assertIsDisplayed()
        compose.onNodeWithText("Delete older messages").performClick()
        assertEquals(listOf<DataEvent>(DataEvent.ConfirmRetention), events)
    }

    @Test
    fun `delete chat history is enabled only once the word is typed`() {
        val events = mutableListOf<DataEvent>()
        var typed by mutableStateOf("")
        show {
            DeleteChatCard(DataUiState(householdId = household, chatDeleteTyped = typed), onEvent = { events += it })
        }

        compose.onNodeWithText("To confirm, type DELETE below.").assertIsDisplayed()
        compose.onNodeWithText("Delete chat history").assertIsNotEnabled()
        compose.onNodeWithText("Confirmation").performTextInput("delete")
        typed = "delete"
        compose.onNodeWithText("Delete chat history").assertIsEnabled().performClick()

        assertEquals(listOf(DataEvent.ChatDeleteTyped("delete"), DataEvent.ConfirmDeleteChat), events)
    }

    @Test
    fun `a running export says what it is doing and cannot start twice`() {
        show {
            DataScreen(
                DataUiState(householdId = household, exportProgress = ExportProgress(ExportStage.MEDIA, 1, 4)),
                onEvent = {},
                onBack = {},
            )
        }

        compose.onNodeWithText("Adding photos and voice notes (1 of 4)…").assertIsDisplayed()
        compose.onNodeWithText("Export").assertIsNotEnabled()
        compose.onNodeWithText("decrypted on this phone", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the allowance section shows the daily game limit and opens its dialog`() {
        val events = mutableListOf<AllowanceSettingsEvent>()
        show {
            AllowanceSettingsScreen(
                AllowanceSettingsUiState(
                    isLoading = false,
                    gameCap = GameCapUi(capMinor = 50, isDefault = true, currency = SettingsPreviewData.gbp),
                ),
                onEvent = { events += it },
                onBack = {},
            )
        }

        compose.onNodeWithText("Brain games can earn each kid up to 50p a day (the default)").assertIsDisplayed()
        compose.onNodeWithText("Daily game limit").performClick()
        assertEquals(listOf<AllowanceSettingsEvent>(AllowanceSettingsEvent.EditGameCap), events)
    }

    @Test
    fun `the game limit dialog explains a limit that is too high and resets to the default`() {
        val events = mutableListOf<AllowanceSettingsEvent>()
        show {
            GameCapDialogCard(
                GameCapEditorUi("120", SettingsPreviewData.gbp, error = AmountError.TOO_LARGE),
                onEvent = { events += it },
            )
        }

        compose.onNodeWithText("At most £100.00").assertIsDisplayed()
        compose.onNodeWithText("Use the default (50p)").performClick()
        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf(AllowanceSettingsEvent.ResetGameCap, AllowanceSettingsEvent.SaveGameCap), events)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `on a wide window the hub stays beside the open section`() {
        val opened = mutableListOf<SettingsSection>()
        var selected by mutableStateOf<SettingsSection?>(null)
        show {
            SettingsListDetail(
                sections = SettingsSection.entries,
                selected = selected,
                onOpen = { opened += it },
                onBack = {},
                detail = selected?.let {
                    { modifier ->
                        AboutScreen(
                            SettingsPreviewData.about,
                            onEvent = {},
                            onOpenLicences = {},
                            onBack = {},
                            modifier = modifier,
                        )
                    }
                },
            )
        }

        compose.onNodeWithText("Choose a section").assertIsDisplayed()
        compose.onNodeWithText("About").performScrollTo().performClick()
        selected = SettingsSection.ABOUT
        compose.onNodeWithText("1.0.0 (build 128)").assertIsDisplayed()
        compose.onNode(hasText("Version and privacy")).assertIsSelected()
        compose.onNodeWithText("Kids").assertIsNotSelected()
        assertEquals(listOf(SettingsSection.ABOUT), opened)
    }

    @Test
    fun `About opens the policies and the licences and shows the build number`() {
        val events = mutableListOf<AboutEvent>()
        var licencesOpened = false
        show {
            AboutScreen(
                SettingsPreviewData.about,
                onEvent = { events += it },
                onOpenLicences = { licencesOpened = true },
                onBack = {},
            )
        }

        compose.onNodeWithText("Privacy policy").performClick()
        compose.onNodeWithText("Security and disclosure").performClick()
        compose.onNodeWithText("Open-source licences").performClick()
        compose.onNodeWithText("Version").assertIsDisplayed()
        compose.onNodeWithText("1.0.0 (build 128)").assertIsDisplayed()
        assertEquals(listOf(AboutEvent.OpenPrivacyPolicy, AboutEvent.OpenSecurityPolicy), events)
        assertTrue(licencesOpened)
    }

    @Test
    fun `About without configured policy pages shows no policy rows`() {
        show {
            AboutScreen(
                SettingsPreviewData.about.copy(hasPrivacyPolicy = false, hasSecurityPolicy = false),
                onEvent = {},
                onOpenLicences = {},
                onBack = {},
            )
        }

        compose.onAllNodesWithText("Privacy policy").assertCountEquals(0)
        compose.onAllNodesWithText("Security and disclosure").assertCountEquals(0)
        compose.onNodeWithText("Open-source licences").assertIsDisplayed()
    }

    @Test
    fun `the licences page lists each library and opens the ones with a link`() {
        val events = mutableListOf<AboutEvent>()
        val about = SettingsPreviewData.about
        show { OpenSourceLicencesScreen(about, onEvent = { events += it }, onBack = {}) }

        compose.onNodeWithText("androidx.activity:activity 1.13.0\nApache-2.0").assertIsDisplayed()
        compose.onNodeWithText("Activity").performClick()
        compose.onNodeWithText("Timber").performClick()
        compose.onNodeWithText("com.jakewharton.timber:timber 5.0.1\nLicence not stated in the library's metadata")
            .assertIsDisplayed()
        assertEquals(listOf<AboutEvent>(AboutEvent.OpenLibrary(about.libraries!!.first())), events)
    }

    @Test
    fun `the licences page says when the list could not be loaded`() {
        show {
            OpenSourceLicencesScreen(
                AboutUiState(versionName = "1.0.0", licencesFailed = true),
                onEvent = {},
                onBack = {},
            )
        }

        compose.onNodeWithText("The licence list couldn't be loaded.").assertIsDisplayed()
    }
}

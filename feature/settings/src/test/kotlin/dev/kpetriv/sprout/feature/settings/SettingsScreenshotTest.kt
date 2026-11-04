package dev.kpetriv.sprout.feature.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotTest
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotWindow
import dev.kpetriv.sprout.core.ui.WindowWidthClass
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Goldens in src/test/screenshots; see docs/SCREENSHOT_TESTS.md. Every variant is also checked with the accessibility
 * rule (D-M7-5).
 */
class SettingsScreenshotTest : ScreenshotTest(checkAccessibility = true) {
    @Test
    fun hub() = screenshot.captureLightDark("settings_hub") {
        SettingsScreen(onOpen = {}, onBack = {})
    }

    @Test
    fun `hub of the trial household, without the synced sections`() =
        screenshot.captureLightDark("settings_hub_trial") {
            SettingsScreen(onOpen = {}, onBack = {}, sections = SettingsSection.entries.filterNot { it.needsHousehold })
        }

    @Test
    fun `hub beside a section on a tablet (D-M7-2)`() = screenshot.captureWidthClasses("settings_hub") { widthClass ->
        if (widthClass == WindowWidthClass.Expanded) {
            SettingsListDetail(
                sections = SettingsSection.entries,
                selected = SettingsSection.ALLOWANCE,
                onOpen = {},
                onBack = {},
                detail = { modifier ->
                    AllowanceSettingsScreen(SettingsPreviewData.allowance(), onEvent = {}, onBack = {}, modifier)
                },
            )
        } else {
            SettingsScreen(onOpen = {}, onBack = {})
        }
    }

    @Test
    fun `hub on a tablet with no section open`() =
        screenshot.captureWidthClasses("settings_hub_none", windows = listOf(ScreenshotWindow.TABLET)) {
            SettingsListDetail(SettingsSection.entries, selected = null, onOpen = {}, onBack = {}, detail = null)
        }

    @Test
    fun kids() = screenshot.captureLightDark("settings_kids") {
        KidsSettingsScreen(SettingsPreviewData.kids(), onEvent = {}, onBack = {})
    }

    @Test
    fun `add a kid sheet`() = screenshot.captureLightDark("settings_kids_add") {
        KidEditorForm(SettingsPreviewData.kidEditor(isNew = true), onEvent = {}, modifier = Modifier.padding(24.dp))
    }

    @Test
    fun `edit a kid sheet, with linking`() = screenshot.captureLightDark("settings_kids_edit") {
        KidEditorForm(
            SettingsPreviewData.kidEditor(),
            onEvent = {},
            modifier = Modifier.padding(24.dp),
            onLinkDevice = {},
        )
    }

    @Test
    fun `archive a kid dialog`() = screenshot.captureScreenLightDark(
        "settings_kids_archive",
        open = { onNodeWithText("Archive kid").performClick() },
    ) {
        KidEditorForm(SettingsPreviewData.kidEditor(), onEvent = {}, modifier = Modifier.padding(24.dp))
    }

    @Test
    fun `allowance with the daily game limit`() = screenshot.captureLightDark("settings_allowance") {
        AllowanceSettingsScreen(SettingsPreviewData.allowance(), onEvent = {}, onBack = {})
    }

    @Test
    fun `allowance sheet`() = screenshot.captureLightDark("settings_allowance_sheet") {
        AllowanceEditorForm(SettingsPreviewData.allowanceEditor(), onEvent = {}, modifier = Modifier.padding(24.dp))
    }

    @Test
    fun `allowance start date picker`() = screenshot.captureScreenLightDark(
        "settings_allowance_date",
        open = { onNodeWithText("Change").performClick() },
    ) {
        AllowanceEditorForm(SettingsPreviewData.allowanceEditor(), onEvent = {}, modifier = Modifier.padding(24.dp))
    }

    @Test
    fun `daily game limit dialog`() = screenshot.captureLightDark("settings_game_cap") {
        GameCapDialogCard(
            GameCapEditorUi("1.20", SettingsPreviewData.gbp),
            onEvent = {},
            modifier = Modifier.padding(24.dp),
        )
    }

    @Test
    fun `savings goals`() = screenshot.captureLightDark("settings_goals") {
        SavingsGoalsScreen(SettingsPreviewData.goals(), onEvent = {}, onBack = {})
    }

    @Test
    fun `savings goal sheet`() = screenshot.captureLightDark("settings_goals_sheet") {
        GoalEditorForm(SettingsPreviewData.goalEditor(), onEvent = {}, modifier = Modifier.padding(24.dp))
    }

    @Test
    fun about() = screenshot.captureLightDark("settings_about") {
        AboutScreen(SettingsPreviewData.about, onEvent = {}, onOpenLicences = {}, onBack = {})
    }

    @Test
    fun `open-source licences`() = screenshot.captureLightDark("settings_about_licences") {
        OpenSourceLicencesScreen(SettingsPreviewData.about, onEvent = {}, onBack = {})
    }

    @Test
    fun `on-device AI, not available on this phone`() = screenshot.captureLightDark("settings_ai_unavailable") {
        AiSettingsScreen(SettingsPreviewData.ai(AiModelStatus.UNAVAILABLE), onEvent = {}, onBack = {})
    }

    @Test
    fun `on-device AI, model downloadable`() = screenshot.captureLightDark("settings_ai") {
        AiSettingsScreen(SettingsPreviewData.ai(), onEvent = {}, onBack = {})
    }

    @Test
    fun interest() = screenshot.captureLightDark("settings_interest") {
        InterestSettingsScreen(SettingsPreviewData.interest(), onEvent = {}, onBack = {})
    }

    @Test
    fun `interest, rate too high`() = screenshot.captureLightDark("settings_interest_too_high") {
        InterestSettingsScreen(SettingsPreviewData.interest("21"), onEvent = {}, onBack = {})
    }

    @Test
    @Config(qualifiers = "+h1100dp") // The PIN form sits below the signing card.
    fun `parent security, signing on`() = screenshot.captureLightDark("settings_security_signing_on") {
        ParentSecurityScreen(SettingsPreviewData.security(), onEvent = {}, onBack = {})
    }

    @Test
    @Config(qualifiers = "+h1100dp")
    fun `parent security, no strong biometric (D-M2-1)`() =
        screenshot.captureLightDark("settings_security_unavailable") {
            ParentSecurityScreen(
                SettingsPreviewData.security(
                    availability = ParentAuthAvailability.NOT_ENROLLED,
                    hasParentKey = false,
                    pinError = PinChangeError.Mismatch,
                ),
                onEvent = {},
                onBack = {},
            )
        }

    @Test
    @Config(qualifiers = "+h1100dp")
    fun `parent security, signing key not registered with the household (D-M3-39)`() =
        screenshot.captureLightDark("settings_security_key_not_shared") {
            ParentSecurityScreen(
                SettingsPreviewData.security(keyNotRegisteredWithHousehold = true),
                onEvent = {},
                onBack = {},
            )
        }

    @Test
    @Config(qualifiers = "+h1100dp")
    fun `parent security, fingerprint changed`() = screenshot.captureLightDark("settings_security_key_changed") {
        ParentSecurityScreen(
            SettingsPreviewData.security(
                keyNeedsRenewal = true,
                signingPin = SigningPinUi(SigningPinPurpose.RENEW, enteredDigits = 2),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

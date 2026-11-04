package dev.kpetriv.sprout.feature.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotTest
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Goldens for the household sections (M3 §6, M7 §2–§3), in src/test/screenshots; see docs/SCREENSHOT_TESTS.md. Every
 * variant is also checked with the accessibility rule (D-M7-5).
 */
class HouseholdScreenshotTest : ScreenshotTest(checkAccessibility = true) {
    private val rows = listOf(
        DeviceRowUi(
            SECOND_PHONE,
            "3fa9 c01d 77e2 b4a8",
            Role.PARENT,
            null,
            AttestationLabel.UNVERIFIED,
            RegistrationStatus.PENDING,
            false,
        ),
        DeviceRowUi(
            THIS_PHONE,
            "aaaa 1111 bbbb 2222",
            Role.PARENT,
            null,
            AttestationLabel.STRONGBOX,
            RegistrationStatus.ACTIVE,
            true,
        ),
        DeviceRowUi(
            LEO_PHONE,
            "c0ff ee00 d00d feed",
            Role.KID,
            "Leo",
            AttestationLabel.TEE,
            RegistrationStatus.ACTIVE,
            false,
        ),
        DeviceRowUi(
            OLD_PHONE,
            "dead 0000 beef 1111",
            Role.KID,
            "Mia",
            AttestationLabel.TEE,
            RegistrationStatus.REVOKED,
            false,
        ),
    )

    @Test
    @Config(qualifiers = "+h1100dp")
    fun `devices, with a pending and a revoked phone`() = screenshot.captureLightDark("settings_devices") {
        DevicesScreen(DevicesUiState(isLoading = false, devices = rows), onEvent = {}, onBack = {})
    }

    @Test
    fun `approve sheet`() = screenshot.captureLightDark("settings_devices_approve") {
        ApproveDeviceContent(rows.first(), busy = false, onEvent = {}, modifier = Modifier.padding(24.dp))
    }

    @Test
    fun `delete household dialog`() = screenshot.captureLightDark("settings_data_delete") {
        DeleteHouseholdContent(
            DataUiState(householdId = HouseholdId("hh7f3k2q9xabc"), deleteTyped = "hh7f"),
            onEvent = {},
            modifier = Modifier.padding(24.dp),
        )
    }

    @Test
    @Config(qualifiers = "+h1100dp")
    fun `household data, keeping chat for 90 days`() = screenshot.captureLightDark("settings_data") {
        DataScreen(SettingsPreviewData.data(), onEvent = {}, onBack = {})
    }

    @Test
    @Config(qualifiers = "+h1100dp")
    fun `export in progress`() = screenshot.captureLightDark("settings_data_exporting") {
        DataScreen(
            SettingsPreviewData.data().copy(exportProgress = ExportProgress(ExportStage.MEDIA, done = 3, total = 8)),
            onEvent = {},
            onBack = {},
        )
    }

    @Test
    fun `shorter chat retention confirmation`() = screenshot.captureScreenLightDark("settings_data_retention") {
        DataScreen(
            SettingsPreviewData.data(retentionDays = null).let {
                it.copy(retention = it.retention?.copy(pending = RetentionChoice(30)))
            },
            onEvent = {},
            onBack = {},
        )
    }

    @Test
    fun `delete chat history dialog`() = screenshot.captureLightDark("settings_data_delete_chat") {
        DeleteChatCard(
            SettingsPreviewData.data().copy(chatDeleteTyped = "DEL"),
            onEvent = {},
            modifier = Modifier.padding(24.dp),
        )
    }

    @Test
    fun `sign in again to confirm, wrong account`() = screenshot.captureLightDark("settings_data_reauth") {
        ReauthCard(
            ReauthUi(usesPassword = true, problem = ReauthProblem.WRONG_ACCOUNT),
            onEvent = {},
            modifier = Modifier.padding(24.dp),
        )
    }

    @Test
    fun `remove a phone dialog`() = screenshot.captureScreenLightDark("settings_devices_revoke") {
        DevicesScreen(
            DevicesUiState(isLoading = false, devices = rows, revoking = rows[2]),
            onEvent = {},
            onBack = {},
        )
    }

    @Test
    fun `sign out dialog`() = screenshot.captureScreenLightDark("settings_account_sign_out") {
        AccountScreen(
            AccountUiState(
                email = "parent@example.com",
                householdId = HouseholdId("hh7f3k2q9xabc"),
                confirmingSignOut = true,
            ),
            onEvent = {},
            onBack = {},
        )
    }

    @Test
    fun `link a device, creating the code`() = screenshot.captureLightDark("settings_link_loading") {
        LinkDeviceScreen(LinkDeviceUiState(kidName = "Leo", loading = true), onEvent = {}, onBack = {})
    }

    @Test
    fun `link a device, code expired`() = screenshot.captureLightDark("settings_link_expired") {
        LinkDeviceScreen(
            LinkDeviceUiState(kidName = "Leo", code = "K7QM2X9P", remainingSeconds = 0),
            onEvent = {},
            onBack = {},
        )
    }

    @Test
    fun `link a device, too many codes`() = screenshot.captureLightDark("settings_link_rate_limited") {
        LinkDeviceScreen(
            LinkDeviceUiState(kidName = "Leo", failure = AdminFailure.RATE_LIMITED),
            onEvent = {},
            onBack = {},
        )
    }

    @Test
    fun `link a device`() = screenshot.captureLightDark("settings_link_code") {
        LinkDeviceScreen(
            LinkDeviceUiState(kidName = "Leo", code = "K7QM2X9P", remainingSeconds = 581),
            onEvent = {},
            onBack = {},
        )
    }

    @Test
    fun account() = screenshot.captureLightDark("settings_account") {
        AccountScreen(
            AccountUiState(email = "parent@example.com", householdId = HouseholdId("hh7f3k2q9xabc")),
            onEvent = {},
            onBack = {},
        )
    }
}

package dev.kpetriv.sprout.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons

/** The settings sections, in hub order. */
enum class SettingsSection {
    KIDS,
    ALLOWANCE,
    SAVINGS_GOALS,
    INTEREST,
    PARENT_SECURITY,
    DEVICES,
    ACCOUNT,
    DATA,

    /** On-device AI (M6): status, the enable switch and the model download consent. */
    AI,
    ABOUT,
    ;

    /** Only a synced household has these; the trial household does not (it has no account, M3 §6). */
    val needsHousehold: Boolean get() = this == DEVICES || this == ACCOUNT || this == DATA
}

internal fun SettingsSection.titleRes(): Int = when (this) {
    SettingsSection.KIDS -> R.string.feature_settings_section_kids
    SettingsSection.ALLOWANCE -> R.string.feature_settings_section_allowance
    SettingsSection.SAVINGS_GOALS -> R.string.feature_settings_section_goals
    SettingsSection.INTEREST -> R.string.feature_settings_section_interest
    SettingsSection.PARENT_SECURITY -> R.string.feature_settings_section_security
    SettingsSection.DEVICES -> R.string.feature_settings_section_devices
    SettingsSection.ACCOUNT -> R.string.feature_settings_section_account
    SettingsSection.DATA -> R.string.feature_settings_section_data
    SettingsSection.AI -> R.string.feature_settings_section_ai
    SettingsSection.ABOUT -> R.string.feature_settings_section_about
}

internal fun SettingsSection.summaryRes(): Int = when (this) {
    SettingsSection.KIDS -> R.string.feature_settings_section_kids_summary
    SettingsSection.ALLOWANCE -> R.string.feature_settings_section_allowance_summary
    SettingsSection.SAVINGS_GOALS -> R.string.feature_settings_section_goals_summary
    SettingsSection.INTEREST -> R.string.feature_settings_section_interest_summary
    SettingsSection.PARENT_SECURITY -> R.string.feature_settings_section_security_summary
    SettingsSection.DEVICES -> R.string.feature_settings_section_devices_summary
    SettingsSection.ACCOUNT -> R.string.feature_settings_section_account_summary
    SettingsSection.DATA -> R.string.feature_settings_section_data_summary
    SettingsSection.AI -> R.string.feature_settings_section_ai_summary
    SettingsSection.ABOUT -> R.string.feature_settings_section_about_summary
}

/** The section's icon on the hub (Material Symbols Outlined, M9 §2.7). */
@Composable
internal fun SettingsSection.icon(): ImageVector = when (this) {
    SettingsSection.KIDS -> ImageVector.vectorResource(R.drawable.feature_settings_ic_group)
    SettingsSection.ALLOWANCE -> SproutIcons.Payments
    SettingsSection.SAVINGS_GOALS -> SproutIcons.Savings
    SettingsSection.INTEREST -> ImageVector.vectorResource(R.drawable.feature_settings_ic_percent)
    SettingsSection.PARENT_SECURITY -> SproutIcons.Lock
    SettingsSection.DEVICES -> ImageVector.vectorResource(R.drawable.feature_settings_ic_devices)
    SettingsSection.ACCOUNT -> ImageVector.vectorResource(R.drawable.feature_settings_ic_account_circle)
    SettingsSection.DATA -> ImageVector.vectorResource(R.drawable.feature_settings_ic_database)
    SettingsSection.AI -> SproutIcons.AutoAwesome
    SettingsSection.ABOUT -> ImageVector.vectorResource(R.drawable.feature_settings_ic_info)
}

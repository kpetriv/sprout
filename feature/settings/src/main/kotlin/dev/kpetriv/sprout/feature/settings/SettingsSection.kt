package dev.kpetriv.sprout.feature.settings

/** The settings sections, in hub order. */
enum class SettingsSection {
    KIDS,
    ALLOWANCE,
    SAVINGS_GOALS,
    INTEREST,
    PARENT_SECURITY,
    ABOUT,
}

internal fun SettingsSection.titleRes(): Int = when (this) {
    SettingsSection.KIDS -> R.string.feature_settings_section_kids
    SettingsSection.ALLOWANCE -> R.string.feature_settings_section_allowance
    SettingsSection.SAVINGS_GOALS -> R.string.feature_settings_section_goals
    SettingsSection.INTEREST -> R.string.feature_settings_section_interest
    SettingsSection.PARENT_SECURITY -> R.string.feature_settings_section_security
    SettingsSection.ABOUT -> R.string.feature_settings_section_about
}

internal fun SettingsSection.summaryRes(): Int = when (this) {
    SettingsSection.KIDS -> R.string.feature_settings_section_kids_summary
    SettingsSection.ALLOWANCE -> R.string.feature_settings_section_allowance_summary
    SettingsSection.SAVINGS_GOALS -> R.string.feature_settings_section_goals_summary
    SettingsSection.INTEREST -> R.string.feature_settings_section_interest_summary
    SettingsSection.PARENT_SECURITY -> R.string.feature_settings_section_security_summary
    SettingsSection.ABOUT -> R.string.feature_settings_section_about_summary
}

package dev.kpetriv.sprout.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * The settings feature: the hub and its sections, navigated inside this route (system back returns to the hub).
 * Must sit behind the parent lock. The app binds [PinChanger], [ParentKeyRegistrar] and [LockPreferences].
 *
 * @param appVersion shown in About (the app's `versionName`).
 * @param onBack leaves settings from the hub.
 */
@Composable
fun SettingsRoute(onBack: () -> Unit, modifier: Modifier = Modifier, appVersion: String = "") {
    var section by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    val closeSection = { section = null }
    SystemBackHandler(enabled = section != null, onBack = closeSection)
    when (section) {
        null -> SettingsScreen(onOpen = { section = it }, onBack = onBack, modifier = modifier)
        SettingsSection.KIDS -> KidsSettingsRoute(onBack = closeSection, modifier = modifier)
        SettingsSection.ALLOWANCE -> AllowanceSettingsRoute(onBack = closeSection, modifier = modifier)
        SettingsSection.SAVINGS_GOALS -> SavingsGoalsRoute(onBack = closeSection, modifier = modifier)
        SettingsSection.INTEREST -> InterestSettingsRoute(onBack = closeSection, modifier = modifier)
        SettingsSection.PARENT_SECURITY -> ParentSecurityRoute(onBack = closeSection, modifier = modifier)
        SettingsSection.ABOUT -> AboutScreen(appVersion = appVersion, onBack = closeSection, modifier = modifier)
    }
}

/** The settings hub: one row per [SettingsSection]. */
@Composable
fun SettingsScreen(onOpen: (SettingsSection) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    SettingsScaffold(title = stringResource(R.string.feature_settings_title), onBack = onBack, modifier = modifier) {
        Column(modifier = Modifier.padding(it).fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsSection.entries.forEachIndexed { index, section ->
                if (index > 0) HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(section.titleRes())) },
                    supportingContent = { Text(stringResource(section.summaryRes())) },
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(section) },
                )
            }
        }
    }
}

/** Version and privacy notes. */
@Composable
fun AboutScreen(appVersion: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    SettingsScaffold(
        title = stringResource(R.string.feature_settings_section_about),
        onBack = onBack,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(it).sectionPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.feature_settings_about_app), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.feature_settings_about_tagline), style = MaterialTheme.typography.bodyLarge)
            if (appVersion.isNotBlank()) {
                Text(
                    stringResource(R.string.feature_settings_about_version, appVersion),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(stringResource(R.string.feature_settings_about_privacy), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

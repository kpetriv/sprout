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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId

/**
 * The settings feature: the hub and its sections, navigated inside this route (system back returns to the hub).
 * Must sit behind the parent lock. The app binds [PinChanger], [ParentKeyRegistrar], [LockPreferences] and the
 * household ports ([DeviceAdmin], [KidLinking], [AccountControl], [HouseholdData], [ExportWriter]).
 *
 * @param appVersion shown in About (the app's `versionName`).
 * @param onBack leaves settings from the hub (or from [initialSection] when settings opened straight on it).
 * @param initialSection open this section directly (the dashboard's "New device waiting" opens Devices).
 * @param reviewDeviceId with [SettingsSection.DEVICES]: open this PENDING device's approval sheet.
 */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    appVersion: String = "",
    initialSection: SettingsSection? = null,
    reviewDeviceId: DeviceId? = null,
) {
    val hub: SettingsHubViewModel = hiltViewModel()
    val synced by hub.synced.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(initialSection) }
    var linkKidId by rememberSaveable { mutableStateOf<String?>(null) }
    var linkKidName by rememberSaveable { mutableStateOf("") }
    val closeSection = { if (initialSection != null) onBack() else section = null }
    val closeLink = { linkKidId = null }
    SystemBackHandler(enabled = section != null && linkKidId == null, onBack = closeSection)
    SystemBackHandler(enabled = linkKidId != null, onBack = closeLink)
    val linking = linkKidId
    if (linking != null) {
        LinkDeviceRoute(kidId = KidId(linking), kidName = linkKidName, onBack = closeLink, modifier = modifier)
        return
    }
    val current = section
    if (current == null) {
        SettingsScreen(
            onOpen = { section = it },
            onBack = onBack,
            modifier = modifier,
            sections = SettingsSection.entries.filter { synced || !it.needsHousehold },
        )
        return
    }
    val onLinkDevice: ((KidId, String) -> Unit)? = if (synced) {
        { kidId, name ->
            linkKidName = name
            linkKidId = kidId.value
        }
    } else {
        null
    }
    SectionRoute(current, closeSection, appVersion, reviewDeviceId, onLinkDevice, modifier)
}

@Composable
@Suppress("LongParameterList") // What the sections need from the route.
private fun SectionRoute(
    section: SettingsSection,
    onBack: () -> Unit,
    appVersion: String,
    reviewDeviceId: DeviceId?,
    onLinkDevice: ((KidId, String) -> Unit)?,
    modifier: Modifier,
) {
    when (section) {
        SettingsSection.KIDS -> KidsSettingsRoute(onBack = onBack, modifier = modifier, onLinkDevice = onLinkDevice)
        SettingsSection.ALLOWANCE -> AllowanceSettingsRoute(onBack = onBack, modifier = modifier)
        SettingsSection.SAVINGS_GOALS -> SavingsGoalsRoute(onBack = onBack, modifier = modifier)
        SettingsSection.INTEREST -> InterestSettingsRoute(onBack = onBack, modifier = modifier)
        SettingsSection.PARENT_SECURITY -> ParentSecurityRoute(onBack = onBack, modifier = modifier)
        SettingsSection.DEVICES -> DevicesRoute(onBack = onBack, reviewDeviceId = reviewDeviceId, modifier = modifier)
        SettingsSection.ACCOUNT -> AccountRoute(onBack = onBack, modifier = modifier)
        SettingsSection.DATA -> DataRoute(onBack = onBack, modifier = modifier)
        SettingsSection.ABOUT -> AboutScreen(appVersion = appVersion, onBack = onBack, modifier = modifier)
    }
}

/** The settings hub: one row per [SettingsSection] in [sections]. */
@Composable
fun SettingsScreen(
    onOpen: (SettingsSection) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    sections: List<SettingsSection> = SettingsSection.entries,
) {
    SettingsScaffold(title = stringResource(R.string.feature_settings_title), onBack = onBack, modifier = modifier) {
        Column(modifier = Modifier.padding(it).fillMaxSize().verticalScroll(rememberScrollState())) {
            sections.forEachIndexed { index, section ->
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

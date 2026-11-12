package dev.kpetriv.sprout.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.ui.ProvideWindowWidthClass
import dev.kpetriv.sprout.core.ui.WindowWidthClass
import dev.kpetriv.sprout.core.ui.rememberWindowWidthClass

/**
 * The settings feature: the hub and its sections, navigated inside this route (system back returns to the hub).
 * Must sit behind the parent lock. The app binds [PinChanger], [ParentKeyRegistrar], [LockPreferences],
 * [AiSettingsSource] and the household ports ([DeviceAdmin], [KidLinking], [AccountControl], [HouseholdData],
 * [ExportWriter]) and About's ([AppBuildInfo], [UrlOpener], [OpenSourceLicences]).
 *
 * @param onBack leaves settings from the hub (or from [initialSection] when settings opened straight on it); null on
 *   the Settings tab root (M9 §5.2), whose hub shows no back arrow. Sections opened from the hub keep their arrow
 *   back to it either way.
 * @param initialSection open this section directly (the dashboard's "New device waiting" opens Devices, the summary
 *   card's "Download the on-device model" opens [SettingsSection.AI]).
 * @param reviewDeviceId with [SettingsSection.DEVICES]: open this PENDING device's approval sheet.
 *
 * On an Expanded window (D-M7-2) the hub stays on the left and the open section (or the link screen) fills the right
 * pane ([SettingsListDetail]); back closes the section as on a phone.
 */
@Composable
fun SettingsRoute(
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    initialSection: SettingsSection? = null,
    reviewDeviceId: DeviceId? = null,
) {
    val hub: SettingsHubViewModel = hiltViewModel()
    val synced by hub.synced.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(initialSection) }
    var linkKidId by rememberSaveable { mutableStateOf<String?>(null) }
    var linkKidName by rememberSaveable { mutableStateOf("") }
    val closeSection = { if (initialSection != null && onBack != null) onBack() else section = null }
    val closeLink = { linkKidId = null }
    SystemBackHandler(enabled = section != null && linkKidId == null, onBack = closeSection)
    SystemBackHandler(enabled = linkKidId != null, onBack = closeLink)
    val onLinkDevice: ((KidId, String) -> Unit)? = if (synced) {
        { kidId, name ->
            linkKidName = name
            linkKidId = kidId.value
        }
    } else {
        null
    }
    val sections = SettingsSection.entries.filter { synced || !it.needsHousehold }
    val current = section
    val linking = linkKidId
    // The open section, or the link screen above it; null shows the hub (one pane) or a hint (two panes).
    val detail: (@Composable (Modifier) -> Unit)? = when {
        linking != null -> { paneModifier ->
            // The link screen opens from a section: its back arrow returns there, beside the hub too.
            CompositionLocalProvider(LocalBesideHub provides false) {
                LinkDeviceRoute(
                    kidId = KidId(linking),
                    kidName = linkKidName,
                    onBack = closeLink,
                    modifier = paneModifier,
                )
            }
        }

        current != null -> { paneModifier ->
            SectionRoute(current, closeSection, reviewDeviceId, onLinkDevice, paneModifier)
        }

        else -> null
    }
    if (rememberWindowWidthClass() == WindowWidthClass.Expanded) {
        SettingsListDetail(
            sections = sections,
            selected = current,
            onOpen = {
                linkKidId = null
                section = it
            },
            onBack = onBack,
            modifier = modifier,
            detail = detail,
        )
    } else if (detail != null) {
        detail(modifier)
    } else {
        SettingsScreen(onOpen = { section = it }, onBack = onBack, modifier = modifier, sections = sections)
    }
}

/**
 * The hub beside a section, for an Expanded window (D-M7-2): the hub ([SettingsScreen], [selected] highlighted) in a
 * fixed-width list pane, [detail] (or a "Choose a section" hint when null) in the rest. The detail pane gets its own
 * width class so a section lays out for the pane, not the window, and no back arrow of its own ([LocalBesideHub]): the
 * hub beside it is the way out, and system back still closes the section.
 */
@Composable
fun SettingsListDetail(
    sections: List<SettingsSection>,
    selected: SettingsSection?,
    onOpen: (SettingsSection) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    detail: (@Composable (Modifier) -> Unit)?,
) {
    Row(modifier = modifier.fillMaxSize()) {
        SettingsScreen(
            onOpen = onOpen,
            onBack = onBack,
            modifier = Modifier.width(ListPaneWidth).fillMaxHeight(),
            sections = sections,
            selected = selected,
            listPane = true,
        )
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxHeight()) {
            ProvideWindowWidthClass(WindowWidthClass.fromWidth(maxWidth)) {
                if (detail != null) {
                    CompositionLocalProvider(LocalBesideHub provides true) { detail(Modifier.fillMaxSize()) }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.feature_settings_pick_section),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private val ListPaneWidth = 360.dp

@Composable
private fun SectionRoute(
    section: SettingsSection,
    onBack: () -> Unit,
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
        SettingsSection.AI -> AiSettingsRoute(onBack = onBack, modifier = modifier)
        SettingsSection.ABOUT -> AboutRoute(onBack = onBack, modifier = modifier)
    }
}

/**
 * The settings hub on parent tokens (M9): one white card row per [SettingsSection] in [sections], each with its icon
 * in a Leaf-tint circle, its summary and a chevron. [onBack] null (the Settings tab root, M9 §5.2) shows no back
 * arrow. As the [listPane] beside a detail pane, every row is selectable (announced selected or not) and [selected]
 * sits on Leaf tint, "Choose a section" included.
 */
@Composable
fun SettingsScreen(
    onOpen: (SettingsSection) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    sections: List<SettingsSection> = SettingsSection.entries,
    selected: SettingsSection? = null,
    listPane: Boolean = selected != null,
) {
    SettingsScaffold(title = stringResource(R.string.feature_settings_title), onBack = onBack, modifier = modifier) {
        SettingsColumn(padding = it, spacing = SproutSpacing.rowGap) {
            sections.forEach { section ->
                SettingsRow(
                    title = stringResource(section.titleRes()),
                    summary = stringResource(section.summaryRes()),
                    icon = section.icon(),
                    onClick = { onOpen(section) },
                    // Selection is announced only beside a detail pane, where a row is the open section or none is.
                    selected = if (listPane) section == selected else null,
                )
            }
        }
    }
}

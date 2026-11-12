package dev.kpetriv.sprout.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.ui.ContentWidth
import dev.kpetriv.sprout.core.ui.contentWidth

/**
 * Settings → About, with its open-source licences page (M8 §1): the policy rows open the hosted pages in the browser,
 * the licences page lists the generated asset (D-M8-9). Back from the licences page returns to About.
 */
@Composable
fun AboutRoute(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: AboutViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showingLicences by rememberSaveable { mutableStateOf(false) }
    val closeLicences = { showingLicences = false }
    SystemBackHandler(enabled = showingLicences, onBack = closeLicences)
    // Also after the page is restored (process death): the list is read only when the page shows.
    LaunchedEffect(showingLicences) {
        if (showingLicences) viewModel.onEvent(AboutEvent.LoadLicences)
    }
    if (showingLicences) {
        // A page of its own inside About: it keeps its back arrow beside the hub too (like the link screen).
        CompositionLocalProvider(LocalBesideHub provides false) {
            OpenSourceLicencesScreen(state, viewModel::onEvent, onBack = closeLicences, modifier = modifier)
        }
    } else {
        AboutScreen(
            state = state,
            onEvent = viewModel::onEvent,
            onOpenLicences = { showingLicences = true },
            onBack = onBack,
            modifier = modifier,
        )
    }
}

/** The app, a short privacy note, the policy and licence rows, and the version with its build number. */
@Composable
fun AboutScreen(
    state: AboutUiState,
    onEvent: (AboutEvent) -> Unit,
    onOpenLicences: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbarHostState) { onEvent(AboutEvent.MessageShown) }
    SettingsScaffold(
        title = stringResource(R.string.feature_settings_section_about),
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    ) { padding ->
        SettingsColumn(padding = padding, spacing = SproutSpacing.rowGap) {
            SettingsCard(modifier = Modifier.padding(bottom = SproutSpacing.sm)) {
                Text(
                    stringResource(R.string.feature_settings_about_app),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(R.string.feature_settings_about_tagline),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.feature_settings_about_privacy),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.hasPrivacyPolicy) {
                SettingsRow(
                    title = stringResource(R.string.feature_settings_about_privacy_policy),
                    summary = stringResource(R.string.feature_settings_about_privacy_policy_summary),
                    icon = SproutIcons.Lock,
                    onClick = { onEvent(AboutEvent.OpenPrivacyPolicy) },
                )
            }
            if (state.hasSecurityPolicy) {
                SettingsRow(
                    title = stringResource(R.string.feature_settings_about_security),
                    summary = stringResource(R.string.feature_settings_about_security_summary),
                    icon = ImageVector.vectorResource(R.drawable.feature_settings_ic_warning),
                    onClick = { onEvent(AboutEvent.OpenSecurityPolicy) },
                )
            }
            SettingsRow(
                title = stringResource(R.string.feature_settings_about_licences),
                summary = stringResource(R.string.feature_settings_about_licences_summary),
                icon = ImageVector.vectorResource(R.drawable.feature_settings_ic_info),
                onClick = onOpenLicences,
            )
            SettingsRow(
                title = stringResource(R.string.feature_settings_about_version),
                summary = stringResource(
                    R.string.feature_settings_about_version_value,
                    state.versionName,
                    state.versionCode,
                ),
                icon = SproutIcons.Eco,
            )
        }
    }
}

/** Every library this build ships with its licences; a tap opens the licence text (or the project's page). */
@Composable
fun OpenSourceLicencesScreen(
    state: AboutUiState,
    onEvent: (AboutEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbarHostState) { onEvent(AboutEvent.MessageShown) }
    SettingsScaffold(
        title = stringResource(R.string.feature_settings_about_licences),
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    ) { padding ->
        val libraries = state.libraries
        if (libraries == null) {
            if (state.licencesFailed) {
                Text(
                    stringResource(R.string.feature_settings_licences_failed),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(padding).contentWidth().sectionPadding(),
                )
            } else {
                loadStatus(isLoading = true, loadFailed = false, modifier = Modifier.padding(padding))
            }
            return@SettingsScaffold
        }
        ContentWidth(modifier = Modifier.padding(padding).fillMaxSize()) { gutters ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = gutters,
                verticalArrangement = Arrangement.spacedBy(SproutSpacing.rowGap),
            ) {
                item { Spacer(Modifier.height(SproutSpacing.sm)) }
                items(libraries, key = { it.module + ":" + it.version }) { library ->
                    val licences = library.licences.joinToString { it.name }
                        .ifEmpty { stringResource(R.string.feature_settings_licences_no_licence) }
                    SettingsRow(
                        title = library.name,
                        summary = "${library.module} ${library.version}\n$licences",
                        onClick = library.link?.let { { onEvent(AboutEvent.OpenLibrary(library)) } },
                        modifier = Modifier.padding(horizontal = SproutSpacing.gutter),
                    )
                }
                item { Spacer(Modifier.height(SproutSpacing.sm)) }
            }
        }
    }
}

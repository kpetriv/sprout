package dev.kpetriv.sprout.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
    ) {
        Column(modifier = Modifier.padding(it).fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(modifier = Modifier.sectionPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.feature_settings_about_app),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.feature_settings_about_tagline),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.feature_settings_about_privacy),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.hasPrivacyPolicy) {
                AboutRow(
                    title = stringResource(R.string.feature_settings_about_privacy_policy),
                    summary = stringResource(R.string.feature_settings_about_privacy_policy_summary),
                    onClick = { onEvent(AboutEvent.OpenPrivacyPolicy) },
                )
                HorizontalDivider()
            }
            if (state.hasSecurityPolicy) {
                AboutRow(
                    title = stringResource(R.string.feature_settings_about_security),
                    summary = stringResource(R.string.feature_settings_about_security_summary),
                    onClick = { onEvent(AboutEvent.OpenSecurityPolicy) },
                )
                HorizontalDivider()
            }
            AboutRow(
                title = stringResource(R.string.feature_settings_about_licences),
                summary = stringResource(R.string.feature_settings_about_licences_summary),
                onClick = onOpenLicences,
            )
            HorizontalDivider()
            AboutRow(
                title = stringResource(R.string.feature_settings_about_version),
                summary = stringResource(
                    R.string.feature_settings_about_version_value,
                    state.versionName,
                    state.versionCode,
                ),
                onClick = null,
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
                    modifier = Modifier.padding(padding).sectionPadding(),
                )
            } else {
                loadStatus(isLoading = true, loadFailed = false, modifier = Modifier.padding(padding))
            }
            return@SettingsScaffold
        }
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            items(libraries, key = { it.module + ":" + it.version }) { library ->
                val licences = library.licences.joinToString { it.name }
                    .ifEmpty { stringResource(R.string.feature_settings_licences_no_licence) }
                AboutRow(
                    title = library.name,
                    summary = "${library.module} ${library.version}\n$licences",
                    onClick = library.link?.let { { onEvent(AboutEvent.OpenLibrary(library)) } },
                )
                HorizontalDivider()
            }
        }
    }
}

/** A row of About; [onClick] null makes it plain text (the version). */
@Composable
private fun AboutRow(title: String, summary: String, onClick: (() -> Unit)?) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        modifier = Modifier.fillMaxWidth().then(
            if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier,
        ),
    )
}

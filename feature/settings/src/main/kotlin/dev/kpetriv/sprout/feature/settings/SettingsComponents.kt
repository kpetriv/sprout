package dev.kpetriv.sprout.feature.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.components.SproutTopBar
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.ui.contentWidth

/**
 * True inside the detail pane beside the settings hub (Expanded width, D-M7-26): pages there show no back arrow, since
 * the hub beside them is the way out (system back still closes the section).
 */
internal val LocalBesideHub = staticCompositionLocalOf { false }

/**
 * A settings page on parent tokens (M9): the cream [SproutTopBar] with a back arrow when [onBack] is given (none on
 * the Settings tab root, M9 §5.2, and none beside the hub, [LocalBesideHub]), the snackbar host, and [content] below.
 */
@Composable
internal fun SettingsScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = { SproutTopBar(title = title, onBack = onBack.takeUnless { LocalBesideHub.current }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // Each page caps itself at a reading width on tablets (M7 §5) inside its own scroll container, so the side
        // gutters scroll with it (M7 review L10): `verticalScroll(...).contentWidth()`, or `ContentWidth` gutters as
        // a lazy list's content padding. The full width on a phone.
        content = content,
    )
}

/**
 * The scrolling body of a settings page: capped at the reading width, 20 dp gutters, [SproutSpacing.md] between
 * cards and rows.
 */
@Composable
internal fun SettingsColumn(
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    spacing: Dp = SproutSpacing.md,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .padding(padding)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .contentWidth()
            .sectionPadding(),
        verticalArrangement = Arrangement.spacedBy(spacing),
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

/** Shows [message] once in [snackbarHostState], then acknowledges it. */
@Composable
internal fun MessageEffect(message: SettingsMessage?, snackbarHostState: SnackbarHostState, onShown: () -> Unit) {
    val text = message?.let { stringResource(it.textRes()) }
    val shown by rememberUpdatedState(onShown)
    LaunchedEffect(message, text) {
        if (text != null) {
            snackbarHostState.showSnackbar(text)
            shown()
        }
    }
}

private fun SettingsMessage.textRes(): Int = when (this) {
    SettingsMessage.SAVED -> R.string.feature_settings_message_saved
    SettingsMessage.KID_ADDED -> R.string.feature_settings_message_kid_added
    SettingsMessage.KID_ARCHIVED -> R.string.feature_settings_message_kid_archived
    SettingsMessage.GOAL_DELETED -> R.string.feature_settings_message_goal_deleted
    SettingsMessage.PIN_CHANGED -> R.string.feature_settings_message_pin_changed
    SettingsMessage.KEY_REGISTERED -> R.string.feature_settings_message_key_registered
    SettingsMessage.KEY_NOT_SHARED -> R.string.feature_settings_message_key_not_shared
    SettingsMessage.SAVE_FAILED -> R.string.feature_settings_message_save_failed
    SettingsMessage.NOT_CONFIRMED -> R.string.feature_settings_data_message_not_confirmed
    SettingsMessage.POLICY_NOT_SYNCED -> R.string.feature_settings_policy_not_synced
    SettingsMessage.SETTINGS_RESTORED -> R.string.feature_settings_message_settings_restored
    SettingsMessage.LINK_NOT_OPENED -> R.string.feature_settings_message_link_not_opened
}

/** Loading spinner or load error, shared by the sections. Returns true when it drew something. */
@Composable
internal fun loadStatus(isLoading: Boolean, loadFailed: Boolean, modifier: Modifier = Modifier): Boolean {
    if (!isLoading && !loadFailed) return false
    Box(modifier = modifier.contentWidth().fillMaxSize(), contentAlignment = Alignment.Center) {
        if (loadFailed) {
            Text(stringResource(R.string.feature_settings_load_failed), style = MaterialTheme.typography.bodyLarge)
        } else {
            CircularProgressIndicator()
        }
    }
    return true
}

/** Standard padding for section content: the 20 dp gutter, 16 dp above and below. */
internal val SectionPadding = PaddingValues(horizontal = SproutSpacing.gutter, vertical = SproutSpacing.md)

/**
 * Calls [onBack] for the system back gesture while [enabled]. Uses the activity's back dispatcher directly so the
 * module needs no extra dependency; does nothing outside an [OnBackPressedDispatcherOwner].
 */
@Composable
internal fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val currentOnBack by rememberUpdatedState(onBack)
    val callback = remember {
        object : OnBackPressedCallback(enabled) {
            override fun handleOnBackPressed() = currentOnBack()
        }
    }
    callback.isEnabled = enabled
    DisposableEffect(context, callback) {
        val owner = context.findBackOwner()
        owner?.onBackPressedDispatcher?.addCallback(callback)
        onDispose { callback.remove() }
    }
}

private tailrec fun Context.findBackOwner(): OnBackPressedDispatcherOwner? = when (this) {
    is OnBackPressedDispatcherOwner -> this
    is ContextWrapper -> baseContext.findBackOwner()
    else -> null
}

/** Padding for a full-width column of section content. */
internal fun Modifier.sectionPadding(): Modifier = padding(SectionPadding)

/** Padding for a form inside a bottom sheet: the gutters, and room at the bottom. */
internal fun Modifier.sheetPadding(): Modifier =
    padding(start = SproutSpacing.gutter, end = SproutSpacing.gutter, bottom = SproutSpacing.lg)

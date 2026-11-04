package dev.kpetriv.sprout.feature.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.ContentWidth
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * True inside the detail pane beside the settings hub (Expanded width, D-M7-26): pages there show no back arrow, since
 * the hub beside them is the way out (system back still closes the section).
 */
internal val LocalBesideHub = staticCompositionLocalOf { false }

/** A settings page: top bar with back (none beside the hub, [LocalBesideHub]), snackbar host, and [content] below. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (!LocalBesideHub.current) {
                        IconButton(onClick = onBack) {
                            Icon(
                                painter = painterResource(R.drawable.feature_settings_ic_arrow_back),
                                contentDescription = stringResource(R.string.feature_settings_back),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // Capped at a reading width on tablets (M7 §5); the full width on a phone.
        content = { padding -> ContentWidth(modifier = Modifier.fillMaxSize()) { content(padding) } },
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
}

/** Loading spinner or load error, shared by the sections. Returns true when it drew something. */
@Composable
internal fun loadStatus(isLoading: Boolean, loadFailed: Boolean, modifier: Modifier = Modifier): Boolean {
    if (!isLoading && !loadFailed) return false
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (loadFailed) {
            Text(stringResource(R.string.feature_settings_load_failed), style = MaterialTheme.typography.bodyLarge)
        } else {
            CircularProgressIndicator()
        }
    }
    return true
}

/** A titled group of settings content. */
@Composable
internal fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.semantics { heading() },
    )
}

/** Kid filter chips for the per-kid sections. */
@Composable
internal fun KidChips(kids: List<Kid>, selected: KidId?, onSelect: (KidId) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        kids.forEach { kid ->
            FilterChip(
                selected = kid.id == selected,
                onClick = { onSelect(kid.id) },
                label = { Text(kid.firstName) },
            )
        }
    }
}

/** A labelled button that opens a menu of [options]; [optionLabel] names each. */
@Composable
internal fun <T> ChoiceDropdown(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            val value = optionLabel(selected)
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                // Read as "Birth month, March": the label above is a separate node.
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label, $value" },
            ) {
                Text(value)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

/** Primary save button that turns into a "Saving…" label while [isSaving]. */
@Composable
internal fun SaveButton(isSaving: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, enabled = enabled && !isSaving, modifier = modifier) {
        Text(
            stringResource(if (isSaving) R.string.feature_settings_saving else R.string.feature_settings_save),
        )
    }
}

/** Standard padding for section content. */
internal val SectionPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)

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

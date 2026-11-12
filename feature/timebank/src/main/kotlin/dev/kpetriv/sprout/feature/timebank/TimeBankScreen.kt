package dev.kpetriv.sprout.feature.timebank

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.theme.ProvideKidTier
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.HideNavigationWhile

/**
 * Navigation entry for [kidId]'s time bank, rendered for [tier]. On Android 13+ the first Start on this screen asks
 * for `POST_NOTIFICATIONS` (the app posts the ongoing "screen time running" notification) and starts whatever the
 * answer: the timer works without the notification.
 */
@Composable
fun TimeBankRoute(kidId: KidId, tier: KidUiTier, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val args = Bundle().apply {
        putString(TimeBankViewModel.KID_ID_KEY, kidId.value)
        putString(TimeBankViewModel.TIER_KEY, tier.name)
    }
    val viewModel = timeBankViewModel<TimeBankViewModel>(key = "timebank:${kidId.value}:${tier.name}", args = args)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var askedForNotifications by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onEvent(TimeBankEvent.Start)
    }
    TimeBankScreen(
        state = state,
        onEvent = { event ->
            val ask = event == TimeBankEvent.Start &&
                !askedForNotifications &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            if (ask) {
                askedForNotifications = true
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.onEvent(event)
            }
        },
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * The time bank for any [TimeBankUiState]. Ready content renders inside [ProvideKidTier] and switches to
 * [SeedlingTimeBankContent] (a big Start/Stop sun with a draining ring and whole minutes), [SproutTimeBankContent]
 * (timer, minutes left, today's sessions) or [SaplingTimeBankContent] (timer, history and the rule text).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeBankScreen(
    state: TimeBankUiState,
    onEvent: (TimeBankEvent) -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    // No tabbing away from a running timer (M9 §5.2, D-M9-10): the shell hides its bar until the kid stops it.
    HideNavigationWhile((state as? TimeBankUiState.Ready)?.running == true)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.feature_timebank_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.feature_timebank_ic_arrow_back),
                            contentDescription = stringResource(R.string.feature_timebank_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                TimeBankUiState.Loading -> Loading()

                TimeBankUiState.Error -> ErrorContent(onRetry = { onEvent(TimeBankEvent.Retry) })

                is TimeBankUiState.Ready -> ProvideKidTier(state.tier) {
                    when (state.tier) {
                        KidUiTier.SEEDLING -> SeedlingTimeBankContent(state, onEvent)
                        KidUiTier.SPROUT -> SproutTimeBankContent(state, onEvent)
                        KidUiTier.SAPLING -> SaplingTimeBankContent(state, onEvent)
                    }
                }
            }
        }
    }
    val ready = state as? TimeBankUiState.Ready
    val message = ready?.message
    val text = message?.let { messageText(it) }
    val shown = rememberUpdatedState { onEvent(TimeBankEvent.MessageShown) }
    LaunchedEffect(message, text) {
        if (text != null) {
            snackbar.showSnackbar(text)
            shown.value()
        }
    }
}

@Composable
private fun messageText(message: TimeBankMessage): String = when (message) {
    is TimeBankMessage.Stopped -> pluralStringResource(
        R.plurals.feature_timebank_stopped,
        message.minutes.toPluralCount(),
        message.minutes,
    )

    is TimeBankMessage.StartFailed -> stringResource(
        when (message.refusal) {
            TimeBankRefusal.ALREADY_RUNNING -> R.string.feature_timebank_start_running
            TimeBankRefusal.NO_BALANCE -> R.string.feature_timebank_start_no_balance
            TimeBankRefusal.NOT_RUNNING, null -> R.string.feature_timebank_start_failed
        },
    )

    TimeBankMessage.StopFailed -> stringResource(R.string.feature_timebank_stop_failed)
}

@Composable
private fun Loading() {
    val description = stringResource(R.string.feature_timebank_loading)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = description })
    }
}

@Composable
private fun ErrorContent(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.feature_timebank_error),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Button(onClick = onRetry) { Text(stringResource(R.string.feature_timebank_retry)) }
    }
}

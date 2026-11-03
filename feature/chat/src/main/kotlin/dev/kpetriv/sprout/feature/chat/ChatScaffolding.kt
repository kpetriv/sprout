package dev.kpetriv.sprout.feature.chat

import android.os.Bundle
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.rememberHiltViewModelFactory
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/** A Hilt ViewModel scoped to the current `ViewModelStoreOwner` (the nav entry) under [key], with [args]. */
@Composable
internal inline fun <reified VM : ViewModel> chatViewModel(key: String, args: Bundle): VM {
    val owner = checkNotNull(LocalViewModelStoreOwner.current) { "A chat route needs a ViewModelStoreOwner" }
    val defaults = (owner as? HasDefaultViewModelProviderFactory)?.defaultViewModelCreationExtras
        ?: CreationExtras.Empty
    val extras = MutableCreationExtras(defaults).apply { set(DEFAULT_ARGS_KEY, args) }
    return viewModel(viewModelStoreOwner = owner, key = key, factory = rememberHiltViewModelFactory(), extras = extras)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(text = title, modifier = Modifier.semantics { heading() }) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.feature_chat_ic_arrow_back),
                    contentDescription = stringResource(R.string.feature_chat_back),
                )
            }
        },
    )
}

@Composable
internal fun LoadingContent(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.feature_chat_loading)
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = description })
    }
}

@Composable
internal fun ErrorContent(text: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (onRetry != null) Button(onClick = onRetry) { Text(stringResource(R.string.feature_chat_retry)) }
    }
}

/** Shows [text] once in a snackbar, then reports it shown. */
@Composable
internal fun NoticeEffect(host: SnackbarHostState, key: Any?, text: String?, onShown: () -> Unit) {
    val shown = rememberUpdatedState(onShown)
    LaunchedEffect(key, text) {
        if (text != null) {
            host.showSnackbar(text)
            shown.value()
        }
    }
}

@Composable
internal fun noticeText(notice: ChatNotice): String = stringResource(
    when (notice) {
        ChatNotice.MIC_PERMISSION_DENIED -> R.string.feature_chat_notice_mic_permission
        ChatNotice.MIC_UNAVAILABLE -> R.string.feature_chat_notice_mic_unavailable
        ChatNotice.RECORDING_FAILED -> R.string.feature_chat_notice_recording_failed
        ChatNotice.VOICE_TOO_SHORT -> R.string.feature_chat_notice_voice_too_short
        ChatNotice.PLAYBACK_FAILED -> R.string.feature_chat_notice_playback_failed
        ChatNotice.PHOTO_FAILED -> R.string.feature_chat_notice_photo_failed
        ChatNotice.RETRY_FAILED -> R.string.feature_chat_notice_retry_failed
        ChatNotice.DISCARD_FAILED -> R.string.feature_chat_notice_discard_failed
    },
)

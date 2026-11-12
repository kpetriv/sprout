package dev.kpetriv.sprout.feature.chat

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.rememberHiltViewModelFactory
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutTopBar
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing

/** A Hilt ViewModel scoped to the current `ViewModelStoreOwner` (the nav entry) under [key], with [args]. */
@Composable
internal inline fun <reified VM : ViewModel> chatViewModel(key: String, args: Bundle): VM {
    val owner = checkNotNull(LocalViewModelStoreOwner.current) { "A chat route needs a ViewModelStoreOwner" }
    val defaults = (owner as? HasDefaultViewModelProviderFactory)?.defaultViewModelCreationExtras
        ?: CreationExtras.Empty
    val extras = MutableCreationExtras(defaults).apply { set(DEFAULT_ARGS_KEY, args) }
    return viewModel(viewModelStoreOwner = owner, key = key, factory = rememberHiltViewModelFactory(), extras = extras)
}

/**
 * The chat screens' top bar: a back arrow only when [onBack] is given. The parent inbox and the Sprout and Sapling
 * thread are tab roots (no arrow); a parent's thread and Seedling's thread are pushed (M9 §5.2).
 */
@Composable
internal fun ChatTopBar(title: String, onBack: (() -> Unit)?) {
    SproutTopBar(title = title, onBack = onBack)
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
        modifier = modifier.fillMaxSize().padding(SproutSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (onRetry != null) SproutButton(text = stringResource(R.string.feature_chat_retry), onClick = onRetry)
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

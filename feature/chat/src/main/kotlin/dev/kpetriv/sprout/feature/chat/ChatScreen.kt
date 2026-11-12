package dev.kpetriv.sprout.feature.chat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.theme.ProvideKidTier
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.ContentWidth
import dev.kpetriv.sprout.core.ui.ReadAloud

/**
 * Navigation entry for a parent's view of [threadId] (the kid's thread). Behind the parent gate; the app wraps it in
 * `SecureWindow` ([ChatUiState.secure]). "Add a photo" opens the system photo picker; [PickPhoto] reads the result.
 */
@Composable
fun ParentChatRoute(threadId: KidId, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val args = Bundle().apply {
        putString(ChatViewModel.THREAD_ID_KEY, threadId.value)
        putString(ChatViewModel.MODE_KEY, ChatMode.PARENT.name)
    }
    val viewModel = chatViewModel<ChatViewModel>(key = "chat:parent:${threadId.value}", args = args)
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onEvent(ChatEvent.ScreenStopped) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.onEvent(ChatEvent.PhotoPicked(uri.toString()))
    }
    ChatScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        onAttachPhoto = {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        modifier = modifier,
    )
}

/**
 * Navigation entry for [kidId]'s own thread in kid mode, rendered for [tier]. The app wraps it in `SecureWindow`
 * ([ChatUiState.secure]). Seedling's microphone asks for `RECORD_AUDIO` on the first press; a refusal shows a notice.
 *
 * @param onBack null where the thread is a tab root (Sprout and Sapling: no back arrow, M9 §5.2); Seedling's thread is
 *   pushed from the wallet and keeps the arrow.
 * @param readAloud speaks Seedling bubbles and tiles; with [ReadAloud.None] nothing is spoken.
 */
@Composable
fun KidChatRoute(
    kidId: KidId,
    tier: KidUiTier,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    readAloud: ReadAloud = ReadAloud.None,
) {
    val args = Bundle().apply {
        putString(ChatViewModel.THREAD_ID_KEY, kidId.value)
        putString(ChatViewModel.MODE_KEY, ChatMode.KID.name)
        putString(ChatViewModel.TIER_KEY, tier.name)
    }
    val viewModel = chatViewModel<ChatViewModel>(key = "chat:kid:${kidId.value}:${tier.name}", args = args)
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Leaving the screen mid-recording (home, lock, a call) cancels the voice note instead of sending it.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onEvent(ChatEvent.ScreenStopped) }
    val context = LocalContext.current
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Granted: the kid holds the button again; the press that asked did not record.
        if (!granted) viewModel.onEvent(ChatEvent.MicPermissionDenied)
    }
    ChatScreen(
        state = state,
        onEvent = { event ->
            val needsMic = event == ChatEvent.StartRecording &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            if (needsMic) microphone.launch(Manifest.permission.RECORD_AUDIO) else viewModel.onEvent(event)
        },
        onBack = onBack,
        readAloud = readAloud,
        modifier = modifier,
    )
}

/**
 * A chat thread for any [ChatUiState]. A parent gets the standard (Sapling-sized) thread with free text and photos;
 * a kid's thread renders inside [ProvideKidTier] and switches to [SeedlingChatContent] (presets, emoji, voice notes),
 * Sprout (free text with the kindness hook plus preset chips) or Sapling (free text with the kindness hook).
 *
 * @param onBack draws the back arrow; null on a tab root (M9 §5.2).
 * @param onAttachPhoto parent only: opens the photo picker.
 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    onEvent: (ChatEvent) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onAttachPhoto: () -> Unit = {},
    readAloud: ReadAloud = ReadAloud.None,
) {
    val snackbar = remember { SnackbarHostState() }
    val ready = state as? ChatUiState.Ready
    val title = when {
        ready == null -> stringResource(R.string.feature_chat_title)
        ready.mode == ChatMode.PARENT -> ready.kidName
        else -> stringResource(R.string.feature_chat_kid_title)
    }
    Scaffold(
        modifier = modifier,
        topBar = { ChatTopBar(title = title, onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // Capped and centred on wide windows and panes (D-M7-2); a phone gets the full width as before. The message
        // list spans the full width so its gutters scroll too (M7 review L10); everything else sits inside them.
        ContentWidth(modifier = Modifier.padding(padding).fillMaxSize().imePadding()) { gutters ->
            when (state) {
                ChatUiState.Loading -> LoadingContent(Modifier.padding(gutters))

                is ChatUiState.Error -> when (state.reason) {
                    ChatError.LOAD_FAILED -> ErrorContent(
                        text = stringResource(R.string.feature_chat_error_load_failed),
                        onRetry = { onEvent(ChatEvent.Reload) },
                        modifier = Modifier.padding(gutters),
                    )

                    ChatError.THREAD_NOT_FOUND -> ErrorContent(
                        text = stringResource(R.string.feature_chat_error_not_found),
                        onRetry = null,
                        modifier = Modifier.padding(gutters),
                    )
                }

                is ChatUiState.Ready -> ReadyContent(state, onEvent, onAttachPhoto, readAloud, gutters)
            }
        }
    }
    val notice = ready?.notice
    NoticeEffect(snackbar, notice, notice?.let { noticeText(it) }) { onEvent(ChatEvent.NoticeShown) }
}

@Composable
private fun ReadyContent(
    state: ChatUiState.Ready,
    onEvent: (ChatEvent) -> Unit,
    onAttachPhoto: () -> Unit,
    readAloud: ReadAloud,
    gutters: PaddingValues,
) {
    val tier = state.tier
    if (state.mode == ChatMode.PARENT || tier == null) {
        TextThread(state, onEvent, presets = false, onAttachPhoto = onAttachPhoto, gutters = gutters)
        return
    }
    ProvideKidTier(tier) {
        when (tier) {
            KidUiTier.SEEDLING -> SeedlingChatContent(state, onEvent, readAloud, gutters = gutters)
            KidUiTier.SPROUT -> TextThread(state, onEvent, presets = true, onAttachPhoto = null, gutters = gutters)
            KidUiTier.SAPLING -> TextThread(state, onEvent, presets = false, onAttachPhoto = null, gutters = gutters)
        }
    }
}

/**
 * The parent, Sprout and Sapling thread: messages, then (Sprout) preset chips, the nudge, and the composer. The
 * message list and the composer's white bar fill the width with [gutters] inside; the rest sits inside the gutters.
 */
@Composable
private fun TextThread(
    state: ChatUiState.Ready,
    onEvent: (ChatEvent) -> Unit,
    presets: Boolean,
    onAttachPhoto: (() -> Unit)?,
    gutters: PaddingValues,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (!state.canSend) WaitingBanner(state.mode, state.keyState, modifier = Modifier.padding(gutters))
        MessageList(state = state, onEvent = onEvent, modifier = Modifier.weight(1f), gutters = gutters)
        state.nudge?.let {
            NudgeCard(it, onEvent, modifier = Modifier.padding(gutters).padding(bottom = SproutSpacing.sm))
        }
        if (presets) PresetChips(state, onEvent, modifier = Modifier.padding(gutters))
        TextComposer(state, onEvent, gutters = gutters, onAttachPhoto = onAttachPhoto)
    }
}

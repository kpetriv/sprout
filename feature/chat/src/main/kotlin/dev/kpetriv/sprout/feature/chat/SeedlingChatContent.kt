package dev.kpetriv.sprout.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutButtonDefaults
import dev.kpetriv.sprout.core.designsystem.components.SproutButtonStyle
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.STACK_FONT_SCALE
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.ReadAloud
import dev.kpetriv.sprout.core.ui.TierScaledText
import dev.kpetriv.sprout.core.ui.TierTextRole
import kotlinx.coroutines.CancellationException

/**
 * Seedling (about 5 to 7): no free text. The thread (tap a bubble to hear it, when [readAloud] is real), then twelve
 * preset pills (tap to pick and hear it), the emoji row, and at the bottom the hold-to-talk microphone pill and Send.
 */
@Composable
internal fun SeedlingChatContent(
    state: ChatUiState.Ready,
    onEvent: (ChatEvent) -> Unit,
    readAloud: ReadAloud,
    modifier: Modifier = Modifier,
    gutters: PaddingValues = PaddingValues(),
) {
    val speak: ((String) -> Unit)? = if (readAloud !== ReadAloud.None) readAloud::speak else null
    Column(modifier = modifier.fillMaxSize()) {
        if (!state.canSend) WaitingBanner(ChatMode.KID, state.keyState, modifier = Modifier.padding(gutters))
        MessageList(
            state = state,
            onEvent = onEvent,
            emptyText = stringResource(R.string.feature_chat_empty_seedling),
            onSpeak = speak,
            modifier = Modifier.weight(1f),
            gutters = gutters,
        )
        // Below the thread, everything sits inside the gutters (the thread itself scrolls across the full width).
        Column(modifier = Modifier.padding(gutters)) {
            val recording = state.recording
            if (recording != null) {
                RecordingPanel(recording)
            } else {
                PresetGrid(state, onEvent, speak)
                EmojiRow(state, onEvent)
            }
            SeedlingActions(state, recording != null, onEvent)
        }
    }
}

/** The big microphone pill and Send, side by side (stacked at large font scales). */
@Composable
private fun SeedlingActions(state: ChatUiState.Ready, recording: Boolean, onEvent: (ChatEvent) -> Unit) {
    val mic: @Composable (Modifier) -> Unit = { micModifier ->
        HoldToRecordButton(recording = recording, enabled = state.canSend, onEvent = onEvent, modifier = micModifier)
    }
    val send: @Composable (Modifier) -> Unit = { sendModifier ->
        SproutButton(
            text = stringResource(R.string.feature_chat_send),
            onClick = { onEvent(ChatEvent.SendSelection) },
            enabled = state.canSendSelection && !recording,
            icon = SproutIcons.Send,
            modifier = sendModifier,
        )
    }
    val padding = Modifier.fillMaxWidth().padding(horizontal = SproutSpacing.gutter, vertical = SproutSpacing.md)
    if (LocalDensity.current.fontScale >= STACK_FONT_SCALE) {
        Column(modifier = padding, verticalArrangement = Arrangement.spacedBy(SproutSpacing.md)) {
            mic(Modifier.fillMaxWidth())
            send(Modifier.fillMaxWidth())
        }
    } else {
        Row(
            modifier = padding,
            horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            mic(Modifier.weight(1f))
            send(Modifier.weight(1f))
        }
    }
}

private const val GRID_ROWS = 2

/**
 * The twelve presets as Honey pills in two scrolling rows (M9 §4); the picked one turns Botanical with a check (state
 * is never colour alone). Each pill's emoji is part of the message, so it stays (D-M9-5).
 */
@Composable
private fun PresetGrid(state: ChatUiState.Ready, onEvent: (ChatEvent) -> Unit, speak: ((String) -> Unit)?) {
    // Two rows of six that scroll sideways together; twelve pills need no lazy list.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = SproutSpacing.gutter, vertical = SproutSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
    ) {
        ChatPresets.all.chunked(ChatPresets.all.size / GRID_ROWS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm)) {
                row.forEach { preset -> PresetPill(preset, state, onEvent, speak) }
            }
        }
    }
}

@Composable
private fun PresetPill(
    preset: ChatPreset,
    state: ChatUiState.Ready,
    onEvent: (ChatEvent) -> Unit,
    speak: ((String) -> Unit)?,
) {
    val label = stringResource(preset.label)
    val selected = state.selectedPreset == preset.id
    SproutButton(
        text = "${preset.emoji} $label",
        onClick = {
            onEvent(ChatEvent.SelectPreset(preset.id))
            if (!selected) speak?.invoke(label)
        },
        enabled = state.canSend,
        style = if (selected) SproutButtonStyle.Primary else SproutButtonStyle.Secondary,
        icon = if (selected) SproutIcons.Check else null,
        // At least the tier's touch target both ways, however short the label.
        modifier = Modifier.widthIn(min = minTouch).semantics {
            contentDescription = label
            this.selected = selected
        },
    )
}

/** The emoji row: sent on their own or added to a preset. The emoji are message content (D-M9-5). */
@Composable
private fun EmojiRow(state: ChatUiState.Ready, onEvent: (ChatEvent) -> Unit) {
    val colors = MaterialTheme.colorScheme
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = SproutSpacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
    ) {
        items(ChatPresets.emojis, key = { it.emoji }) { option ->
            val name = stringResource(option.name)
            val selected = state.selectedEmoji == option.emoji
            val enabled = state.canSend
            // Picked: Leaf tint with a thick Botanical ring (the ring, not only the colour, shows the state). Off while
            // the chat waits for a key: grey and faded like the preset pills.
            Surface(
                onClick = { onEvent(ChatEvent.SelectEmoji(option.emoji)) },
                enabled = enabled,
                shape = CircleShape,
                color = when {
                    !enabled -> colors.surfaceContainer
                    selected -> colors.primaryContainer
                    else -> colors.surfaceContainerLowest
                },
                border = if (selected && enabled) {
                    BorderStroke(SelectedRing, colors.primary)
                } else {
                    BorderStroke(1.dp, colors.outlineVariant)
                },
                modifier = Modifier.size(minTouch).semantics {
                    contentDescription = name
                    role = Role.Button
                    this.selected = selected
                },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clearAndSetSemantics { }
                        .alpha(if (enabled) 1f else DISABLED_ALPHA),
                ) {
                    Text(text = option.emoji, style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
    }
}

/** Material's disabled content alpha. */
private const val DISABLED_ALPHA = 0.38f
private val SelectedRing = 3.dp

private const val MILLIS_PER_SECOND = 1_000L

/** Screen readers hear the remaining time at most this often (every second would talk over the kid). */
private const val ANNOUNCE_EVERY_SECONDS = 5

@Composable
private fun RecordingPanel(recording: RecordingUi) {
    val seconds = ((recording.remainingMs + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt()
    // Rounded up to a step of five: the announced text, so the live region changes at most every five seconds.
    val announcedSeconds = (seconds + ANNOUNCE_EVERY_SECONDS - 1) / ANNOUNCE_EVERY_SECONDS * ANNOUNCE_EVERY_SECONDS
    val announcement = pluralStringResource(R.plurals.feature_chat_recording, announcedSeconds, announcedSeconds) +
        ". " + stringResource(R.string.feature_chat_recording_cancel_hint)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SproutSpacing.gutter, vertical = SproutSpacing.sm)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(SproutSpacing.lg)
            .clearAndSetSemantics {
                contentDescription = announcement
                liveRegion = LiveRegionMode.Polite
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
    ) {
        Icon(
            imageVector = SproutIcons.Mic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(RecordingIconSize),
        )
        TierScaledText(
            text = pluralStringResource(R.plurals.feature_chat_recording, seconds, seconds),
            role = TierTextRole.Title,
            tier = KidUiTier.SEEDLING,
            color = MaterialTheme.colorScheme.onErrorContainer,
            textAlign = TextAlign.Center,
        )
        TierScaledText(
            text = stringResource(R.string.feature_chat_recording_cancel_hint),
            role = TierTextRole.Label,
            tier = KidUiTier.SEEDLING,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/** How far a finger must slide away from the microphone to cancel the recording. */
private val CancelDistance = 96.dp

/**
 * Press: [ChatEvent.StartRecording]. Release: [ChatEvent.StopRecording]. Sliding more than [cancelDistance] px away, or
 * the gesture being taken over: [ChatEvent.CancelRecording].
 */
private fun Modifier.holdToRecord(enabled: Boolean, cancelDistance: Float, onEvent: (ChatEvent) -> Unit): Modifier =
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            onEvent(ChatEvent.StartRecording)
            var cancelled = false
            var pressed = true
            try {
                while (pressed) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    if (!cancelled && change != null &&
                        (change.position - down.position).getDistance() > cancelDistance
                    ) {
                        cancelled = true
                        onEvent(ChatEvent.CancelRecording)
                    }
                    pressed = change?.pressed == true
                    change?.consume()
                }
            } catch (e: CancellationException) {
                if (!cancelled) onEvent(ChatEvent.CancelRecording)
                throw e
            }
            if (!cancelled) onEvent(ChatEvent.StopRecording)
        }
    }

/**
 * Hold to record, release to send, slide away to cancel: a big pill with the microphone and "Hold to talk" (Sky tint;
 * red with a record dot and "Let go to send" while recording). Screen readers get a plain button instead: activate to
 * start, activate again to send.
 */
@Composable
internal fun HoldToRecordButton(
    recording: Boolean,
    enabled: Boolean,
    onEvent: (ChatEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val send = rememberUpdatedState(onEvent)
    val cancelDistance = with(LocalDensity.current) { CancelDistance.toPx() }
    val description = stringResource(
        if (recording) R.string.feature_chat_recording_described else R.string.feature_chat_record_described,
    )
    val colors = MaterialTheme.colorScheme
    val (container, content) = when {
        !enabled -> colors.surfaceContainer to colors.onSurfaceVariant
        recording -> colors.error to colors.onError
        else -> colors.tertiaryContainer to colors.onTertiaryContainer
    }
    Row(
        modifier = modifier
            .heightIn(min = SproutTheme.tierTokens.buttonHeight)
            .clip(CircleShape)
            .background(container)
            .holdToRecord(enabled, cancelDistance) { send.value(it) }
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                if (enabled) {
                    onClick {
                        send.value(if (recording) ChatEvent.StopRecording else ChatEvent.StartRecording)
                        true
                    }
                } else {
                    disabled()
                }
            }
            .padding(horizontal = SproutSpacing.md, vertical = SproutSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = if (recording) ImageVector.vectorResource(R.drawable.feature_chat_ic_record) else SproutIcons.Mic
        val label = if (recording) R.string.feature_chat_recording_label else R.string.feature_chat_record_label
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(SproutButtonDefaults.IconSize),
        )
        Text(
            text = stringResource(label),
            style = SproutButtonDefaults.textStyle(),
            color = content,
            textAlign = TextAlign.Center,
        )
    }
}

private val RecordingIconSize = 48.dp

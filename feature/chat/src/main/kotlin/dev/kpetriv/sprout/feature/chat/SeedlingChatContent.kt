package dev.kpetriv.sprout.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.ReadAloud
import dev.kpetriv.sprout.core.ui.TierScaledText
import dev.kpetriv.sprout.core.ui.TierTextRole
import kotlinx.coroutines.CancellationException

/**
 * Seedling (about 5 to 7): no free text. The thread (tap a bubble to hear it, when [readAloud] is real), then twelve
 * picture tiles (tap to pick and hear it), the emoji row, and at the bottom the hold-to-talk microphone and Send.
 */
@Composable
internal fun SeedlingChatContent(
    state: ChatUiState.Ready,
    onEvent: (ChatEvent) -> Unit,
    readAloud: ReadAloud,
    modifier: Modifier = Modifier,
) {
    val speak: ((String) -> Unit)? = if (readAloud !== ReadAloud.None) readAloud::speak else null
    Column(modifier = modifier.fillMaxSize()) {
        if (!state.canSend) WaitingBanner(ChatMode.KID, state.keyState)
        MessageList(
            state = state,
            onEvent = onEvent,
            emptyText = stringResource(R.string.feature_chat_empty_seedling),
            onSpeak = speak,
            modifier = Modifier.weight(1f),
        )
        val recording = state.recording
        if (recording != null) {
            RecordingPanel(recording)
        } else {
            PresetGrid(state, onEvent, speak)
            EmojiRow(state, onEvent)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HoldToRecordButton(recording = recording != null, enabled = state.canSend, onEvent = onEvent)
            Button(
                onClick = { onEvent(ChatEvent.SendSelection) },
                enabled = state.canSendSelection && recording == null,
                modifier = Modifier.weight(1f).heightIn(min = minTouch),
            ) {
                TierScaledText(
                    text = "✉️ " + stringResource(R.string.feature_chat_send),
                    role = TierTextRole.Title,
                    tier = KidUiTier.SEEDLING,
                )
            }
        }
    }
}

private val TileWidth = 132.dp
private val TileHeight = 108.dp
private const val GRID_ROWS = 2

@Composable
private fun PresetGrid(state: ChatUiState.Ready, onEvent: (ChatEvent) -> Unit, speak: ((String) -> Unit)?) {
    LazyHorizontalGrid(
        rows = GridCells.Fixed(GRID_ROWS),
        modifier = Modifier.fillMaxWidth().height(TileHeight * GRID_ROWS + 8.dp + 16.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(ChatPresets.all, key = { it.id }) { preset ->
            val label = stringResource(preset.label)
            val selected = state.selectedPreset == preset.id
            Card(
                onClick = {
                    onEvent(ChatEvent.SelectPreset(preset.id))
                    if (!selected) speak?.invoke(label)
                },
                enabled = state.canSend,
                colors = if (selected) {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                } else {
                    CardDefaults.cardColors()
                },
                border = if (selected) {
                    BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
                } else {
                    null
                },
                modifier = Modifier
                    .size(TileWidth, TileHeight)
                    .semantics {
                        contentDescription = label
                        this.selected = selected
                    },
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(6.dp).clearAndSetSemantics { },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(text = preset.emoji, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmojiRow(state: ChatUiState.Ready, onEvent: (ChatEvent) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(ChatPresets.emojis, key = { it.emoji }) { option ->
            val name = stringResource(option.name)
            val selected = state.selectedEmoji == option.emoji
            Surface(
                onClick = { onEvent(ChatEvent.SelectEmoji(option.emoji)) },
                enabled = state.canSend,
                shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                border = if (selected) {
                    BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
                } else {
                    null
                },
                modifier = Modifier.size(minTouch).semantics {
                    contentDescription = name
                    this.selected = selected
                },
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.clearAndSetSemantics { }) {
                    Text(text = option.emoji, style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
    }
}

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
            .padding(12.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(24.dp)
            .clearAndSetSemantics {
                contentDescription = announcement
                liveRegion = LiveRegionMode.Polite
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "🎙️", style = MaterialTheme.typography.displayMedium, modifier = Modifier.clearAndSetSemantics { })
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
 * Hold to record, release to send, slide away to cancel. Screen readers get a plain button instead: activate to start,
 * activate again to send.
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
    Box(
        modifier = modifier
            .size(minTouch + 8.dp)
            .clip(CircleShape)
            .background(
                when {
                    !enabled -> colors.surfaceVariant
                    recording -> colors.error
                    else -> colors.tertiary
                },
            )
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
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = if (recording) "⏺️" else "🎤", style = MaterialTheme.typography.headlineLarge)
    }
}

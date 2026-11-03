package dev.kpetriv.sprout.feature.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.time.Instant

/** The banner while this device has no usable thread key; sending is off until it goes. */
@Composable
internal fun WaitingBanner(mode: ChatMode, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "🔒", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics { })
            Text(
                text = stringResource(
                    when (mode) {
                        ChatMode.KID -> R.string.feature_chat_waiting_kid
                        ChatMode.PARENT -> R.string.feature_chat_waiting_parent
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/**
 * The thread, newest at the bottom. [onSpeak], when set (Seedling with read-aloud), makes each bubble tappable to
 * hear it.
 */
@Composable
internal fun MessageList(
    state: ChatUiState.Ready,
    onEvent: (ChatEvent) -> Unit,
    modifier: Modifier = Modifier,
    emptyText: String = stringResource(R.string.feature_chat_empty),
    onSpeak: ((String) -> Unit)? = null,
) {
    if (state.messages.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(12.dp),
    ) {
        items(state.messages.asReversed(), key = { it.id }) { message ->
            MessageBubble(
                message = message,
                playing = state.playingId == message.id,
                onEvent = onEvent,
                onSpeak = onSpeak,
            )
        }
        if (state.hasMore) {
            item(key = "older") {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { onEvent(ChatEvent.LoadOlder) }) {
                        Text(stringResource(R.string.feature_chat_load_older))
                    }
                }
            }
        }
    }
}

/** What a bubble says, for read-aloud and accessibility; null for a voice note or photo without caption. */
@Composable
internal fun spokenText(message: MessageUi): String? = when {
    message.locked -> stringResource(R.string.feature_chat_locked)
    message.kind == MessageKind.TEXT -> message.text
    message.kind == MessageKind.PRESET -> presetLabel(message.presetId)
    message.kind == MessageKind.PHOTO -> message.text ?: stringResource(R.string.feature_chat_photo)
    else -> null
}

@Composable
internal fun presetLabel(presetId: String?): String =
    ChatPresets.find(presetId)?.let { stringResource(it.label) } ?: stringResource(R.string.feature_chat_preset_unknown)

private const val MAX_BUBBLE_WIDTH_DP = 300
private const val EMOJI_ONLY_MAX_LENGTH = 8

/** A text made of a few emoji and nothing else is drawn big, like a sticker. */
internal fun isEmojiOnly(text: String): Boolean = text.length <= EMOJI_ONLY_MAX_LENGTH &&
    text.isNotBlank() &&
    text.none { it.isLetterOrDigit() || it.isWhitespace() }

@Composable
private fun MessageBubble(
    message: MessageUi,
    playing: Boolean,
    onEvent: (ChatEvent) -> Unit,
    onSpeak: ((String) -> Unit)?,
) {
    val mine = message.direction == MessageDirection.MINE
    val colors = MaterialTheme.colorScheme
    val spoken = spokenText(message)
    val speak = onSpeak?.takeIf { spoken != null }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        if (!mine && message.authorName != null) {
            Text(
                text = message.authorName,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp).clearAndSetSemantics { },
            )
        }
        Surface(
            color = if (mine) colors.primaryContainer else colors.surfaceVariant,
            contentColor = if (mine) colors.onPrimaryContainer else colors.onSurfaceVariant,
            shape = MaterialTheme.shapes.large,
            onClick = { if (speak != null && spoken != null) speak(spoken) },
            enabled = speak != null,
            modifier = Modifier.widthIn(max = MAX_BUBBLE_WIDTH_DP.dp),
        ) {
            Box(modifier = Modifier.padding(12.dp)) { BubbleContent(message, playing, onEvent) }
        }
        if (message.fromParentPhone) {
            Text(
                text = stringResource(R.string.feature_chat_from_parent_phone),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        BubbleFooter(message, onEvent)
    }
}

@Composable
private fun BubbleContent(message: MessageUi, playing: Boolean, onEvent: (ChatEvent) -> Unit) {
    val author = message.authorName ?: if (message.direction == MessageDirection.MINE) {
        stringResource(R.string.feature_chat_you)
    } else {
        null
    }
    val resources = LocalResources.current
    fun described(content: String): String =
        author?.let { resources.getString(R.string.feature_chat_message_from, it, content) } ?: content
    when {
        message.locked -> Text(
            text = "🔒 " + stringResource(R.string.feature_chat_locked),
            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
        )

        message.kind == MessageKind.TEXT -> {
            val text = message.text.orEmpty()
            Text(
                text = text,
                style = if (isEmojiOnly(text)) {
                    MaterialTheme.typography.displaySmall
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                modifier = Modifier.semantics { contentDescription = described(text) },
            )
        }

        message.kind == MessageKind.PRESET -> PresetContent(message, ::described)

        message.kind == MessageKind.VOICE -> VoiceContent(message, playing, onEvent)

        message.kind == MessageKind.PHOTO -> PhotoContent(message)
    }
}

@Composable
private fun PresetContent(message: MessageUi, described: (String) -> String) {
    val preset = ChatPresets.find(message.presetId)
    val label = presetLabel(message.presetId)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.clearAndSetSemantics { contentDescription = described(label) },
    ) {
        Text(text = preset?.emoji ?: "💬", style = MaterialTheme.typography.headlineMedium)
        Text(text = label, style = MaterialTheme.typography.titleMedium)
        message.emoji?.let { Text(text = it, style = MaterialTheme.typography.headlineMedium) }
    }
}

@Composable
private fun VoiceContent(message: MessageUi, playing: Boolean, onEvent: (ChatEvent) -> Unit) {
    val duration = durationText(message.durationMs ?: 0)
    val action = stringResource(if (playing) R.string.feature_chat_stop_playing else R.string.feature_chat_play)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalIconButton(
            onClick = { onEvent(ChatEvent.PlayVoice(message.id)) },
            enabled = !message.pending || message.sendState != SendState.FAILED,
            modifier = Modifier.semantics { contentDescription = action },
        ) { Text(text = if (playing) "■" else "▶", modifier = Modifier.clearAndSetSemantics { }) }
        Text(
            text = stringResource(R.string.feature_chat_voice_note, duration),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L

/** "0:12" */
internal fun durationText(millis: Long): String {
    val seconds = millis / MILLIS_PER_SECOND
    return String.format(Locale.ROOT, "%d:%02d", seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
}

private const val PHOTO_SIZE_DP = 220
private const val MAX_PHOTO_PIXELS = 1_280

@Composable
private fun PhotoContent(message: MessageUi) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val photoModifier = Modifier.size(PHOTO_SIZE_DP.dp).clip(MaterialTheme.shapes.medium)
        when (val photo = message.photo) {
            is PhotoUi.Loaded -> {
                val bitmap = remember(photo) { decodePhoto(photo.bytes) }
                val description = message.text ?: stringResource(R.string.feature_chat_photo)
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = description,
                        contentScale = ContentScale.Crop,
                        modifier = photoModifier,
                    )
                } else {
                    PhotoPlaceholder(stringResource(R.string.feature_chat_photo_unavailable), photoModifier)
                }
            }

            PhotoUi.Loading, null -> {
                val loading = stringResource(R.string.feature_chat_photo_loading)
                Box(photoModifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = loading })
                }
            }

            PhotoUi.Unavailable ->
                PhotoPlaceholder(stringResource(R.string.feature_chat_photo_unavailable), photoModifier)
        }
        message.text?.let { Text(text = it, style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
private fun PhotoPlaceholder(text: String, modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Text(text = "🖼️ $text", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

/** Decodes [bytes] at most [MAX_PHOTO_PIXELS] on the long side; null when they are not an image. */
private fun decodePhoto(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PHOTO_PIXELS) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

@Composable
private fun BubbleFooter(message: MessageUi, onEvent: (ChatEvent) -> Unit) {
    val time = timeText(message.createdAt)
    when (message.sendState) {
        SendState.SENT -> Text(
            text = time,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )

        SendState.SENDING -> Text(
            text = stringResource(R.string.feature_chat_sending),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )

        SendState.BLOCKED -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(
                text = "⛔ " + stringResource(R.string.feature_chat_blocked),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = { onEvent(ChatEvent.Discard(message.id)) }) {
                Text(stringResource(R.string.feature_chat_discard))
            }
        }

        SendState.FAILED -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(
                text = "⚠️ " + stringResource(R.string.feature_chat_failed),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = { onEvent(ChatEvent.Retry(message.id)) }) {
                Text(stringResource(R.string.feature_chat_retry_send))
            }
            if (message.pending) {
                TextButton(onClick = { onEvent(ChatEvent.Discard(message.id)) }) {
                    Text(stringResource(R.string.feature_chat_discard))
                }
            }
        }
    }
}

/** The message time in the device's zone ("16:05"). */
@Composable
internal fun timeText(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }
    val local = java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()).atZone(ZoneId.systemDefault())
    return formatter.format(local)
}

/** A button or tile at least the tier's touch target. */
internal val minTouch @Composable get() = SproutTheme.tierTokens.minTouchTarget

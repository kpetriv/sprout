package dev.kpetriv.sprout.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The free-text composer (parent, Sprout, Sapling): the draft and Send, with "Add a photo" when [onAttachPhoto] is
 * set (parents). Disabled while the device waits for a thread key.
 */
@Composable
internal fun TextComposer(
    state: ChatUiState.Ready,
    onEvent: (ChatEvent) -> Unit,
    modifier: Modifier = Modifier,
    onAttachPhoto: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onAttachPhoto != null) {
            val attach = stringResource(R.string.feature_chat_attach_photo)
            FilledTonalIconButton(
                onClick = onAttachPhoto,
                enabled = state.canSend,
                modifier = Modifier.semantics { contentDescription = attach },
            ) { Text(text = "📷", modifier = Modifier.clearAndSetSemantics { }) }
        }
        OutlinedTextField(
            value = state.draft,
            onValueChange = { onEvent(ChatEvent.DraftChanged(it)) },
            enabled = state.canSend,
            placeholder = { Text(stringResource(R.string.feature_chat_input_hint)) },
            maxLines = MAX_COMPOSER_LINES,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = { onEvent(ChatEvent.SendText) },
            enabled = state.canSendDraft,
            modifier = Modifier.heightIn(min = minTouch),
        ) {
            if (state.checking) CheckingIndicator()
            Text(stringResource(R.string.feature_chat_send))
        }
    }
}

/** The kindness check is running (at most 2.5 s): a small spinner in the Send button, announced once. */
@Composable
private fun CheckingIndicator() {
    val checking = stringResource(R.string.feature_chat_checking)
    CircularProgressIndicator(
        modifier = Modifier
            .padding(end = 8.dp)
            .size(16.dp)
            .semantics {
                contentDescription = checking
                liveRegion = LiveRegionMode.Polite
            },
        strokeWidth = 2.dp,
    )
}

private const val MAX_COMPOSER_LINES = 4

/** Sprout's preset chips: one tap sends. */
@Composable
internal fun PresetChips(state: ChatUiState.Ready, onEvent: (ChatEvent) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(ChatPresets.all, key = { it.id }) { preset ->
            SuggestionChip(
                onClick = { onEvent(ChatEvent.SendPreset(preset.id)) },
                enabled = state.canSend,
                label = { Text("${preset.emoji} ${stringResource(preset.label)}") },
            )
        }
    }
}

/**
 * The kindness nudge: never blocks. The kid can send anyway, take the suggestion, or go back and edit. The kid's own
 * text is not repeated here (it is still in the composer).
 */
@Composable
internal fun NudgeCard(nudge: NudgeUi, onEvent: (ChatEvent) -> Unit, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "🤔 " + stringResource(R.string.feature_chat_nudge_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = stringResource(R.string.feature_chat_nudge_body), style = MaterialTheme.typography.bodyMedium)
            nudge.suggestion?.let {
                Text(
                    text = stringResource(R.string.feature_chat_nudge_suggestion, it),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (nudge.suggestion != null) {
                    Button(onClick = { onEvent(ChatEvent.NudgeUseSuggestion) }) {
                        Text(stringResource(R.string.feature_chat_nudge_use))
                    }
                }
                OutlinedButton(onClick = { onEvent(ChatEvent.NudgeDismiss) }) {
                    Text(stringResource(R.string.feature_chat_nudge_edit))
                }
                TextButton(onClick = { onEvent(ChatEvent.NudgeSendAnyway) }) {
                    Text(stringResource(R.string.feature_chat_nudge_send_anyway))
                }
            }
        }
    }
}

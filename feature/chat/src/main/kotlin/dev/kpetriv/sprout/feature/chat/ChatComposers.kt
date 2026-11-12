package dev.kpetriv.sprout.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutButtonDefaults
import dev.kpetriv.sprout.core.designsystem.components.SproutButtonStyle
import dev.kpetriv.sprout.core.designsystem.components.SproutCard
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.STACK_FONT_SCALE
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.designsystem.theme.pressScale

/**
 * The free-text composer (parent, Sprout, Sapling) on a white bar with a hairline on top (M9 §4): the draft on a
 * Linen field (with "Add a photo" inside it when [onAttachPhoto] is set, parents) and Send: a Botanical circle for a
 * parent, a labelled Botanical pill on the kid tiers.
 * Disabled while the device waits for a thread key. The bar spans the full width; [gutters] keep its content in the
 * capped column.
 */
@Composable
internal fun TextComposer(
    state: ChatUiState.Ready,
    onEvent: (ChatEvent) -> Unit,
    modifier: Modifier = Modifier,
    gutters: PaddingValues = PaddingValues(),
    onAttachPhoto: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainerLowest, modifier = modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider(color = colors.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(gutters)
                    .padding(horizontal = SproutSpacing.md, vertical = SproutSpacing.sm + SproutSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm + SproutSpacing.xs),
            ) {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = { onEvent(ChatEvent.DraftChanged(it)) },
                    // Review M7: the text being checked is the text sent, so it cannot be edited while the check runs.
                    enabled = state.canSend && !state.checking,
                    placeholder = { Text(stringResource(R.string.feature_chat_input_hint)) },
                    trailingIcon = onAttachPhoto?.let { attach -> { AttachPhotoButton(attach, state.canSend) } },
                    maxLines = MAX_COMPOSER_LINES,
                    shape = RoundedCornerShape(FieldCorner),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surfaceContainerLow,
                        unfocusedContainerColor = colors.surfaceContainerLow,
                        disabledContainerColor = colors.surfaceContainerLow,
                        unfocusedBorderColor = colors.outlineVariant,
                        disabledBorderColor = colors.outlineVariant,
                        focusedBorderColor = colors.primary,
                    ),
                    modifier = Modifier.weight(1f),
                )
                if (state.mode == ChatMode.KID) KidSendButton(state, onEvent) else SendButton(state, onEvent)
            }
        }
    }
}

@Composable
private fun AttachPhotoButton(onAttachPhoto: () -> Unit, enabled: Boolean) {
    IconButton(onClick = onAttachPhoto, enabled = enabled) {
        Icon(
            painter = painterResource(R.drawable.feature_chat_ic_photo_camera),
            contentDescription = stringResource(R.string.feature_chat_attach_photo),
        )
    }
}

/** Send: a Botanical circle at least the tier's touch target; the kindness check spins inside it. */
@Composable
private fun SendButton(state: ChatUiState.Ready, onEvent: (ChatEvent) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val send = stringResource(R.string.feature_chat_send)
    FilledIconButton(
        onClick = { onEvent(ChatEvent.SendText) },
        enabled = state.canSendDraft,
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = colors.primary,
            contentColor = colors.onPrimary,
            disabledContainerColor = colors.surfaceContainer,
            disabledContentColor = colors.onSurfaceVariant,
        ),
        modifier = Modifier
            .size(maxOf(minTouch, SendSize))
            .semantics { contentDescription = send },
    ) {
        if (state.checking) CheckingIndicator() else Icon(SproutIcons.Send, contentDescription = null)
    }
}

/**
 * Send on the Sprout and Sapling composer: a labelled Botanical pill (kid screens never use an icon-only button, M9
 * §3), shaped like `SproutButton.Primary`; the kindness check spins in place of the icon.
 */
@Composable
private fun KidSendButton(state: ChatUiState.Ready, onEvent: (ChatEvent) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = { onEvent(ChatEvent.SendText) },
        enabled = state.canSendDraft,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (pressed) SproutTheme.colors.primaryPressed else colors.primary,
            contentColor = colors.onPrimary,
        ),
        contentPadding = PaddingValues(horizontal = SproutSpacing.md + SproutSpacing.xs, vertical = SproutSpacing.sm),
        interactionSource = interactionSource,
        modifier = Modifier
            .pressScale(interactionSource)
            .heightIn(min = SproutTheme.tierTokens.buttonHeight),
    ) {
        if (state.checking) {
            CheckingIndicator()
        } else {
            Icon(SproutIcons.Send, contentDescription = null, modifier = Modifier.size(SproutButtonDefaults.IconSize))
        }
        Spacer(modifier = Modifier.width(SproutSpacing.sm))
        Text(text = stringResource(R.string.feature_chat_send), style = SproutButtonDefaults.textStyle())
    }
}

/** The kindness check is running (at most 2.5 s): a small spinner in the Send circle, announced once. */
@Composable
private fun CheckingIndicator() {
    val checking = stringResource(R.string.feature_chat_checking)
    CircularProgressIndicator(
        modifier = Modifier
            .size(SpinnerSize)
            .semantics {
                contentDescription = checking
                liveRegion = LiveRegionMode.Polite
            },
        // Inside the disabled Send button: the button's (disabled) content colour, not the default primary (L13).
        color = LocalContentColor.current,
        strokeWidth = 2.dp,
    )
}

private const val MAX_COMPOSER_LINES = 4
private val FieldCorner = 28.dp
private val SendSize = 56.dp
private val SpinnerSize = 20.dp

/** Sprout's preset chips: Linen pills with a hairline; one tap sends. The emoji is part of the message (content). */
@Composable
internal fun PresetChips(state: ChatUiState.Ready, onEvent: (ChatEvent) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = SproutSpacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
    ) {
        items(ChatPresets.all, key = { it.id }) { preset ->
            SuggestionChip(
                onClick = { onEvent(ChatEvent.SendPreset(preset.id)) },
                enabled = state.canSend,
                label = { Text("${preset.emoji} ${stringResource(preset.label)}") },
                shape = CircleShape,
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = colors.surfaceContainerLow,
                    labelColor = colors.onSurface,
                ),
                border = BorderStroke(1.dp, colors.outlineVariant),
            )
        }
    }
}

/**
 * The kindness nudge on a white card: never blocks. The kid can send anyway, take the suggestion (both filled, side by
 * side, stacked at large font scales), or go back and edit. The kid's own text is not repeated here (it is still in
 * the composer).
 */
@Composable
internal fun NudgeCard(nudge: NudgeUi, onEvent: (ChatEvent) -> Unit, modifier: Modifier = Modifier) {
    SproutCard(modifier = modifier.fillMaxWidth().padding(horizontal = SproutSpacing.gutter)) {
        Column(
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
            ) {
                Icon(
                    painter = painterResource(R.drawable.feature_chat_ic_psychology_alt),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.feature_chat_nudge_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                text = stringResource(R.string.feature_chat_nudge_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            nudge.suggestion?.let {
                Text(
                    text = stringResource(R.string.feature_chat_nudge_suggestion, it),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            NudgeActions(nudge, onEvent)
            SproutButton(
                text = stringResource(R.string.feature_chat_nudge_edit),
                onClick = { onEvent(ChatEvent.NudgeDismiss) },
                style = SproutButtonStyle.Tertiary,
            )
        }
    }
}

/**
 * "Send anyway" is a filled pill first, never less prominent than the suggestion: a kid reporting something must not
 * be steered into replacing it (D-M6-52).
 */
@Composable
private fun NudgeActions(nudge: NudgeUi, onEvent: (ChatEvent) -> Unit) {
    val sendAnyway: @Composable (Modifier) -> Unit = { buttonModifier ->
        SproutButton(
            text = stringResource(R.string.feature_chat_nudge_send_anyway),
            onClick = { onEvent(ChatEvent.NudgeSendAnyway) },
            modifier = buttonModifier,
        )
    }
    val useThis: @Composable (Modifier) -> Unit = { buttonModifier ->
        SproutButton(
            text = stringResource(R.string.feature_chat_nudge_use),
            onClick = { onEvent(ChatEvent.NudgeUseSuggestion) },
            modifier = buttonModifier,
        )
    }
    if (LocalDensity.current.fontScale >= STACK_FONT_SCALE) {
        Column(verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm), modifier = Modifier.fillMaxWidth()) {
            sendAnyway(Modifier.fillMaxWidth())
            if (nudge.suggestion != null) useThis(Modifier.fillMaxWidth())
        }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            sendAnyway(Modifier.weight(1f))
            if (nudge.suggestion != null) useThis(Modifier.weight(1f))
        }
    }
}

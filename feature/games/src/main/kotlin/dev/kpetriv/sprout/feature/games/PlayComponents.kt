@file:Suppress("MatchingDeclarationName") // Shared play-screen pieces; the defaults object sits beside them.

package dev.kpetriv.sprout.feature.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.Accent
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.games.TablesLevel
import dev.kpetriv.sprout.core.games.TablesSetup
import dev.kpetriv.sprout.feature.games.playground.PlayTile
import dev.kpetriv.sprout.feature.games.playground.Playground
import dev.kpetriv.sprout.feature.games.playground.PlaygroundType
import dev.kpetriv.sprout.feature.games.playground.StreakPill

/** Shared sizes of the play screens. */
internal object PlayDefaults {
    /** Panels on the field (DESIGN-playground.md "rounded.panel"). */
    val PanelCorner: Dp = 24.dp
    val PanelPadding: Dp = 20.dp

    /** Action tiles ("Start", "Done", "Check") are the tier's tile height, but never taller than this. */
    val MaxActionHeight: Dp = 80.dp

    /** The pill border on the field. */
    val PillBorder: Dp = 1.5.dp

    val SmallIcon: Dp = 20.dp
}

/** The height of an action tile at this tier: the tile height, at least the touch target, at most 80 dp. */
@Composable
@ReadOnlyComposable
internal fun actionHeight(): Dp =
    maxOf(SproutTheme.tierTokens.minTouchTarget, minOf(Playground.tokens.tileHeight, PlayDefaults.MaxActionHeight))

/** A Field-panel box with the panel corner, the way the renders frame a group (the start card, the word slots). */
@Composable
internal fun FieldPanel(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(16.dp),
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Playground.colors.fieldPanel, RoundedCornerShape(PlayDefaults.PanelCorner))
            .padding(PlayDefaults.PanelPadding),
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

/**
 * A full-width action tile with [text] in the tile style and an optional trailing [icon]: the Marigold primary action
 * ("Start", "Check", "Done") or, with another [accent], a secondary one ("Play again", "Clear").
 */
@Composable
internal fun ActionTile(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Accent = Accent.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    PlayTile(
        accent = accent,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = actionHeight()),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val style = PlaygroundType.title.copy(color = LocalContentColor.current, textAlign = TextAlign.Center)
            // One line: a long label ("Play again" at Seedling's type scale) steps down rather than wrapping.
            BasicText(
                text = text,
                style = style,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = MinActionText, maxFontSize = style.fontSize),
                modifier = Modifier.weight(1f, fill = false),
            )
            if (icon != null) Icon(icon, contentDescription = null)
        }
    }
}

/**
 * The white answer box of a typed round (Sapling arithmetic, Times tables): the answer so far in Outfit, centred,
 * Ink on Chalk. It is a real text field labelled "Your answer", so a keyboard (hardware, or the soft one when the kid
 * taps it) works as well as the number pad; IME done checks the answer when [canCheck].
 */
@Composable
internal fun AnswerBox(
    typed: String,
    onEvent: (GamePlayEvent) -> Unit,
    canCheck: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = Playground.colors
    val label = stringResource(R.string.feature_games_answer_label)
    BasicTextField(
        value = typed,
        onValueChange = { onEvent(GamePlayEvent.AnswerChanged(it)) },
        singleLine = true,
        textStyle = PlaygroundType.tileLarge.copy(color = colors.ink, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(colors.field),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (canCheck) onEvent(GamePlayEvent.SubmitAnswer) }),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = actionHeight())
            .semantics { contentDescription = label },
        decorationBox = { field ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = actionHeight())
                    .background(colors.chalk, RoundedCornerShape(Playground.tokens.tileCorner))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { field() }
        },
    )
}

/**
 * This play's tally (M9-games §3.1): "Right 6" and the streak pill, read as one line ("Right 6, Streak 4"). On
 * Sapling the right count is a plain Lime line beside the pill, as quiet as the rest.
 */
@Composable
internal fun TallyLine(right: Int, streak: Int, modifier: Modifier = Modifier) {
    val colors = Playground.colors
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier
                .background(colors.fieldPanel, CircleShape)
                .border(PlayDefaults.PillBorder, colors.lime, CircleShape)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                SproutIcons.CheckCircle,
                contentDescription = null,
                tint = colors.lime,
                modifier = Modifier.size(PlayDefaults.SmallIcon),
            )
            Text(
                text = stringResource(R.string.feature_games_right, right),
                style = PlaygroundType.figure,
                color = colors.lime,
            )
        }
        StreakPill(count = streak)
    }
}

/** The Times tables setup in the top bar ("3, 4, 6 · Gentle"): a Lime pill, Ink on it. */
@Composable
internal fun SetupChip(setup: TablesSetup) {
    val colors = Playground.colors
    Text(
        text = stringResource(R.string.feature_games_tables_chip, tablesLabel(setup), levelName(setup.level)),
        style = MaterialTheme.typography.labelMedium,
        color = colors.ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = SetupChipMaxWidth)
            .background(colors.lime, CircleShape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

private val SetupChipMaxWidth = 150.dp
private val MinActionText = 14.sp

/** The chosen tables as the hub and the chip show them: "3, 4, 6", or "Mix" for all of them. */
@Composable
internal fun tablesLabel(setup: TablesSetup): String = if (setup.isMix) {
    stringResource(R.string.feature_games_tables_mix)
} else {
    setup.tables.sorted().joinToString(", ")
}

/** The level's short name ("Gentle", "Steady", "Rock star"). */
@Composable
internal fun levelName(level: TablesLevel): String = stringResource(
    when (level) {
        TablesLevel.GENTLE -> R.string.feature_games_level_gentle
        TablesLevel.STEADY -> R.string.feature_games_level_steady
        TablesLevel.ROCK_STAR -> R.string.feature_games_level_rock_star
    },
)

/** A caption line on the field (Chalk soft is too faint on a panel, so the opaque caption colour). */
@Composable
internal fun Caption(text: String, modifier: Modifier = Modifier, textAlign: TextAlign = TextAlign.Center) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = Playground.colors.caption,
        textAlign = textAlign,
        modifier = modifier,
    )
}

/** A decorative icon beside text that says the same thing. */
@Composable
internal fun DecorativeIcon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    Icon(
        icon,
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(PlayDefaults.SmallIcon).clearAndSetSemantics { },
    )
}

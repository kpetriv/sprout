@file:Suppress("MatchingDeclarationName") // Named after its composables; the choice rules sit beside them.

package dev.kpetriv.sprout.feature.games

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.Accent
import dev.kpetriv.sprout.core.games.TablesLevel
import dev.kpetriv.sprout.core.games.TablesSetup
import dev.kpetriv.sprout.feature.games.playground.NumberPad
import dev.kpetriv.sprout.feature.games.playground.NumberPadDefaults
import dev.kpetriv.sprout.feature.games.playground.PlayTile
import dev.kpetriv.sprout.feature.games.playground.Playground
import dev.kpetriv.sprout.feature.games.playground.PlaygroundType

/** How the table tiles answer a tap (M9-games §6.3): a multi-select of 2 to 12, with "Mix" for all of them. */
internal object TablesChoice {
    /** Every table: what "Mix" selects. */
    val mix: Set<Int> = TablesSetup.TABLES.toSet()

    /**
     * The tables after a tap on [table]: it toggles that one table (from Mix too, which then leaves the other ten),
     * except that the last chosen table cannot be deselected.
     */
    fun toggle(current: Set<Int>, table: Int): Set<Int> = when {
        table !in current -> current + table
        current.size > 1 -> current - table
        else -> current
    }
}

private const val TABLE_COLUMNS = 4
private val TablesGridMaxWidth = 420.dp
private val CheckBadge = 18.dp
private val LevelRing = 28.dp

/**
 * The Times tables start card (M9-games §3.1, "games-B-tables-setup"): "Which tables?" with a round checkbox tile for
 * each table 2 to 12 and "Mix" for all of them (a chosen tile is Marigold with a check, Lime on the quiet set), the
 * three level tiles (the chosen one Lime with a check), "Best this week: 18 of 20" from the ledger when there is a
 * play this week, and the Marigold "Start · 20 questions". No age chip and no reward line (the renders' inventions).
 */
@Composable
internal fun TablesSetupContent(state: GamePlayUiState.Start, setup: TablesSetup, onEvent: (GamePlayEvent) -> Unit) {
    FieldColumn(verticalArrangement = Arrangement.spacedBy(PlayLayoutDefaults.SectionGap, Alignment.Top)) {
        SectionHeading(stringResource(R.string.feature_games_tables_which))
        TablesGrid(setup) { onEvent(GamePlayEvent.TablesChanged(it)) }
        SectionHeading(stringResource(R.string.feature_games_tables_level))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TablesLevel.entries.forEach { level ->
                LevelTile(level, selected = level == setup.level) { onEvent(GamePlayEvent.LevelChanged(level)) }
            }
        }
        val best = state.bestThisWeek
        if (best != null) BestThisWeek(best, state.rounds)
        ActionTile(
            text = stringResource(R.string.feature_games_tables_start, state.rounds),
            onClick = { onEvent(GamePlayEvent.Start) },
            icon = SproutIcons.ChevronRight,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = PlaygroundType.heading,
        color = Playground.colors.chalk,
        modifier = Modifier.fillMaxWidth().semantics { heading() },
    )
}

@Composable
private fun TablesGrid(setup: TablesSetup, onChange: (Set<Int>) -> Unit) {
    val mixLabel = stringResource(R.string.feature_games_tables_mix)
    val mixDescription = stringResource(R.string.feature_games_tables_mix_description)
    val cells: List<Int?> = TablesSetup.TABLES.toList() + null
    Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = TablesGridMaxWidth),
        verticalArrangement = Arrangement.spacedBy(PlayLayoutDefaults.TileGap),
    ) {
        cells.chunked(TABLE_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(PlayLayoutDefaults.TileGap)) {
                row.forEach { table ->
                    if (table == null) {
                        // Checked when every table is; unchecking it changes nothing (pick tables to narrow down).
                        TableTile(mixLabel, mixDescription, checked = setup.isMix, Modifier.weight(1f)) {
                            onChange(TablesChoice.mix)
                        }
                    } else {
                        TableTile(
                            label = "$table",
                            description = "$table",
                            checked = table in setup.tables,
                            modifier = Modifier.weight(1f),
                        ) { onChange(TablesChoice.toggle(setup.tables, table)) }
                    }
                }
            }
        }
    }
}

/**
 * A table tile: a checkbox for TalkBack ("3, checked"), Marigold with a check when [checked]. The tile's press travel
 * follows the toggle through the shared interaction source.
 */
@Composable
private fun TableTile(label: String, description: String, checked: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    PlayTile(
        accent = if (checked) Accent.Marigold else Accent.Panel,
        onClick = null,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        interactionSource = source,
        modifier = modifier
            .aspectRatio(1f)
            .toggleable(
                value = checked,
                interactionSource = source,
                indication = null,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .semantics { contentDescription = description },
    ) {
        Text(
            text = label,
            style = PlaygroundType.tile.copy(color = LocalContentColor.current),
            modifier = Modifier.clearAndSetSemantics { },
        )
        if (checked) {
            Icon(
                SproutIcons.Check,
                contentDescription = null,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(CheckBadge),
            )
        }
    }
}

@Composable
private fun LevelTile(level: TablesLevel, selected: Boolean, onClick: () -> Unit) {
    val colors = Playground.colors
    PlayTile(
        accent = if (selected) Accent.Lime else Accent.Panel,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = levelLine(level),
                style = PlaygroundType.figure.copy(color = LocalContentColor.current),
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Box(
                    modifier = Modifier.size(LevelRing).clearAndSetSemantics { },
                    contentAlignment = Alignment.Center,
                ) { Icon(SproutIcons.CheckCircle, contentDescription = null, tint = colors.ink) }
            } else {
                Spacer(
                    modifier = Modifier
                        .size(LevelRing)
                        .border(2.dp, colors.caption, CircleShape),
                )
            }
        }
    }
}

@Composable
private fun levelLine(level: TablesLevel): String = stringResource(
    when (level) {
        TablesLevel.GENTLE -> R.string.feature_games_level_gentle_line
        TablesLevel.STEADY -> R.string.feature_games_level_steady_line
        TablesLevel.ROCK_STAR -> R.string.feature_games_level_rock_star_line
    },
)

@Composable
private fun BestThisWeek(best: Int, rounds: Int) {
    val colors = Playground.colors
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DecorativeIcon(SproutIcons.Timer, tint = colors.sky)
        Text(
            text = stringResource(R.string.feature_games_tables_best, best, rounds),
            style = PlaygroundType.figure,
            color = colors.sky,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A Times tables question (M9-games §3.1, "games-B-tables-play"): the question at 68 sp (48 on Sapling), the white
 * answer box, "Right 6 · Streak 4" and the number pad with its Coral backspace and Marigold "Go" (Sapling: the quiet
 * pad). The ring above counts the level's seconds; only Rock star is timed.
 */
@Composable
internal fun TablesGame(
    state: GamePlayUiState.Playing,
    ui: RoundUi.Tables,
    header: @Composable () -> Unit,
    onEvent: (GamePlayEvent) -> Unit,
) {
    val round = ui.round
    val question = stringResource(R.string.feature_games_question_description, round.spokenPrompt)
    val canCheck = ui.typed.isNotEmpty()
    RoundLayout(
        header = header,
        prompt = {
            Text(
                text = round.prompt,
                style = PlaygroundType.prompt,
                color = Playground.colors.chalk,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { contentDescription = question },
            )
            AnswerBox(
                typed = ui.typed,
                onEvent = onEvent,
                canCheck = canCheck,
                modifier = Modifier.widthIn(max = NumberPadDefaults.MaxWidth),
            )
            TallyLine(right = state.right, streak = state.streak)
        },
        answers = {
            NumberPad(
                onDigit = { onEvent(GamePlayEvent.Digit(it)) },
                onBackspace = { onEvent(GamePlayEvent.Backspace) },
                onGo = { onEvent(GamePlayEvent.SubmitAnswer) },
                goEnabled = canCheck,
            )
        },
    )
}

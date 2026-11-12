package dev.kpetriv.sprout.feature.games

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.games.GameCatalogue
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.rememberMoneyFormatter
import dev.kpetriv.sprout.feature.games.playground.Playground
import dev.kpetriv.sprout.feature.games.playground.PlaygroundType
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The start card of Numbers, Memory and Word scramble (M9-games §3, §4): a Field-panel card with the game's
 * description, the rounds chip and the Marigold "Start"; Sapling adds the "Timed" switch and "Your last plays", the
 * last three credited plays from the ledger (date, score, credit). Centred on the field below Sapling; Sapling's,
 * with its list, starts at the top.
 */
@Composable
internal fun StartCard(state: GamePlayUiState.Start, onEvent: (GamePlayEvent) -> Unit) {
    val sapling = state.tier == KidUiTier.SAPLING
    FieldColumn(
        verticalArrangement = Arrangement.spacedBy(
            PlayLayoutDefaults.SectionGap,
            if (sapling) Alignment.Top else Alignment.CenterVertically,
        ),
    ) {
        FieldPanel(horizontalAlignment = Alignment.Start) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = gameDescription(state.kind, state.tier),
                    style = PlaygroundType.heading,
                    color = Playground.colors.chalk,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                RoundsChip(state.rounds)
            }
            // Times tables have their own start card: their level decides the timing.
            if (state.timedAvailable) TimedSwitch(state.timed, onEvent)
            ActionTile(
                text = stringResource(R.string.feature_games_start),
                onClick = { onEvent(GamePlayEvent.Start) },
                icon = SproutIcons.ChevronRight,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val currency = state.currency
        if (sapling && state.history.isNotEmpty() && currency != null) LastPlays(state.history, currency)
    }
}

@Composable
private fun RoundsChip(rounds: Int) {
    val colors = Playground.colors
    Text(
        text = pluralStringResource(R.plurals.feature_games_rounds, rounds, rounds),
        style = MaterialTheme.typography.labelLarge,
        color = colors.chalk,
        modifier = Modifier
            .background(colors.fieldDeep, CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun TimedSwitch(timed: Boolean, onEvent: (GamePlayEvent) -> Unit) {
    val colors = Playground.colors
    val label = stringResource(R.string.feature_games_timed)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.fieldDeep, RoundedCornerShape(Playground.tokens.tileCorner))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecorativeIcon(SproutIcons.Timer, tint = colors.sky)
                Text(text = label, style = PlaygroundType.figure, color = colors.chalk)
            }
            Caption(text = stringResource(R.string.feature_games_timed_description), textAlign = TextAlign.Start)
        }
        Switch(
            checked = timed,
            onCheckedChange = { onEvent(GamePlayEvent.TimedChanged(it)) },
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

/** "Your last plays": one row per credited play, newest first, on a Field-panel list. */
@Composable
private fun LastPlays(plays: List<GamePlaySummary>, currency: Currency.Fiat) {
    val colors = Playground.colors
    val formatter = rememberMoneyFormatter()
    val locale = LocalConfiguration.current.locales[0]
    val dates = remember(locale) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, DATE_SKELETON), locale)
    }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.feature_games_last_plays).uppercase(locale),
            style = MaterialTheme.typography.labelLarge,
            color = colors.caption,
            modifier = Modifier.semantics { heading() },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.fieldPanel, RoundedCornerShape(PlayDefaults.PanelCorner)),
        ) {
            plays.forEachIndexed { index, play ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PlayRow(
                    date = dates.format(play.playedOn.toLocalDate()),
                    score = stringResource(
                        R.string.feature_games_history_score,
                        play.score,
                        GameCatalogue.rounds(play.gameId),
                    ),
                    amount = formatter.format(play.amount, currency, signed = true),
                )
            }
        }
    }
}

@Composable
private fun PlayRow(date: String, score: String, amount: String) {
    val colors = Playground.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(HistoryBadge).background(colors.fieldDeep, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(SproutIcons.Check, contentDescription = null, tint = colors.sky) }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = date, style = PlaygroundType.figure, color = colors.chalk)
            Text(text = score, style = MaterialTheme.typography.bodyMedium, color = colors.caption)
        }
        Text(text = amount, style = PlaygroundType.figure, color = colors.lime)
    }
}

private fun CalendarDate.toLocalDate(): LocalDate = LocalDate.of(year, month, day)

/** "Thu 12 Nov" in the locale's order. */
private const val DATE_SKELETON = "EEEdMMM"
private val HistoryBadge = 36.dp

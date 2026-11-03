package dev.kpetriv.sprout.feature.timebank

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.rememberHiltViewModelFactory
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.TierScaledText
import dev.kpetriv.sprout.core.ui.TierTextRole
import dev.kpetriv.sprout.core.ui.rememberMoneyFormatter
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** A Hilt ViewModel scoped to the current `ViewModelStoreOwner` (the nav entry) under [key], with [args]. */
@Composable
internal inline fun <reified VM : ViewModel> timeBankViewModel(key: String, args: Bundle): VM {
    val owner = checkNotNull(LocalViewModelStoreOwner.current) { "A time bank route needs a ViewModelStoreOwner" }
    val defaults = (owner as? HasDefaultViewModelProviderFactory)?.defaultViewModelCreationExtras
        ?: CreationExtras.Empty
    val extras = MutableCreationExtras(defaults).apply { set(DEFAULT_ARGS_KEY, args) }
    return viewModel(viewModelStoreOwner = owner, key = key, factory = rememberHiltViewModelFactory(), extras = extras)
}

/** A plural quantity for [pluralStringResource]; minutes never come near [Int.MAX_VALUE]. */
internal fun Long.toPluralCount(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L

/** A stopwatch reading: "04:05", or "1:02:03" from an hour on. */
internal fun clockText(seconds: Long): String {
    val hours = seconds / SECONDS_PER_HOUR
    val minutes = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val rest = seconds % SECONDS_PER_MINUTE
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, rest)
    }
}

/** "12 minutes left", "Time's up! Press Stop." or "No screen time left". */
@Composable
internal fun remainingText(state: TimeBankUiState.Ready): String = when {
    state.isEmpty -> stringResource(R.string.feature_timebank_empty_title)

    state.timeUp -> stringResource(R.string.feature_timebank_time_up)

    else -> pluralStringResource(
        R.plurals.feature_timebank_minutes_left,
        state.remainingMinutes.toPluralCount(),
        state.remainingMinutes,
    )
}

/**
 * The Sprout and Sapling timer: status line, the stopwatch, minutes left with a bar, and Start or Stop at least the
 * tier's touch target tall.
 */
@Composable
internal fun TimerCard(state: TimeBankUiState.Ready, onEvent: (TimeBankEvent) -> Unit, modifier: Modifier = Modifier) {
    val tier = SproutTheme.kidTier
    val elapsedLabel = stringResource(R.string.feature_timebank_elapsed_label)
    val clock = clockText(state.elapsedSeconds)
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TierScaledText(
                text = stringResource(
                    when {
                        state.running -> R.string.feature_timebank_running
                        state.isEmpty -> R.string.feature_timebank_empty_title
                        else -> R.string.feature_timebank_idle
                    },
                ),
                role = TierTextRole.Title,
                tier = tier,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = clock,
                style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace),
                color = if (state.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clearAndSetSemantics { contentDescription = "$elapsedLabel $clock" },
            )
            TierScaledText(
                text = remainingText(state),
                role = TierTextRole.Body,
                tier = tier,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            LinearProgressIndicator(
                progress = { state.ringFraction },
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
            )
            if (state.isEmpty) {
                TierScaledText(
                    text = stringResource(R.string.feature_timebank_empty_body),
                    role = TierTextRole.Label,
                    tier = tier,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StartStopButton(state, onEvent, tier)
        }
    }
}

@Composable
private fun StartStopButton(state: TimeBankUiState.Ready, onEvent: (TimeBankEvent) -> Unit, tier: KidUiTier) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = SproutTheme.tierTokens.minTouchTarget)
    if (state.running) {
        Button(
            onClick = { onEvent(TimeBankEvent.Stop) },
            enabled = state.canStop,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = modifier,
        ) { TierScaledText(stringResource(R.string.feature_timebank_stop), TierTextRole.Title, tier) }
    } else {
        Button(onClick = { onEvent(TimeBankEvent.Start) }, enabled = state.canStart, modifier = modifier) {
            TierScaledText(stringResource(R.string.feature_timebank_start), TierTextRole.Title, tier)
        }
    }
}

/** A heading for a list section. */
@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.semantics { heading() },
    )
}

/** One finished session: "16:05 · 20 min", with the date first when [withDate]. */
@Composable
internal fun SessionRow(session: SessionUi, withDate: Boolean, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = rememberMoneyFormatter()
    val pattern = if (withDate) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    } else {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    }
    val text = stringResource(
        R.string.feature_timebank_session,
        pattern.withLocale(locale).format(session.startedAt),
        formatter.formatMinutes(session.minutes),
    )
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) { }) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

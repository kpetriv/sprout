package dev.kpetriv.sprout.feature.timebank

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import dev.kpetriv.sprout.core.designsystem.components.CardMeaning
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutButtonDefaults
import dev.kpetriv.sprout.core.designsystem.components.SproutCard
import dev.kpetriv.sprout.core.designsystem.components.accentTint
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
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
 * The Sprout and Sapling timer on a white card (M9 §4): status line, the stopwatch in the display face, minutes left
 * over a Botanical bar on a Sky-tint track, and Start (a Botanical pill) or Stop (an error-red pill, with its word and
 * icon) at least the tier's touch target tall. Time up says so in words; colour never carries a state alone.
 */
@Composable
internal fun TimerCard(state: TimeBankUiState.Ready, onEvent: (TimeBankEvent) -> Unit, modifier: Modifier = Modifier) {
    val tier = SproutTheme.kidTier
    val elapsedLabel = stringResource(R.string.feature_timebank_elapsed_label)
    val clock = clockText(state.elapsedSeconds)
    val colors = MaterialTheme.colorScheme
    SproutCard(meaning = CardMeaning.Time, modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm + SproutSpacing.xs),
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
                style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"),
                color = if (state.running) colors.primary else colors.onSurface,
                modifier = Modifier.clearAndSetSemantics { contentDescription = "$elapsedLabel $clock" },
            )
            TierScaledText(
                text = remainingText(state),
                role = TierTextRole.Body,
                tier = tier,
                textAlign = TextAlign.Center,
                color = if (state.timeUp) colors.error else colors.onSurface,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            MinutesTrack(fraction = state.ringFraction)
            if (state.isEmpty) {
                TierScaledText(
                    text = stringResource(R.string.feature_timebank_empty_body),
                    role = TierTextRole.Label,
                    tier = tier,
                    textAlign = TextAlign.Center,
                    color = colors.onSurfaceVariant,
                )
            }
            StartStopButton(state, onEvent)
        }
    }
}

private val TrackHeight = 14.dp

/** The minutes still left as a Botanical bar on a Sky-tint track; decorative (the text above says it). */
@Composable
private fun MinutesTrack(fraction: Float) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TrackHeight)
            .clip(CircleShape)
            .background(colors.tertiaryContainer)
            .clearAndSetSemantics { },
    ) {
        val clamped = fraction.coerceIn(0f, 1f)
        if (clamped > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(colors.primary),
            )
        }
    }
}

@Composable
private fun StartStopButton(state: TimeBankUiState.Ready, onEvent: (TimeBankEvent) -> Unit) {
    if (state.running) {
        StopButton(enabled = state.canStop, onClick = {
            onEvent(TimeBankEvent.Stop)
        }, modifier = Modifier.fillMaxWidth())
    } else {
        SproutButton(
            text = stringResource(R.string.feature_timebank_start),
            onClick = { onEvent(TimeBankEvent.Start) },
            enabled = state.canStart,
            icon = ImageVector.vectorResource(R.drawable.feature_timebank_ic_play_arrow),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Stop: an error-red pill with the stop icon and its word, the size of a `SproutButton`. */
@Composable
private fun StopButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
        contentPadding = PaddingValues(horizontal = SproutSpacing.lg, vertical = SproutSpacing.sm),
        modifier = modifier.heightIn(min = SproutTheme.tierTokens.buttonHeight),
    ) {
        Icon(
            painter = painterResource(R.drawable.feature_timebank_ic_stop_circle),
            contentDescription = null,
            modifier = Modifier.size(SproutButtonDefaults.IconSize),
        )
        Spacer(modifier = Modifier.width(SproutSpacing.sm))
        Text(text = stringResource(R.string.feature_timebank_stop), style = SproutButtonDefaults.textStyle())
    }
}

/** A heading for a list section. */
@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.semantics { heading() },
    )
}

private val SessionCircle = 36.dp
private val SessionIcon = 20.dp

/** One finished session: a Sky-tint clock and "16:05 · 20 min", with the date first when [withDate]. */
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
    val tint = CardMeaning.Time.accentTint()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = SproutSpacing.sm)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md),
    ) {
        Box(
            modifier = Modifier.size(SessionCircle).background(tint.container, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = SproutIcons.Schedule,
                contentDescription = null,
                tint = tint.content,
                modifier = Modifier.size(SessionIcon),
            )
        }
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** [sessions] in one white card, hairlines between the rows. */
@Composable
internal fun SessionList(sessions: List<SessionUi>, withDate: Boolean, modifier: Modifier = Modifier) {
    SproutCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = SproutSpacing.md, vertical = SproutSpacing.xs),
    ) {
        sessions.forEachIndexed { index, session ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SessionRow(session, withDate = withDate)
        }
    }
}

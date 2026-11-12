package dev.kpetriv.sprout.feature.chores

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.components.CardMeaning
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.accentTint
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.MoneyFormatter

@Composable
internal fun LoadingContent(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.feature_chores_loading)
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = description })
    }
}

@Composable
internal fun ErrorContent(text: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(SproutSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        SproutButton(text = stringResource(R.string.feature_chores_retry), onClick = onRetry)
    }
}

/** A heading in Ink: [style] defaults to the form-section size; screen sections pass `titleLarge` (display face). */
@Composable
internal fun SectionHeading(text: String, modifier: Modifier = Modifier, style: TextStyle? = null) {
    Text(
        text = text,
        style = style ?: MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.semantics { heading() },
    )
}

/**
 * A reward as the renders show it: money in Botanical and minutes in Sky, in [style] (the display face for amounts),
 * joined by "+" in Ink soft. The words are the same as [RewardUi.format], so the colour never carries the meaning.
 */
@Composable
internal fun RewardText(
    reward: RewardUi,
    formatter: MoneyFormatter,
    tier: KidUiTier,
    style: TextStyle,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    val scheme = MaterialTheme.colorScheme
    // Each amount keeps together ("15 min" never breaks); a long reward wraps at the "+".
    val money = reward.money?.let { formatter.formatForTier(it, reward.currency, tier).keepTogether() }
    val minutes = reward.minutes?.let { formatter.formatForTier(it, Currency.Minutes, tier).keepTogether() }
    val words = reward.format(formatter, tier)
    val text = buildAnnotatedString {
        money?.let { withStyle(SpanStyle(color = scheme.primary)) { append(it) } }
        if (money != null && minutes != null) {
            withStyle(SpanStyle(color = scheme.onSurfaceVariant)) { append(" + ") }
        }
        minutes?.let { withStyle(SpanStyle(color = scheme.tertiary)) { append(it) } }
    }
    // Read (and found by tests) with ordinary spaces.
    Text(
        text = text,
        style = style,
        modifier = modifier.clearAndSetSemantics { this.text = AnnotatedString(words) },
        textAlign = textAlign,
    )
}

private fun String.keepTogether(): String = replace(' ', '\u00A0')

/** How a chore stands after "I did it": an icon and words on a tint pill (never colour alone). */
internal enum class StatusTone { Waiting, Done, NotAccepted }

/**
 * The state of a submitted chore on a pill: [StatusTone.Waiting] a clock on Honey tint, [StatusTone.Done] a check on
 * Leaf tint, [StatusTone.NotAccepted] a cross on Linen. Announced politely when it changes.
 */
@Composable
internal fun StatusPill(text: String, tone: StatusTone, style: TextStyle, modifier: Modifier = Modifier) {
    val tint = when (tone) {
        StatusTone.Waiting -> CardMeaning.Attention.accentTint()
        StatusTone.Done -> CardMeaning.Money.accentTint()
        StatusTone.NotAccepted -> CardMeaning.Content.accentTint()
    }
    val icon = when (tone) {
        StatusTone.Waiting -> SproutIcons.Schedule
        StatusTone.Done -> SproutIcons.CheckCircle
        StatusTone.NotAccepted -> SproutIcons.Close
    }
    Row(
        modifier = modifier
            .background(tint.container, MaterialTheme.shapes.small)
            .padding(horizontal = SproutSpacing.md - SproutSpacing.xs, vertical = SproutSpacing.sm)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint.content, modifier = Modifier.size(StatusIcon))
        Text(text = text, style = style, color = tint.content)
    }
}

private val StatusIcon = 20.dp

/** A small pill naming a state ("Active", "Proposed", "Archived") on [meaning]'s tint. */
@Composable
internal fun LabelPill(text: String, meaning: CardMeaning, modifier: Modifier = Modifier) {
    val tint = meaning.accentTint()
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = tint.content,
        modifier = modifier
            .background(tint.container, CircleShape)
            .padding(horizontal = SproutSpacing.sm + SproutSpacing.xs, vertical = SproutSpacing.xs),
    )
}

/** Shows [text] once in a snackbar, then reports it shown. */
@Composable
internal fun MessageEffect(host: SnackbarHostState, key: Any?, text: String?, onShown: () -> Unit) {
    val shown = rememberUpdatedState(onShown)
    LaunchedEffect(key, text) {
        if (text != null) {
            host.showSnackbar(text)
            shown.value()
        }
    }
}

package dev.kpetriv.sprout.feature.chores

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.components.CardMeaning
import dev.kpetriv.sprout.core.designsystem.components.Pictogram
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutButtonStyle
import dev.kpetriv.sprout.core.designsystem.components.SproutCard
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.STACK_FONT_SCALE
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.ContentWidth
import dev.kpetriv.sprout.core.ui.MoneyFormatter
import dev.kpetriv.sprout.core.ui.rememberMoneyFormatter
import dev.kpetriv.sprout.core.ui.withGutters

/**
 * Sprout (8 to 11) and Sapling (12+), the "Morning garden" chores of the renders: today's chores as white cards with a
 * pictogram, the title, the reward on the right in the display face and an "I did it" pill that opens the submission
 * sheet (full width on Sprout; a compact pill at the end on Sapling, whose rows are denser), then the next seven days
 * in one card of rows. Sapling also lists its chore ideas and can propose one.
 */
@Composable
internal fun ListTodayContent(
    state: KidTodayUiState.Ready,
    onEvent: (KidTodayEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tier = state.tier
    val formatter = rememberMoneyFormatter()
    ContentWidth(modifier = modifier.fillMaxSize()) { gutters ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = SproutSpacing.gutter, vertical = SproutSpacing.md)
                .withGutters(gutters),
            verticalArrangement = Arrangement.spacedBy(SproutSpacing.md),
        ) {
            item(key = "today") { Heading(stringResource(R.string.feature_chores_today)) }
            if (state.today.isEmpty()) {
                item(key = "today-empty") { EmptyLine(stringResource(R.string.feature_chores_no_chores_today)) }
            }
            items(state.today, key = { "today:${it.choreId.value}" }) { chore ->
                TodayCard(
                    chore = chore,
                    state = state,
                    formatter = formatter,
                    submitting = chore.choreId in state.submitting,
                    onDidIt = { onEvent(KidTodayEvent.OpenSubmit(chore.choreId)) },
                )
            }
            item(key = "upcoming") {
                Heading(stringResource(R.string.feature_chores_upcoming), Modifier.padding(top = SproutSpacing.sm))
            }
            if (state.upcoming.isEmpty()) {
                item(key = "upcoming-empty") { EmptyLine(stringResource(R.string.feature_chores_nothing_upcoming)) }
            } else {
                item(key = "upcoming-card") { UpcomingCard(state, formatter) }
            }
            if (state.canPropose) {
                item(key = "ideas") {
                    Heading(stringResource(R.string.feature_chores_my_ideas), Modifier.padding(top = SproutSpacing.sm))
                }
                if (state.proposals.isNotEmpty()) {
                    item(key = "ideas-card") { IdeasCard(state.proposals, tier, formatter) }
                }
                item(key = "propose") {
                    SproutButton(
                        text = stringResource(R.string.feature_chores_propose),
                        onClick = { onEvent(KidTodayEvent.OpenPropose) },
                        style = SproutButtonStyle.Tertiary,
                        icon = SproutIcons.Add,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String, modifier: Modifier = Modifier) {
    SectionHeading(text = text, modifier = modifier, style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun EmptyLine(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * One chore today: the pictogram, the title (and description, and the due day when it is not today), the reward on
 * the right (under the title at large text sizes), then "I did it" or the chore's state.
 */
@Composable
private fun TodayCard(
    chore: TodayChoreUi,
    state: KidTodayUiState.Ready,
    formatter: MoneyFormatter,
    submitting: Boolean,
    onDidIt: () -> Unit,
) {
    val tier = state.tier
    val sapling = tier == KidUiTier.SAPLING
    val stacked = LocalDensity.current.fontScale >= STACK_FONT_SCALE
    val reward = chore.reward.format(formatter, tier)
    SproutCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(if (sapling) SproutSpacing.sm else SproutSpacing.md)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md),
                modifier = Modifier.semantics(mergeDescendants = true) {},
            ) {
                Pictogram(chore.pictogram, size = if (sapling) SaplingPictogram else SproutPictogram)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SproutSpacing.xs)) {
                    Text(text = chore.title, style = MaterialTheme.typography.titleLarge)
                    chore.description?.let { Detail(it) }
                    if (chore.dueOn != state.date) Detail(relativeDueLabel(chore.dueOn, state.date))
                    if (stacked) RewardText(chore.reward, formatter, tier, MaterialTheme.typography.titleLarge)
                }
                if (!stacked) {
                    RewardText(
                        reward = chore.reward,
                        formatter = formatter,
                        tier = tier,
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.End,
                        // A long reward wraps at its "+" so the title keeps most of the row.
                        modifier = Modifier.widthIn(max = RewardMaxWidth).padding(start = SproutSpacing.xs),
                    )
                }
            }
            TodayAction(chore, reward, submitting, compact = sapling && !stacked, onDidIt = onDidIt)
        }
    }
}

/** "I did it" (full width, or a compact pill at the end on Sapling) or the chore's state. */
@Composable
private fun ColumnScope.TodayAction(
    chore: TodayChoreUi,
    reward: String,
    submitting: Boolean,
    compact: Boolean,
    onDidIt: () -> Unit,
) {
    when (chore.status) {
        OccurrenceStatus.TO_DO -> {
            val description = stringResource(R.string.feature_chores_did_it_described, chore.title)
            val button = Modifier.semantics { contentDescription = description }
            SproutButton(
                text = stringResource(R.string.feature_chores_did_it),
                onClick = onDidIt,
                enabled = !submitting,
                icon = SproutIcons.Check,
                modifier = if (compact) button.align(Alignment.End) else button.fillMaxWidth(),
            )
        }

        OccurrenceStatus.WAITING -> Status(stringResource(R.string.feature_chores_waiting), StatusTone.Waiting)

        OccurrenceStatus.DONE -> Status(stringResource(R.string.feature_chores_done_reward, reward), StatusTone.Done)

        OccurrenceStatus.NOT_ACCEPTED -> Status(
            chore.rejectionReason?.let { stringResource(R.string.feature_chores_not_accepted_reason, it) }
                ?: stringResource(R.string.feature_chores_not_accepted),
            StatusTone.NotAccepted,
        )
    }
}

@Composable
private fun Detail(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Status(text: String, tone: StatusTone) {
    StatusPill(text = text, tone = tone, style = MaterialTheme.typography.labelLarge)
}

/** The next seven days in one white card: a row per chore with a hairline between rows. */
@Composable
private fun UpcomingCard(state: KidTodayUiState.Ready, formatter: MoneyFormatter) {
    SproutCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        state.upcoming.forEachIndexed { index, chore ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ListRow(
                pictogram = chore.pictogram,
                title = chore.title,
                detail = relativeDueLabel(chore.dueOn, state.date),
                trailing = { modifier ->
                    RewardText(
                        reward = chore.reward,
                        formatter = formatter,
                        tier = state.tier,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.End,
                        modifier = modifier,
                    )
                },
            )
        }
    }
}

/** Sapling's own chore ideas, waiting for a parent, in one white card. */
@Composable
private fun IdeasCard(proposals: List<ProposedChoreUi>, tier: KidUiTier, formatter: MoneyFormatter) {
    SproutCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        proposals.forEachIndexed { index, idea ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ListRow(
                pictogram = ChorePictogram.forTitle(idea.title),
                title = idea.title,
                detail = idea.reward.format(formatter, tier) + " · " +
                    stringResource(R.string.feature_chores_idea_waiting),
            )
        }
    }
}

/** A dense row inside a card: pictogram, title over [detail], and an optional [trailing] amount. Read as one. */
@Composable
private fun ListRow(
    pictogram: Pictogram,
    title: String,
    detail: String,
    trailing: (@Composable (Modifier) -> Unit)? = null,
) {
    val stacked = LocalDensity.current.fontScale >= STACK_FONT_SCALE
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md),
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = SproutSpacing.cardPadding, vertical = SproutSpacing.md - SproutSpacing.xs),
    ) {
        Pictogram(pictogram, size = RowPictogram)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Detail(detail)
            if (stacked) trailing?.invoke(Modifier)
        }
        if (!stacked) trailing?.invoke(Modifier)
    }
}

private val SproutPictogram: Dp = 48.dp
private val SaplingPictogram: Dp = 44.dp
private val RowPictogram: Dp = 40.dp
private val RewardMaxWidth: Dp = 100.dp

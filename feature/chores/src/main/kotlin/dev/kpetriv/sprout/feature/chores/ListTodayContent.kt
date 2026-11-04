package dev.kpetriv.sprout.feature.chores

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.ContentWidth
import dev.kpetriv.sprout.core.ui.MoneyFormatter
import dev.kpetriv.sprout.core.ui.TierScaledText
import dev.kpetriv.sprout.core.ui.TierTextRole
import dev.kpetriv.sprout.core.ui.rememberMoneyFormatter
import dev.kpetriv.sprout.core.ui.tierTextStyle

/**
 * Sprout (8 to 11) and Sapling (12+): today's chores with rewards and an "I did it" button that opens the
 * submission sheet, then the next seven days. Sapling also lists its chore ideas and can propose one.
 */
@Composable
internal fun ListTodayContent(
    state: KidTodayUiState.Ready,
    onEvent: (KidTodayEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tier = state.tier
    val formatter = rememberMoneyFormatter()
    ContentWidth(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "today") { Heading(stringResource(R.string.feature_chores_today), tier) }
            if (state.today.isEmpty()) {
                item(key = "today-empty") {
                    TierScaledText(stringResource(R.string.feature_chores_no_chores_today), TierTextRole.Body, tier)
                }
            }
            items(state.today, key = { "today:${it.choreId.value}" }) { chore ->
                TodayRow(
                    chore = chore,
                    tier = tier,
                    formatter = formatter,
                    submitting = chore.choreId in state.submitting,
                    onDidIt = { onEvent(KidTodayEvent.OpenSubmit(chore.choreId)) },
                )
            }
            item(key = "upcoming") {
                Heading(stringResource(R.string.feature_chores_upcoming), tier, Modifier.padding(top = 8.dp))
            }
            if (state.upcoming.isEmpty()) {
                item(key = "upcoming-empty") {
                    TierScaledText(stringResource(R.string.feature_chores_nothing_upcoming), TierTextRole.Body, tier)
                }
            }
            items(state.upcoming, key = { "next:${it.choreId.value}:${it.dueOn}" }) { chore ->
                UpcomingRow(chore, state, formatter)
            }
            if (state.canPropose) {
                item(key = "ideas") {
                    Heading(stringResource(R.string.feature_chores_my_ideas), tier, Modifier.padding(top = 8.dp))
                }
                items(state.proposals, key = { "idea:${it.choreId.value}" }) { idea ->
                    Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                        TierScaledText(idea.title, TierTextRole.Body, tier)
                        TierScaledText(
                            text = idea.reward.format(formatter, tier) + " · " +
                                stringResource(R.string.feature_chores_idea_waiting),
                            role = TierTextRole.Label,
                            tier = tier,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item(key = "propose") {
                    OutlinedButton(
                        onClick = { onEvent(KidTodayEvent.OpenPropose) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = SproutTheme.tierTokens.minTouchTarget),
                    ) { Text(stringResource(R.string.feature_chores_propose)) }
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String, tier: KidUiTier, modifier: Modifier = Modifier) {
    SectionHeading(text = text, modifier = modifier, style = tierTextStyle(TierTextRole.Title, tier))
}

@Composable
private fun TodayRow(
    chore: TodayChoreUi,
    tier: KidUiTier,
    formatter: MoneyFormatter,
    submitting: Boolean,
    onDidIt: () -> Unit,
) {
    val reward = chore.reward.format(formatter, tier)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = chore.emoji,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.clearAndSetSemantics { },
                )
                Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    TierScaledText(chore.title, TierTextRole.Title, tier)
                    chore.description?.let { TierScaledText(it, TierTextRole.Label, tier) }
                    TierScaledText(reward, TierTextRole.Label, tier, color = MaterialTheme.colorScheme.primary)
                }
            }
            when (chore.status) {
                OccurrenceStatus.TO_DO -> {
                    val description = stringResource(R.string.feature_chores_did_it_described, chore.title)
                    Button(
                        onClick = onDidIt,
                        enabled = !submitting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = SproutTheme.tierTokens.minTouchTarget)
                            .semantics { contentDescription = description },
                    ) { Text(stringResource(R.string.feature_chores_did_it)) }
                }

                OccurrenceStatus.WAITING -> Status("⏳ " + stringResource(R.string.feature_chores_waiting), tier)

                OccurrenceStatus.DONE -> Status(
                    "⭐ " + stringResource(R.string.feature_chores_done_reward, reward),
                    tier,
                )

                OccurrenceStatus.NOT_ACCEPTED -> Status(
                    chore.rejectionReason?.let { stringResource(R.string.feature_chores_not_accepted_reason, it) }
                        ?: stringResource(R.string.feature_chores_not_accepted),
                    tier,
                )
            }
        }
    }
}

@Composable
private fun Status(text: String, tier: KidUiTier) {
    TierScaledText(
        text = text,
        role = TierTextRole.Body,
        tier = tier,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun UpcomingRow(chore: UpcomingChoreUi, state: KidTodayUiState.Ready, formatter: MoneyFormatter) {
    val tier = state.tier
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
        Text(
            text = chore.emoji,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.clearAndSetSemantics {
            },
        )
        Column(modifier = Modifier.weight(1f)) {
            TierScaledText(chore.title, TierTextRole.Body, tier)
            TierScaledText(
                text = relativeDueLabel(chore.dueOn, state.date),
                role = TierTextRole.Label,
                tier = tier,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TierScaledText(chore.reward.format(formatter, tier), TierTextRole.Label, tier)
    }
}

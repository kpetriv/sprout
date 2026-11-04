package dev.kpetriv.sprout.feature.chores

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.Confetti
import dev.kpetriv.sprout.core.ui.MoneyFormatter
import dev.kpetriv.sprout.core.ui.ReadAloud
import dev.kpetriv.sprout.core.ui.TierScaledText
import dev.kpetriv.sprout.core.ui.TierTextRole
import dev.kpetriv.sprout.core.ui.contentWidth
import dev.kpetriv.sprout.core.ui.rememberMoneyFormatter

/**
 * Seedling (about 5 to 7): at most [KidTodayUiState.Ready.MAX_SEEDLING_CARDS] picture cards, each with an emoji,
 * the title, the reward in coins and one big "Done!" button. Finishing a chore plays confetti and, with a real
 * [ReadAloud], says "Well done!". Read-aloud buttons only appear when [readAloud] is not [ReadAloud.None].
 */
@Composable
internal fun SeedlingTodayContent(
    state: KidTodayUiState.Ready,
    onEvent: (KidTodayEvent) -> Unit,
    readAloud: ReadAloud,
    modifier: Modifier = Modifier,
) {
    val tier = KidUiTier.SEEDLING
    val formatter = rememberMoneyFormatter()
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .contentWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.seedlingCards.isEmpty()) {
                Text(
                    text = "🌈",
                    style = MaterialTheme.typography.displayLarge,
                    modifier = Modifier.clearAndSetSemantics {
                    },
                )
                TierScaledText(
                    text = stringResource(R.string.feature_chores_no_chores_today),
                    role = TierTextRole.Title,
                    tier = tier,
                    textAlign = TextAlign.Center,
                )
            }
            state.seedlingCards.forEach { chore ->
                SeedlingCard(
                    chore = chore,
                    formatter = formatter,
                    submitting = chore.choreId in state.submitting,
                    readAloud = readAloud,
                    onDone = { onEvent(KidTodayEvent.Done(chore.choreId)) },
                )
            }
        }
        val celebration = state.celebration
        if (celebration != null) {
            val spoken = stringResource(R.string.feature_chores_well_done, celebration)
            val speak = rememberUpdatedState(readAloud)
            LaunchedEffect(celebration) { if (speak.value !== ReadAloud.None) speak.value.speak(spoken) }
            Confetti(onFinished = { onEvent(KidTodayEvent.CelebrationShown) }, modifier = Modifier.fillMaxSize())
            Text(
                text = stringResource(R.string.feature_chores_celebration),
                modifier = Modifier
                    .align(Alignment.Center)
                    .semantics { liveRegion = LiveRegionMode.Assertive }
                    .padding(24.dp),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SeedlingCard(
    chore: TodayChoreUi,
    formatter: MoneyFormatter,
    submitting: Boolean,
    readAloud: ReadAloud,
    onDone: () -> Unit,
) {
    val tier = KidUiTier.SEEDLING
    val touch = SproutTheme.tierTokens.minTouchTarget
    val reward = chore.reward.format(formatter, tier)
    val done = chore.status == OccurrenceStatus.DONE
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (done) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = chore.emoji,
                    style = MaterialTheme.typography.displayMedium,
                    modifier = Modifier.clearAndSetSemantics { },
                )
                Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    TierScaledText(text = chore.title, role = TierTextRole.Title, tier = tier)
                    TierScaledText(text = reward, role = TierTextRole.Label, tier = tier)
                }
                if (readAloud !== ReadAloud.None) {
                    val spoken = stringResource(R.string.feature_chores_read_aloud_chore, chore.title, reward)
                    val description = stringResource(R.string.feature_chores_read_aloud_described, chore.title)
                    FilledTonalIconButton(
                        onClick = { readAloud.speak(spoken) },
                        modifier = Modifier
                            .sizeIn(minWidth = touch, minHeight = touch)
                            .semantics { contentDescription = description },
                    ) { Text(text = "🔊", modifier = Modifier.clearAndSetSemantics { }) }
                }
            }
            when (chore.status) {
                OccurrenceStatus.TO_DO -> Button(
                    onClick = onDone,
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = touch),
                ) {
                    TierScaledText(
                        text = stringResource(R.string.feature_chores_done_button),
                        role = TierTextRole.Title,
                        tier = tier,
                    )
                }

                OccurrenceStatus.WAITING -> StatusLine("⏳", stringResource(R.string.feature_chores_waiting))

                OccurrenceStatus.DONE -> StatusLine("⭐", stringResource(R.string.feature_chores_done_reward, reward))

                OccurrenceStatus.NOT_ACCEPTED -> StatusLine("🙂", stringResource(R.string.feature_chores_not_accepted))
            }
        }
    }
}

@Composable
private fun StatusLine(glyph: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(text = glyph, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clearAndSetSemantics { })
        TierScaledText(text = text, role = TierTextRole.Body, tier = KidUiTier.SEEDLING)
    }
}

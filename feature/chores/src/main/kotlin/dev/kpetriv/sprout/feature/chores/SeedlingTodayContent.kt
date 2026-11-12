package dev.kpetriv.sprout.feature.chores

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.components.CardMeaning
import dev.kpetriv.sprout.core.designsystem.components.Pictogram
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutCard
import dev.kpetriv.sprout.core.designsystem.components.accentTint
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.STACK_FONT_SCALE
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.Confetti
import dev.kpetriv.sprout.core.ui.MoneyFormatter
import dev.kpetriv.sprout.core.ui.ReadAloud
import dev.kpetriv.sprout.core.ui.contentWidth
import dev.kpetriv.sprout.core.ui.rememberMoneyFormatter

/**
 * Seedling (about 5 to 7), the "Morning garden" chores of the render: "Today", then at most
 * [KidTodayUiState.Ready.MAX_SEEDLING_CARDS] big white cards, each with a large [Pictogram], the title, the reward in
 * Botanical in the display face and one giant "I did it" pill that submits in one tap. Finishing a chore plays
 * confetti and, with a real [ReadAloud], says "Well done!". Read-aloud buttons only appear when [readAloud] is not
 * [ReadAloud.None].
 */
@Composable
internal fun SeedlingTodayContent(
    state: KidTodayUiState.Ready,
    onEvent: (KidTodayEvent) -> Unit,
    readAloud: ReadAloud,
    modifier: Modifier = Modifier,
) {
    val formatter = rememberMoneyFormatter()
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .contentWidth()
                .padding(horizontal = SproutSpacing.gutter, vertical = SproutSpacing.md),
            verticalArrangement = Arrangement.spacedBy(SproutSpacing.md),
        ) {
            if (state.seedlingCards.isEmpty()) {
                NothingToday()
            } else {
                SectionHeading(
                    text = stringResource(R.string.feature_chores_today),
                    style = MaterialTheme.typography.headlineMedium,
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
                    .padding(SproutSpacing.lg),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** A big seedling pictogram over "No chores today!". */
@Composable
private fun NothingToday() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = SproutSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.md),
    ) {
        Pictogram(Pictogram.SEEDLING, size = EmptyPictogram)
        Text(
            text = stringResource(R.string.feature_chores_no_chores_today),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
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
    val reward = chore.reward.format(formatter, tier)
    val stacked = LocalDensity.current.fontScale >= STACK_FONT_SCALE
    SproutCard(modifier = Modifier.fillMaxWidth(), meaning = CardMeaning.Content) {
        Column(verticalArrangement = Arrangement.spacedBy(SproutSpacing.md)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md),
            ) {
                if (!stacked) Pictogram(chore.pictogram, size = CardPictogram)
                Column(
                    modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(SproutSpacing.xs),
                ) {
                    if (stacked) Pictogram(chore.pictogram, size = CardPictogram)
                    Text(text = chore.title, style = MaterialTheme.typography.headlineSmall)
                    RewardText(chore.reward, formatter, tier, style = MaterialTheme.typography.titleLarge)
                }
            }
            // The chore's action (or state), with "Read to me" beside it.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm + SproutSpacing.xs),
            ) {
                Box(modifier = Modifier.weight(1f)) { ChoreAction(chore, reward, submitting, onDone) }
                if (readAloud !== ReadAloud.None) {
                    val spoken = stringResource(R.string.feature_chores_read_aloud_chore, chore.title, reward)
                    ReadAloudButton(
                        description = stringResource(R.string.feature_chores_read_aloud_described, chore.title),
                        onClick = { readAloud.speak(spoken) },
                    )
                }
            }
        }
    }
}

/** "I did it" (one tap submits on Seedling) or the chore's state. */
@Composable
private fun ChoreAction(chore: TodayChoreUi, reward: String, submitting: Boolean, onDone: () -> Unit) {
    when (chore.status) {
        OccurrenceStatus.TO_DO -> {
            val description = stringResource(R.string.feature_chores_did_it_described, chore.title)
            SproutButton(
                text = stringResource(R.string.feature_chores_did_it),
                onClick = onDone,
                enabled = !submitting,
                icon = SproutIcons.Check,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
            )
        }

        OccurrenceStatus.WAITING -> SeedlingStatus(
            stringResource(R.string.feature_chores_waiting),
            StatusTone.Waiting,
        )

        OccurrenceStatus.DONE -> SeedlingStatus(
            stringResource(R.string.feature_chores_done_reward, reward),
            StatusTone.Done,
        )

        OccurrenceStatus.NOT_ACCEPTED -> SeedlingStatus(
            stringResource(R.string.feature_chores_not_accepted),
            StatusTone.NotAccepted,
        )
    }
}

@Composable
private fun SeedlingStatus(text: String, tone: StatusTone) {
    StatusPill(
        text = text,
        tone = tone,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A Leaf-tint speaker circle at the tier's touch size, read as "Read <chore> to me". */
@Composable
private fun ReadAloudButton(description: String, onClick: () -> Unit) {
    val tint = CardMeaning.Money.accentTint()
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = tint.container,
        contentColor = tint.content,
        modifier = Modifier
            .size(SproutTheme.tierTokens.minTouchTarget)
            .semantics {
                role = Role.Button
                contentDescription = description
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(SproutIcons.VolumeUp, contentDescription = null, modifier = Modifier.size(SpeakerIcon))
        }
    }
}

private val CardPictogram = 72.dp
private val EmptyPictogram = 120.dp
private val SpeakerIcon = 32.dp

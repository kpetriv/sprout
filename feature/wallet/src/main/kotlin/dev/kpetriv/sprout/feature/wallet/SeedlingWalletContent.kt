package dev.kpetriv.sprout.feature.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.components.CardMeaning
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutButtonStyle
import dev.kpetriv.sprout.core.designsystem.components.SproutCard
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.ProvideKidTier
import dev.kpetriv.sprout.core.designsystem.theme.STACK_FONT_SCALE
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.BalanceJar
import dev.kpetriv.sprout.core.ui.MinutesSun
import dev.kpetriv.sprout.core.ui.ReadAloud
import dev.kpetriv.sprout.core.ui.SavingsStars
import dev.kpetriv.sprout.core.ui.TierScaledText
import dev.kpetriv.sprout.core.ui.TierTextRole
import dev.kpetriv.sprout.core.ui.contentWidth

/**
 * Seedling (about 5 to 7), the "Morning garden" render on `FULL` tints: the greeting and "Read to me", the coin jar
 * on a Leaf card, the screen-time sun on a Sky card (a button to the time bank in kid mode), the goal's stars and
 * two giant pills, Chores and Shop. No decimals, no minus signs, no history list for the kid.
 *
 * The read-aloud button only appears when a real [ReadAloud] is supplied (not [ReadAloud.None]), so a kid never
 * taps a button that does nothing. When a parent is looking ([WalletUiState.Ready.isParentView]), a compact
 * "Recent activity" list in real currency follows, so a parent can see a Seedling kid's history.
 */
@Composable
internal fun SeedlingWalletContent(
    state: WalletUiState.Ready,
    readAloud: ReadAloud,
    actions: WalletActions,
    modifier: Modifier = Modifier,
) {
    val tier = KidUiTier.SEEDLING
    val main = state.mainBalance
    val minutes = state.minutesBalance
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .contentWidth()
            .padding(horizontal = SproutSpacing.gutter, vertical = SproutSpacing.md),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.md),
    ) {
        Greeting(state, readAloud)
        if (main != null) {
            SproutCard(modifier = Modifier.fillMaxWidth(), meaning = CardMeaning.Money) {
                Text(
                    text = stringResource(R.string.feature_wallet_your_coins),
                    style = MaterialTheme.typography.labelLarge,
                )
                BalanceJar(
                    level = state.jarLevel,
                    countLabel = main.kidFormatted,
                    tier = tier,
                    contentDescription = stringResource(R.string.feature_wallet_jar_description, main.kidFormatted),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
        if (minutes != null) MinutesCard(minutes, onOpenTimeBank = actions.onOpenTimeBank)
        state.savingsGoal?.takeIf { it.title != null }?.let { goal ->
            SavingsGoalCard(goal) {
                SavingsStars(
                    title = stringResource(R.string.feature_wallet_saving_for, goal.title.orEmpty()),
                    filled = goal.stars,
                    tier = tier,
                )
            }
        }
        ChoresAndShopButtons(onOpenChores = actions.onOpenChores, onOpenShop = actions.onOpenShop)
        if (state.isParentView) ParentRecentActivity(state.events)
    }
}

/** "Hi Mia!" and, with a real [readAloud], "Read to me" beside it (under it when they do not fit one line). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Greeting(state: WalletUiState.Ready, readAloud: ReadAloud) {
    val main = state.mainBalance
    val minutes = state.minutesBalance
    val spoken = when {
        main != null && minutes != null ->
            stringResource(
                R.string.feature_wallet_read_aloud_coins_and_minutes,
                main.kidFormatted,
                minutes.kidFormatted,
            )

        main != null -> stringResource(R.string.feature_wallet_read_aloud_coins, main.kidFormatted)

        else -> null
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
    ) {
        TierScaledText(
            text = stringResource(R.string.feature_wallet_seedling_greeting, state.kid.firstName),
            role = TierTextRole.Title,
            tier = KidUiTier.SEEDLING,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
        if (spoken != null && readAloud !== ReadAloud.None) {
            SproutButton(
                text = stringResource(R.string.feature_wallet_read_aloud),
                onClick = { readAloud.speak(spoken) },
                style = SproutButtonStyle.Tertiary,
                icon = SproutIcons.VolumeUp,
            )
        }
    }
}

/**
 * Screen time as the sun on a Sky card. With [onOpenTimeBank] (kid mode) the whole card is one button to the time
 * bank, read as "1 h 20 min of screen time, Screen time, Tap to start your timer".
 */
@Composable
private fun MinutesCard(minutes: BalanceUi, onOpenTimeBank: (() -> Unit)?) {
    val sun: @Composable () -> Unit = {
        MinutesSun(
            minutes = minutes.amount.minorUnits,
            label = minutes.kidFormatted,
            tier = KidUiTier.SEEDLING,
            contentDescription = stringResource(R.string.feature_wallet_sun_description, minutes.kidFormatted),
            sunSize = SunSize,
        )
    }
    val words: @Composable (Modifier) -> Unit = { wordsModifier ->
        Column(modifier = wordsModifier, verticalArrangement = Arrangement.spacedBy(SproutSpacing.xs)) {
            Text(
                text = stringResource(R.string.feature_wallet_screen_time),
                style = MaterialTheme.typography.titleMedium,
            )
            if (onOpenTimeBank != null) {
                Text(
                    text = stringResource(R.string.feature_wallet_time_bank_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    SproutCard(modifier = Modifier.fillMaxWidth(), meaning = CardMeaning.Time, onClick = onOpenTimeBank) {
        if (LocalDensity.current.fontScale >= STACK_FONT_SCALE) {
            // Large text: the sun over the words, so no word breaks mid-way.
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(SproutSpacing.md),
            ) {
                sun()
                words(Modifier)
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md),
            ) {
                sun()
                words(Modifier.weight(1f))
                if (onOpenTimeBank != null) {
                    Box(
                        modifier = Modifier
                            .size(TimerCircle)
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(SproutIcons.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
    }
}

/**
 * The parent's view of a Seedling kid's history: Sapling-size rows with real amounts (no coin rounding), since the
 * reader is an adult. Kid-mode Seedling never shows it.
 */
@Composable
private fun ParentRecentActivity(events: List<EventUi>, modifier: Modifier = Modifier) {
    // Sapling is the plain, decimal presentation; the rows are for the parent, not the kid.
    val rowTier = KidUiTier.SAPLING
    ProvideKidTier(rowTier) {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm)) {
            SectionHeading(stringResource(R.string.feature_wallet_recent_activity), rowTier)
            HistoryCard(events, rowTier)
        }
    }
}

private val SunSize = 80.dp
private val TimerCircle = 48.dp

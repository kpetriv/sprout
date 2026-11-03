package dev.kpetriv.sprout.feature.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.theme.ProvideKidTier
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.BalanceJar
import dev.kpetriv.sprout.core.ui.MinutesSun
import dev.kpetriv.sprout.core.ui.ReadAloud
import dev.kpetriv.sprout.core.ui.SavingsStars
import dev.kpetriv.sprout.core.ui.TierScaledText
import dev.kpetriv.sprout.core.ui.TierTextRole

/**
 * Seedling (about 5 to 7): a coin jar with a whole number, a sun for screen time, two big picture buttons and a
 * read-aloud button. No decimals, no minus signs, no history list for the kid.
 *
 * The read-aloud button only appears when a real [ReadAloud] is supplied (not [ReadAloud.None]), so a kid never
 * taps a button that does nothing. When a parent is looking ([WalletUiState.Ready.isParentView]), a compact
 * "Recent activity" list in real currency follows, so a parent can see a Seedling kid's history.
 *
 * When the kid has a savings goal, a "Saving for ..." star row follows the sun.
 *
 * @param onOpenChores the big Chores button; the app navigates to the kid's chores.
 * @param onOpenShop the big Shop button; the app navigates to [ShopRoute].
 */
@Composable
internal fun SeedlingWalletContent(
    state: WalletUiState.Ready,
    readAloud: ReadAloud,
    modifier: Modifier = Modifier,
    onOpenChores: () -> Unit = {},
    onOpenShop: () -> Unit = {},
) {
    val tier = KidUiTier.SEEDLING
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
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        TierScaledText(
            text = stringResource(R.string.feature_wallet_seedling_greeting, state.kid.firstName),
            role = TierTextRole.Title,
            tier = tier,
            textAlign = TextAlign.Center,
        )
        if (main != null) {
            BalanceJar(
                level = state.jarLevel,
                countLabel = main.kidFormatted,
                tier = tier,
                contentDescription = stringResource(R.string.feature_wallet_jar_description, main.kidFormatted),
            )
        }
        if (minutes != null) {
            MinutesSun(
                minutes = minutes.amount.minorUnits,
                label = minutes.kidFormatted,
                tier = tier,
                contentDescription = stringResource(R.string.feature_wallet_sun_description, minutes.kidFormatted),
            )
        }
        state.savingsGoal?.let { goal ->
            goal.title?.let { title ->
                SavingsStars(
                    title = stringResource(R.string.feature_wallet_saving_for, title),
                    filled = goal.stars,
                    tier = tier,
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BigPictureButton(
                glyph = EventCategory.CHORES.icon,
                label = stringResource(R.string.feature_wallet_chores),
                onClick = onOpenChores,
                modifier = Modifier.weight(1f),
            )
            BigPictureButton(
                glyph = EventCategory.SHOP.icon,
                label = stringResource(R.string.feature_wallet_shop),
                onClick = onOpenShop,
                modifier = Modifier.weight(1f),
            )
        }
        if (spoken != null && readAloud !== ReadAloud.None) {
            FilledTonalButton(
                onClick = { readAloud.speak(spoken) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SproutTheme.tierTokens.minTouchTarget),
            ) {
                Text(
                    text = "🔊",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.clearAndSetSemantics { },
                )
                Text(
                    text = stringResource(R.string.feature_wallet_read_aloud),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        if (state.isParentView) ParentRecentActivity(state.events)
    }
}

/**
 * The parent's view of a Seedling kid's history: standard-size rows with real amounts (no coin rounding), since the
 * reader is an adult. Kid-mode Seedling never shows it.
 */
@Composable
private fun ParentRecentActivity(events: List<EventUi>, modifier: Modifier = Modifier) {
    // Sapling is the plain, decimal presentation; the rows are for the parent, not the kid.
    val rowTier = KidUiTier.SAPLING
    ProvideKidTier(rowTier) {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionHeading(stringResource(R.string.feature_wallet_recent_activity), rowTier)
            if (events.isEmpty()) {
                EmptyHistory(rowTier)
            } else {
                events.forEach { EventRow(it, rowTier) }
            }
        }
    }
}

/** A tall button with a picture glyph over a word; at least the tier's touch target (72dp on Seedling). */
@Composable
private fun BigPictureButton(glyph: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = SproutTheme.tierTokens.minTouchTarget * 2),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Decorative: the word below names the button.
            Text(
                text = glyph,
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.clearAndSetSemantics {
                },
            )
            Text(text = label, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        }
    }
}

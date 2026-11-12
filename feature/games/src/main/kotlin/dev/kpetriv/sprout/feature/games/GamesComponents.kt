package dev.kpetriv.sprout.feature.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.games.Difficulty
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.games.TablesSetup
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.MoneyFormatter

@Composable
internal fun LoadingContent(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.feature_games_loading)
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = description })
    }
}

@Composable
internal fun ErrorContent(
    text: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    action: String = stringResource(R.string.feature_games_retry),
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Button(onClick = onRetry) { Text(action) }
    }
}

@Composable
internal fun gameName(kind: GameKind): String = stringResource(
    when (kind) {
        GameKind.ARITHMETIC -> R.string.feature_games_kind_arithmetic
        GameKind.MEMORY -> R.string.feature_games_kind_memory
        GameKind.WORDS -> R.string.feature_games_kind_words
        GameKind.TABLES -> R.string.feature_games_kind_tables
    },
)

/**
 * The line under a game's name (the hub tile, the start card). Times tables show the kid's tables, "3, 4, 6" or
 * "Mix" ([setup], the tier's default when null).
 */
@Composable
internal fun gameDescription(kind: GameKind, tier: KidUiTier, setup: TablesSetup? = null): String {
    if (kind == GameKind.TABLES) return tablesLabel(setup ?: TablesSetup.defaultFor(Difficulty.forTier(tier)))
    val seedling = tier == KidUiTier.SEEDLING
    return stringResource(
        when (kind) {
            GameKind.ARITHMETIC -> if (seedling) {
                R.string.feature_games_kind_arithmetic_seedling_description
            } else {
                R.string.feature_games_kind_arithmetic_description
            }

            GameKind.MEMORY -> if (seedling) {
                R.string.feature_games_kind_memory_seedling_description
            } else {
                R.string.feature_games_kind_memory_description
            }

            GameKind.WORDS, GameKind.TABLES -> R.string.feature_games_kind_words_description
        },
    )
}

/**
 * Game credit for [tier]: Seedling counts minor units named for the currency ("23 pennies", D-M5-10), so a few pence
 * never read as the wallet's whole-pound "coins"; Sprout and Sapling get the currency format ("£0.23").
 */
@Composable
internal fun creditAmount(money: Money, currency: Currency.Fiat, tier: KidUiTier, formatter: MoneyFormatter): String {
    val minor = Money(money.minorUnits.coerceAtLeast(0))
    return if (tier == KidUiTier.SEEDLING) {
        formatter.formatMinorUnits(minor, currency)
    } else {
        formatter.format(minor, currency)
    }
}

/** A credit just earned for [tier]: "+8 pennies" (Seedling, D-M5-10) or "+£0.08". */
@Composable
internal fun creditEarned(money: Money, currency: Currency.Fiat, tier: KidUiTier, formatter: MoneyFormatter): String {
    val minor = Money(money.minorUnits.coerceAtLeast(0))
    return if (tier == KidUiTier.SEEDLING) {
        formatter.formatMinorUnits(minor, currency, signed = true)
    } else {
        formatter.format(minor, currency, signed = true)
    }
}

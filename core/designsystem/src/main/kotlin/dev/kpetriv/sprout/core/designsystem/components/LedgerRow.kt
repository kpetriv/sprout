package dev.kpetriv.sprout.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.SproutPreviewFrame
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.designsystem.theme.SproutTint
import dev.kpetriv.sprout.core.designsystem.theme.amount
import dev.kpetriv.sprout.core.model.KidUiTier

/** What a [LedgerRow] records, which sets its icon circle and amount colours (M9 §3). */
enum class LedgerRowKind {
    /** Money in (chore rewards, allowance, interest): Leaf circle, Botanical amount. */
    Credit,

    /** Screen time in or out: Sky circle, Sky (tertiary) amount. */
    Time,

    /** Game and shop rewards: Honey circle, Botanical amount. */
    Reward,

    /** Money out (the shop): Error-tint circle (a muted brick in dark), Ink amount. */
    Spending,

    /** Moves between the kid's own pots (to savings): Linen circle, Ink-soft amount. */
    Transfer,
}

/** Defaults for [LedgerRow]. */
object LedgerRowDefaults {
    val MinHeight: Dp = 56.dp
    val IconCircleSize: Dp = 40.dp
    val IconSize: Dp = 20.dp
    val VerticalPadding: Dp = 12.dp

    /** The icon circle's tint for [kind]. */
    @Composable
    @ReadOnlyComposable
    fun circleTint(kind: LedgerRowKind): SproutTint {
        val scheme = MaterialTheme.colorScheme
        return when (kind) {
            LedgerRowKind.Credit -> SproutTint(scheme.primaryContainer, scheme.onPrimaryContainer)
            LedgerRowKind.Time -> SproutTint(scheme.tertiaryContainer, scheme.onTertiaryContainer)
            LedgerRowKind.Reward -> SproutTint(scheme.secondaryContainer, scheme.onSecondaryContainer)
            LedgerRowKind.Spending -> SproutTheme.colors.spendingTint
            LedgerRowKind.Transfer -> SproutTint(scheme.surfaceContainerLow, scheme.onSurface)
        }
    }

    /** The amount's colour for [kind]; every one keeps 4.5:1 on a white card. */
    @Composable
    @ReadOnlyComposable
    fun amountColor(kind: LedgerRowKind): Color {
        val scheme = MaterialTheme.colorScheme
        return when (kind) {
            LedgerRowKind.Credit, LedgerRowKind.Reward -> scheme.primary
            LedgerRowKind.Time -> scheme.tertiary
            LedgerRowKind.Spending -> scheme.onSurface
            LedgerRowKind.Transfer -> scheme.onSurfaceVariant
        }
    }
}

/**
 * One ledger line (M9 §3): at least 56 dp, a 40 dp icon circle tinted by [kind], [title] in bold over [detail] in
 * Ink soft, and the signed [amount] in the display face on the right, coloured by [kind]. A hairline sits under it
 * unless [divider] is false (the last row of a card). No chevron. Read as one node; [amount] should already carry its
 * sign in words or symbols, since the colour alone never does.
 */
@Composable
fun LedgerRow(
    icon: ImageVector,
    kind: LedgerRowKind,
    title: String,
    detail: String?,
    amount: String,
    modifier: Modifier = Modifier,
    divider: Boolean = true,
) {
    val tint = LedgerRowDefaults.circleTint(kind)
    Column(modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = LedgerRowDefaults.MinHeight)
                .padding(horizontal = SproutSpacing.cardPadding, vertical = LedgerRowDefaults.VerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md),
        ) {
            Box(
                modifier = Modifier.size(LedgerRowDefaults.IconCircleSize).background(tint.container, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint.content,
                    modifier = Modifier.size(LedgerRowDefaults.IconSize),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = amount,
                style = MaterialTheme.typography.amount,
                color = LedgerRowDefaults.amountColor(kind),
            )
        }
        if (divider) HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** One row of each [LedgerRowKind] in a card, for previews and goldens. */
@Composable
internal fun LedgerRowSample() {
    SproutCard(modifier = Modifier.fillMaxWidth().padding(SproutSpacing.gutter), contentPadding = PaddingValues()) {
        LedgerRow(SproutIcons.AutoAwesome, LedgerRowKind.Credit, "Chore reward", "Cleared garden path", "+£1.50")
        LedgerRow(SproutIcons.ShoppingBag, LedgerRowKind.Spending, "Shop", "Corner shop stickers", "−£3.99")
        LedgerRow(SproutIcons.Timer, LedgerRowKind.Time, "Screen time used", "After school", "−30 min")
        LedgerRow(SproutIcons.Savings, LedgerRowKind.Transfer, "Moved to savings", "Bike pot", "£2.00")
        LedgerRow(SproutIcons.EmojiEvents, LedgerRowKind.Reward, "Game reward", null, "+£0.20", divider = false)
    }
}

@Preview(name = "Seedling", showBackground = true)
@Composable
private fun LedgerRowSeedlingPreview() = SproutPreviewFrame(KidUiTier.SEEDLING) { LedgerRowSample() }

@Preview(name = "Sprout", showBackground = true)
@Composable
private fun LedgerRowSproutPreview() = SproutPreviewFrame(KidUiTier.SPROUT) { LedgerRowSample() }

@Preview(name = "Sapling", showBackground = true)
@Composable
private fun LedgerRowSaplingPreview() = SproutPreviewFrame(KidUiTier.SAPLING) { LedgerRowSample() }

@Preview(name = "Parent, dark", showBackground = true)
@Composable
private fun LedgerRowParentDarkPreview() = SproutPreviewFrame(tier = null, darkTheme = true) { LedgerRowSample() }

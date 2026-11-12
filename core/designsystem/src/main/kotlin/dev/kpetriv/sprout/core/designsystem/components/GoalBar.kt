@file:Suppress("MatchingDeclarationName") // Named after its composable, like Material's files.

package dev.kpetriv.sprout.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.R
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.ColourLevel
import dev.kpetriv.sprout.core.designsystem.theme.OutfitFontFamily
import dev.kpetriv.sprout.core.designsystem.theme.STACK_FONT_SCALE
import dev.kpetriv.sprout.core.designsystem.theme.SproutPreviewFrame
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.designsystem.theme.SproutTint
import dev.kpetriv.sprout.core.model.KidUiTier

/** Defaults for [GoalBar]. */
object GoalBarDefaults {
    val TrackHeight: Dp = 14.dp
    val IconCircleSize: Dp = 48.dp
    val IconSize: Dp = 24.dp
}

/**
 * A savings goal (M9 §3, the goal card of the renders): a Sky-tint icon circle with [title] and [caption]
 * ("£27.50 to go"), a "31% saved" chip on Leaf tint, a 14 dp Moss-grey track with a Botanical fill, then
 * "£12.50 saved" in Botanical and "Target £40.00" in Ink soft (Outline misses 4.5:1). [saved] and [target] are
 * formatted amounts; [percent] is clamped to 0..100. Put it in a `SproutCard(meaning = CardMeaning.Goal)`; on
 * [ColourLevel.FULL] that card is Sky tint, so the icon circle turns white.
 *
 * On [ColourLevel.FULL] (Seedling) with [stars] given, the stars replace the track and the figures: pass core/ui's
 * `SavingsStars` there (this module cannot depend on core/ui).
 */
@Composable
fun GoalBar(
    title: String,
    saved: String,
    target: String,
    percent: Int,
    caption: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector = SproutIcons.Savings,
    stars: (@Composable () -> Unit)? = null,
) {
    val clamped = percent.coerceIn(0, PERCENT)
    val full = SproutTheme.tierTokens.colourLevel == ColourLevel.FULL
    if (stars != null && full) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { stars() }
        return
    }
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md),
        ) {
            // On FULL the goal card itself is Sky tint, so the circle turns white to stay visible.
            val tint = if (full) {
                SproutTint(scheme.surfaceContainerLowest, scheme.onTertiaryContainer)
            } else {
                CardMeaning.Goal.accentTint()
            }
            Box(
                modifier = Modifier.size(GoalBarDefaults.IconCircleSize).background(tint.container, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint.content, modifier = Modifier.size(24.dp))
            }
            // At large font sizes the chip goes under the title, so the title never breaks mid-word.
            val stacked = LocalDensity.current.fontScale >= STACK_FONT_SCALE
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (stacked) SproutSpacing.xs else 0.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = scheme.onSurface,
                )
                if (caption != null) {
                    Text(text = caption, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
                if (stacked) PercentChip(clamped)
            }
            if (!stacked) PercentChip(clamped)
        }
        GoalTrack(fraction = clamped / PERCENT.toFloat())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = SproutIcons.CheckCircle,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.padding(end = SproutSpacing.xs + 2.dp).size(18.dp),
            )
            Text(
                text = stringResource(R.string.core_designsystem_goal_saved, saved),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.core_designsystem_goal_target, target),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** The "31% saved" chip on Leaf tint. */
@Composable
private fun PercentChip(percent: Int) {
    val money = CardMeaning.Money.accentTint()
    Text(
        text = stringResource(R.string.core_designsystem_goal_percent_saved, percent),
        style = MaterialTheme.typography.labelLarge,
        color = money.content,
        modifier = Modifier
            .background(money.container, CircleShape)
            .padding(horizontal = SproutSpacing.md - SproutSpacing.xs, vertical = SproutSpacing.xs + 2.dp),
    )
}

@Composable
private fun GoalTrack(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(GoalBarDefaults.TrackHeight)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

private const val PERCENT = 100

/** The bike goal of the renders in a goal card, for previews and goldens. */
@Composable
internal fun GoalBarSample() {
    SproutCard(modifier = Modifier.fillMaxWidth().padding(SproutSpacing.gutter), meaning = CardMeaning.Goal) {
        GoalBar(
            title = "Saving for a new bike",
            saved = "£12.50",
            target = "£40.00",
            percent = 31,
            caption = "£27.50 to go",
        )
    }
}

@Preview(name = "Seedling", showBackground = true)
@Composable
private fun GoalBarSeedlingPreview() = SproutPreviewFrame(KidUiTier.SEEDLING) { GoalBarSample() }

@Preview(name = "Sprout", showBackground = true)
@Composable
private fun GoalBarSproutPreview() = SproutPreviewFrame(KidUiTier.SPROUT) { GoalBarSample() }

@Preview(name = "Sapling", showBackground = true)
@Composable
private fun GoalBarSaplingPreview() = SproutPreviewFrame(KidUiTier.SAPLING) { GoalBarSample() }

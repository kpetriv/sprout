package dev.kpetriv.sprout.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlin.math.roundToInt

/**
 * Progress towards a savings goal: [title], a bar at [progress] (0..1, clamped; ignored when [targetText] is null),
 * and "saved / target" underneath. Without a target the bar stays empty and says so to accessibility services.
 */
@Composable
fun SavingsGoalBar(
    title: String,
    savedText: String,
    targetText: String?,
    progress: Float,
    tier: KidUiTier,
    modifier: Modifier = Modifier,
) {
    val target = if (targetText == null) 0f else progress.coerceIn(0f, 1f)
    val animated by animateFloatAsState(targetValue = target, label = "savingsProgress")
    val state = if (targetText == null) {
        stringResource(R.string.core_ui_savings_no_target)
    } else {
        val percent = (target * PERCENT_FULL).roundToInt()
        pluralStringResource(R.plurals.core_ui_savings_progress, percent, percent)
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TierScaledText(text = title, role = TierTextRole.Title, tier = tier)
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier
                .fillMaxWidth()
                .height(if (tier == KidUiTier.SAPLING) 8.dp else 14.dp)
                .semantics { stateDescription = state },
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TierScaledText(text = savedText, role = TierTextRole.Label, tier = tier)
            TierScaledText(text = targetText ?: state, role = TierTextRole.Label, tier = tier)
        }
    }
}

private const val PERCENT_FULL = 100

@Preview(name = "Seedling", showBackground = true)
@Composable
private fun SavingsGoalBarSeedlingPreview() = TierPreviewFrame(KidUiTier.SEEDLING) {
    SavingsGoalBar("Bike", "7 coins", "20 coins", 0.35f, KidUiTier.SEEDLING)
}

@Preview(name = "Sprout", showBackground = true)
@Composable
private fun SavingsGoalBarSproutPreview() = TierPreviewFrame(KidUiTier.SPROUT) {
    SavingsGoalBar("New bike", "£12.50", "£40.00", 0.31f, KidUiTier.SPROUT)
}

@Preview(name = "Sapling, no target", showBackground = true)
@Composable
private fun SavingsGoalBarSaplingPreview() = TierPreviewFrame(KidUiTier.SAPLING) {
    SavingsGoalBar("Savings", "£85.20", null, 0f, KidUiTier.SAPLING)
}

package dev.kpetriv.sprout.core.designsystem.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.model.KidUiTier

/**
 * [content] as a kid at [tier] (or on a parent screen when [tier] is null) inside [SproutTheme] on the cream
 * background. For previews in this module.
 */
@Composable
internal fun SproutPreviewFrame(tier: KidUiTier?, darkTheme: Boolean = false, content: @Composable () -> Unit) {
    SproutTheme(darkTheme = darkTheme, reducedMotion = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            if (tier == null) content() else ProvideKidTier(tier, content)
        }
    }
}

@Composable
private fun TierTokensSample(tier: KidUiTier) {
    SproutPreviewFrame(tier) {
        val tokens = SproutTheme.tierTokens
        Column(
            modifier = Modifier.padding(SproutSpacing.md),
            verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
        ) {
            Text(text = SproutTheme.kidTier.name, style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "type x${tokens.typeScale}, touch ${tokens.minTouchTarget}, " +
                    "corners ${tokens.cornerRadius}, colour ${tokens.colourLevel}, button ${tokens.buttonHeight}",
                style = MaterialTheme.typography.bodyMedium,
            )
            SproutButton(text = "Done!", onClick = {})
        }
    }
}

@Preview(name = "Seedling", showBackground = true)
@Composable
private fun SeedlingTokensPreview() = TierTokensSample(KidUiTier.SEEDLING)

@Preview(name = "Sprout", showBackground = true)
@Composable
private fun SproutTokensPreview() = TierTokensSample(KidUiTier.SPROUT)

@Preview(name = "Sapling", showBackground = true)
@Composable
private fun SaplingTokensPreview() = TierTokensSample(KidUiTier.SAPLING)

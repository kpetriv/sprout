package dev.kpetriv.sprout.core.designsystem.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.model.KidUiTier

@Composable
private fun TierTokensSample(tier: KidUiTier) {
    SproutTheme {
        ProvideKidTier(tier) {
            val tokens = SproutTheme.tierTokens
            Surface {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = SproutTheme.kidTier.name, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "type x${tokens.typeScale}, touch ${tokens.minTouchTarget}, " +
                            "corners ${tokens.cornerRadius}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = {},
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.defaultMinSize(minHeight = tokens.minTouchTarget),
                    ) {
                        Text(text = "Done!")
                    }
                }
            }
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

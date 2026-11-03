package dev.kpetriv.sprout.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.theme.ProvideKidTier
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier

/** Preview frame: [SproutTheme] plus [ProvideKidTier] for [tier], on a surface. */
@Composable
internal fun TierPreviewFrame(tier: KidUiTier, content: @Composable () -> Unit) {
    SproutTheme {
        ProvideKidTier(tier) {
            Surface {
                Box(modifier = Modifier.padding(16.dp)) { content() }
            }
        }
    }
}

package dev.kpetriv.sprout.core.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import dev.kpetriv.sprout.core.designsystem.theme.ProvideKidTier
import dev.kpetriv.sprout.core.model.KidUiTier

/**
 * The type style for [role] on [tier]. Younger tiers use bigger slots on top of the tier's own type scale
 * (applied by [ProvideKidTier]), so a Seedling balance is a display-size number while Sapling keeps headline size.
 * All sizes are in sp, so they follow the system font scale.
 */
@Composable
@ReadOnlyComposable
fun tierTextStyle(role: TierTextRole, tier: KidUiTier): TextStyle {
    val type = MaterialTheme.typography
    val (seedling, sprout, sapling) = when (role) {
        TierTextRole.Balance -> Triple(type.displayMedium, type.displaySmall, type.headlineMedium)
        TierTextRole.Title -> Triple(type.headlineSmall, type.titleLarge, type.titleLarge)
        TierTextRole.Body -> Triple(type.titleLarge, type.bodyLarge, type.bodyLarge)
        TierTextRole.Label -> Triple(type.titleMedium, type.labelLarge, type.labelMedium)
    }
    return when (tier) {
        KidUiTier.SEEDLING -> seedling
        KidUiTier.SPROUT -> sprout
        KidUiTier.SAPLING -> sapling
    }
}

/** [Text] in the [tierTextStyle] for [role] and [tier]. */
@Composable
fun TierScaledText(
    text: String,
    role: TierTextRole,
    tier: KidUiTier,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        style = tierTextStyle(role, tier),
        color = if (color == Color.Unspecified) LocalContentColor.current else color,
        textAlign = textAlign,
        modifier = modifier,
    )
}

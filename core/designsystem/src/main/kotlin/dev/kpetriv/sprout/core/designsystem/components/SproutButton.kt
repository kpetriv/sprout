package dev.kpetriv.sprout.core.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.SproutPreviewFrame
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.designsystem.theme.pressScale
import dev.kpetriv.sprout.core.model.KidUiTier

/** The three pill styles of M9 §3. */
enum class SproutButtonStyle {
    /** Botanical fill, white text: the one main action on a screen or card. */
    Primary,

    /** Honey tint, Honey-deep text, honey border: a playful second action (Shop, presets). */
    Secondary,

    /** Linen fill, hairline border, Ink text: quiet actions (Review, Cancel). */
    Tertiary,
}

/** Defaults for [SproutButton]. */
object SproutButtonDefaults {
    /** Kid tiers with buttons at least this tall get the larger label. */
    val LargeHeight: Dp = 64.dp

    /** Icon size beside the label. */
    val IconSize: Dp = 24.dp

    /** The label style for the current tier: titleMedium bold on Seedling and Sprout, labelLarge elsewhere. */
    @Composable
    fun textStyle(): TextStyle = if (SproutTheme.tierTokens.buttonHeight >= LargeHeight) {
        MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
    } else {
        MaterialTheme.typography.labelLarge
    }
}

/**
 * A Sprout pill button (M9 §3): [style] picks the colours, the height comes from the tier
 * ([dev.kpetriv.sprout.core.designsystem.theme.TierTokens.buttonHeight]: 72 / 64 / 56 dp on the kid tiers, 48 dp on
 * parent screens), an optional [icon] sits left of the label (never icon-only), and it shrinks to 0.96 while pressed
 * (not under reduced motion).
 */
@Composable
fun SproutButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: SproutButtonStyle = SproutButtonStyle.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scheme = MaterialTheme.colorScheme
    val sprout = SproutTheme.colors
    val (container, content, border) = when (style) {
        SproutButtonStyle.Primary -> Triple(
            if (pressed) sprout.primaryPressed else scheme.primary,
            scheme.onPrimary,
            null,
        )

        SproutButtonStyle.Secondary -> Triple(
            scheme.secondaryContainer,
            scheme.onSecondaryContainer,
            BorderStroke(1.dp, sprout.honeyBorder),
        )

        SproutButtonStyle.Tertiary -> Triple(
            scheme.surfaceContainerLow,
            scheme.onSurface,
            BorderStroke(1.dp, scheme.outlineVariant),
        )
    }
    Button(
        onClick = onClick,
        modifier = modifier
            .pressScale(interactionSource)
            .heightIn(min = SproutTheme.tierTokens.buttonHeight),
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        border = if (enabled) border else null,
        contentPadding = PaddingValues(horizontal = SproutSpacing.lg, vertical = SproutSpacing.sm),
        interactionSource = interactionSource,
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(SproutButtonDefaults.IconSize))
            Spacer(modifier = Modifier.width(SproutSpacing.sm))
        }
        Text(text = text, style = SproutButtonDefaults.textStyle(), textAlign = TextAlign.Center)
    }
}

/** The three styles, with and without an icon, and a disabled pill, for previews and goldens. */
@Composable
internal fun SproutButtonSample() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(SproutSpacing.gutter),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.md),
    ) {
        SproutButton(text = "I did it", onClick = {}, icon = SproutIcons.Check, modifier = Modifier.fillMaxWidth())
        SproutButton(text = "Shop", onClick = {}, style = SproutButtonStyle.Secondary, icon = SproutIcons.ShoppingBag)
        SproutButton(text = "Review", onClick = {}, style = SproutButtonStyle.Tertiary)
        SproutButton(text = "Sign now", onClick = {}, enabled = false)
    }
}

@Preview(name = "Seedling", showBackground = true)
@Composable
private fun SproutButtonSeedlingPreview() = SproutPreviewFrame(KidUiTier.SEEDLING) { SproutButtonSample() }

@Preview(name = "Sprout", showBackground = true)
@Composable
private fun SproutButtonSproutPreview() = SproutPreviewFrame(KidUiTier.SPROUT) { SproutButtonSample() }

@Preview(name = "Sapling", showBackground = true)
@Composable
private fun SproutButtonSaplingPreview() = SproutPreviewFrame(KidUiTier.SAPLING) { SproutButtonSample() }

package dev.kpetriv.sprout.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutButtonStyle
import dev.kpetriv.sprout.core.designsystem.theme.LocalSproutColors
import dev.kpetriv.sprout.core.designsystem.theme.STACK_FONT_SCALE
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme

/** The Botanical save pill; it reads "Saving…" while [isSaving]. */
@Composable
internal fun SaveButton(isSaving: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SproutButton(
        text = stringResource(if (isSaving) R.string.feature_settings_saving else R.string.feature_settings_save),
        onClick = onClick,
        enabled = enabled && !isSaving,
        modifier = modifier,
    )
}

/** The quiet Linen pill for "Cancel" and the like. */
@Composable
internal fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    SproutButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        style = SproutButtonStyle.Tertiary,
        icon = icon,
        enabled = enabled,
    )
}

/** How a destructive [DestructiveButton] looks: a filled `error` pill, or a white pill with `error` text and border. */
internal enum class DestructiveStyle { Filled, Outlined }

/**
 * A `SproutButton` in the `error` colours (M9 §4: destructive buttons use `error`), for deleting, erasing, removing
 * and signing out. The pill keeps SproutButton's shape, height, press scale and icon placement; only its colours are
 * remapped onto the error roles for this one button. Filled, it darkens while pressed (error 15 % toward Ink); an
 * outlined one that is disabled keeps Material's neutral disabled look.
 */
@Composable
internal fun DestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: DestructiveStyle = DestructiveStyle.Filled,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val sproutStyle = if (style == DestructiveStyle.Filled) SproutButtonStyle.Primary else SproutButtonStyle.Tertiary
    val button = @Composable {
        SproutButton(
            text = text,
            onClick = onClick,
            modifier = modifier,
            style = sproutStyle,
            icon = icon,
            enabled = enabled,
        )
    }
    val remapped = when {
        style == DestructiveStyle.Filled -> scheme.copy(primary = scheme.error, onPrimary = scheme.onError)

        enabled -> scheme.copy(
            surfaceContainerLow = scheme.surfaceContainerLowest,
            onSurface = scheme.error,
            outlineVariant = scheme.error.copy(alpha = OUTLINE_ALPHA),
        )

        // Disabled and outlined: the neutral disabled pill, not a faded red one.
        else -> null
    }
    if (remapped == null) {
        button()
        return
    }
    val pressed = lerp(scheme.error, scheme.onSurface, PRESSED_TOWARD_INK)
    MaterialTheme(colorScheme = remapped) {
        CompositionLocalProvider(LocalSproutColors provides SproutTheme.colors.copy(primaryPressed = pressed)) {
            button()
        }
    }
}

private const val PRESSED_TOWARD_INK = 0.15f
private const val OUTLINE_ALPHA = 0.5f

/**
 * Buttons side by side, ending on the right; stacked full width from [STACK_FONT_SCALE] so no label breaks mid-word.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ButtonRow(
    modifier: Modifier = Modifier,
    alignment: Alignment.Horizontal = Alignment.End,
    content: @Composable () -> Unit,
) {
    if (LocalDensity.current.fontScale >= STACK_FONT_SCALE) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
            horizontalAlignment = Alignment.End,
        ) { content() }
    } else {
        FlowRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm, alignment),
            verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
        ) { content() }
    }
}

/** An `error` line read out when it appears. */
@Composable
internal fun ErrorText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

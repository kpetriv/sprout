package dev.kpetriv.sprout.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutButtonStyle
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.LocalSproutColors
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme

/* Onboarding's pieces on the Morning garden tokens (M9 §4: parent tokens and components). */

/** The sprout mark (M9 §8: Botanical on Leaf tint) above the welcome title. Decorative: the title names the app. */
@Composable
internal fun SproutMark(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(MarkSize).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = SproutIcons.Eco,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(MarkIconSize),
        )
    }
}

/**
 * A Primary `SproutButton` in the `error` colours (M9 §4: destructive buttons use `error`); only its colours are
 * remapped, for this one button. It darkens while pressed (error 15 % toward Ink).
 */
@Composable
internal fun DestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val sproutColors = SproutTheme.colors
    val pressed = lerp(scheme.error, scheme.onSurface, PRESSED_TOWARD_INK)
    MaterialTheme(colorScheme = scheme.copy(primary = scheme.error, onPrimary = scheme.onError)) {
        CompositionLocalProvider(LocalSproutColors provides sproutColors.copy(primaryPressed = pressed)) {
            SproutButton(
                text = text,
                onClick = onClick,
                modifier = modifier,
                style = SproutButtonStyle.Primary,
                enabled = enabled,
            )
        }
    }
}

/** Text fields on Linen with a hairline, Botanical when focused. */
@Composable
internal fun onboardingFieldColors(): TextFieldColors {
    val scheme = MaterialTheme.colorScheme
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = scheme.surfaceContainerLow,
        unfocusedContainerColor = scheme.surfaceContainerLow,
        disabledContainerColor = scheme.surfaceContainerLow,
        errorContainerColor = scheme.surfaceContainerLow,
        unfocusedBorderColor = scheme.outlineVariant,
        disabledBorderColor = scheme.outlineVariant,
        focusedBorderColor = scheme.primary,
    )
}

private const val PRESSED_TOWARD_INK = 0.15f
private val MarkSize = 72.dp
private val MarkIconSize = 40.dp

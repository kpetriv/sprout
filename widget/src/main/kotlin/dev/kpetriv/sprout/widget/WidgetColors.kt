package dev.kpetriv.sprout.widget

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProviders
import androidx.glance.material3.ColorProviders

/**
 * The widget's colours: Sprout's leaf green on a pale leaf card, light and dark (the values follow
 * `core/designsystem`'s palette, whose schemes are internal to that module). Glance derives the card
 * (`widgetBackground`) from `secondaryContainer`, so that slot holds the card's tint here.
 */
internal val SproutWidgetColors: ColorProviders = ColorProviders(
    light = lightColorScheme(
        primary = Color(0xFF2E7D32),
        onSurface = Color(0xFF1A1C19),
        onSurfaceVariant = Color(0xFF42493F),
        secondaryContainer = Color(0xFFCDE8C4),
    ),
    dark = darkColorScheme(
        primary = Color(0xFF8BD88F),
        onSurface = Color(0xFFE2E3DD),
        onSurfaceVariant = Color(0xFFC2C9BB),
        secondaryContainer = Color(0xFF233A24),
    ),
)

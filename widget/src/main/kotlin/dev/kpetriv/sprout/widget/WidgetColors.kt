package dev.kpetriv.sprout.widget

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider
import androidx.glance.color.ColorProviders
import androidx.glance.material3.ColorProviders
import androidx.glance.unit.ColorProvider

/**
 * The widget's colours, light and dark: Botanical text on a Leaf-tint card. The values follow `core/designsystem`'s
 * Morning garden scheme (M9 §2.1: `primary`, `primaryContainer` for the card, `onSurface`, `onSurfaceVariant`), whose
 * schemes are internal to that module, so they are repeated here. The card is not Glance's `widgetBackground`
 * (derived from `secondaryContainer` with its tone shifted +5 in light and -10 in dark, which turns the pale Leaf tint
 * white) but [SproutWidgetCard]; nothing reads the other roles, so they keep Material's defaults. The widget cannot
 * load Compose fonts, so it keeps Glance's text styles. The picker preview (`res/values/colors.xml` and its night
 * twin) uses the same values.
 */
internal val SproutWidgetColors: ColorProviders = ColorProviders(
    light = lightColorScheme(
        primary = Color(0xFF2A6B45),
        onSurface = Color(0xFF1A1C19),
        onSurfaceVariant = Color(0xFF5A635C),
    ),
    dark = darkColorScheme(
        primary = Color(0xFF8FD3A3),
        onSurface = Color(0xFFE4E7E0),
        onSurfaceVariant = Color(0xFFAEB7B0),
    ),
)

/** The widget card: Leaf tint (`primaryContainer`), exactly, in light and dark. */
internal val SproutWidgetCard: ColorProvider = ColorProvider(day = Color(0xFFE9F3EB), night = Color(0xFF1F4A30))

package dev.kpetriv.sprout.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private val Leaf = Color(0xFF2E7D32)
private val LeafLight = Color(0xFF8BD88F)
private val LeafContainer = Color(0xFFB7F0B1)
private val LeafContainerDark = Color(0xFF0F5223)
private val Sun = Color(0xFFF2A900)
private val SunDark = Color(0xFFFFCF5C)
private val SunContainer = Color(0xFFFFE08A)
private val SunContainerDark = Color(0xFF5C4300)
private val Sky = Color(0xFF00658E)
private val SkyDark = Color(0xFF8BCEFF)

// Sky's tonal containers (tones 90/10 light, 30/90 dark). Without them Material's default (pink) tertiary containers
// showed on the dashboard's household cards and the chores' "proposed" chips (D-REL-12).
private val SkyContainer = Color(0xFFC7E7FF)
private val OnSkyContainer = Color(0xFF001E2E)
private val SkyContainerDark = Color(0xFF004C6C)
private val Soil = Color(0xFF1A1C19)
private val Paper = Color(0xFFF8FBF2)

internal val SproutLightColors = lightColorScheme(
    primary = Leaf,
    onPrimary = Color.White,
    primaryContainer = LeafContainer,
    onPrimaryContainer = Color(0xFF002106),
    secondary = Sun,
    onSecondary = Color.Black,
    secondaryContainer = SunContainer,
    onSecondaryContainer = Color(0xFF261A00),
    tertiary = Sky,
    onTertiary = Color.White,
    tertiaryContainer = SkyContainer,
    onTertiaryContainer = OnSkyContainer,
    background = Paper,
    onBackground = Soil,
    surface = Paper,
    onSurface = Soil,
)

internal val SproutDarkColors = darkColorScheme(
    primary = LeafLight,
    onPrimary = Color(0xFF003910),
    primaryContainer = LeafContainerDark,
    onPrimaryContainer = LeafContainer,
    secondary = SunDark,
    onSecondary = Color(0xFF3F2E00),
    secondaryContainer = SunContainerDark,
    onSecondaryContainer = SunContainer,
    tertiary = SkyDark,
    onTertiary = Color(0xFF00344C),
    tertiaryContainer = SkyContainerDark,
    onTertiaryContainer = SkyContainer,
    background = Soil,
    onBackground = Color(0xFFE2E3DD),
    surface = Soil,
    onSurface = Color(0xFFE2E3DD),
)

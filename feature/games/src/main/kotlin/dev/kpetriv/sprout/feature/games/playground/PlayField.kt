@file:Suppress("MatchingDeclarationName") // Named after its composables; the defaults object sits beside them.

package dev.kpetriv.sprout.feature.games.playground

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.PlaygroundAccent
import dev.kpetriv.sprout.core.designsystem.theme.ProvideKidTier
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.feature.games.R

/** Defaults for [PlayField]. */
internal object PlayFieldDefaults {
    /** The field's side gutter (DESIGN-playground.md "spacing"). */
    val Gutter = 24.dp

    /** The back tile's Chalk overlay on the field. */
    const val BACK_TILE_ALPHA: Float = 0.12f
}

/**
 * The full-bleed game scaffold (M9-games §2.2): the Field green behind the system bars (edge to edge, with light
 * status- and navigation-bar icons while it is shown), then a transparent top bar with a back tile (Chalk at 12 %),
 * [title] in Outfit 800 announced as a heading, and the Lime sprout mark (or [trailing], such as the Times tables setup
 * chip), then [content] filling the rest, inside the safe-drawing insets. [backEnabled] = false dims the back tile
 * (Sapling's "Saving…"). Use inside [PlaygroundTheme].
 */
@Composable
internal fun PlayField(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backEnabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    LightSystemBarIcons()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Playground.colors.field)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        PlayTopBar(title = title, onBack = onBack, backEnabled = backEnabled, trailing = trailing)
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), content = content)
    }
}

@Composable
private fun PlayTopBar(title: String, onBack: () -> Unit, backEnabled: Boolean, trailing: (@Composable () -> Unit)?) {
    val colors = Playground.colors
    val tileSize = SproutTheme.tierTokens.minTouchTarget
    val back = PlaygroundAccent(
        fill = colors.chalk.copy(alpha = PlayFieldDefaults.BACK_TILE_ALPHA).compositeOver(colors.field),
        edge = colors.fieldDeep,
        content = colors.chalk,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PlayFieldDefaults.Gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlayTileSurface(
            colors = back,
            onClick = onBack,
            enabled = backEnabled,
            modifier = Modifier.size(tileSize),
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(SproutIcons.ArrowBack, contentDescription = stringResource(R.string.feature_games_back))
        }
        Text(
            text = title,
            // Beside a trailing chip the title steps down a size, so "Times tables" keeps its whole name.
            style = if (trailing == null) PlaygroundType.title else PlaygroundType.figure,
            color = colors.chalk,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (trailing != null) {
            Box(modifier = Modifier.heightIn(min = tileSize), contentAlignment = Alignment.CenterEnd) { trailing() }
        } else {
            // The sprout mark balances the back tile, so the title stays centred. Decorative.
            Box(
                modifier = Modifier.size(tileSize).clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                Icon(SproutIcons.Eco, contentDescription = null, tint = colors.lime)
            }
        }
    }
}

/**
 * Light status- and navigation-bar icons on the dark field while any PlayField is composed in the window. The
 * PlayFields of one window share the effect through a count on the window's decor view: the first one sets the icons
 * light, and the last one to leave restores the app's own default (dark icons on a light theme, as `enableEdgeToEdge`
 * sets them), never a value it saved, so two PlayFields overlapping in a navigation transition cannot restore each
 * other's state and leave the next screen with invisible icons.
 */
@Composable
private fun LightSystemBarIcons() {
    if (LocalInspectionMode.current) return
    val view = LocalView.current
    val appLightIcons = !isSystemInDarkTheme()
    DisposableEffect(view, appLightIcons) {
        val window = view.context.findActivity()?.window
        if (window == null) {
            onDispose {}
        } else {
            val owners = window.decorView
            val controller = WindowCompat.getInsetsController(window, view)
            owners.setTag(R.id.feature_games_play_field_owners, owners.playFieldOwners() + 1)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            onDispose {
                val left = (owners.playFieldOwners() - 1).coerceAtLeast(0)
                owners.setTag(R.id.feature_games_play_field_owners, left)
                if (left == 0) {
                    controller.isAppearanceLightStatusBars = appLightIcons
                    controller.isAppearanceLightNavigationBars = appLightIcons
                }
            }
        }
    }
}

private fun View.playFieldOwners(): Int = getTag(R.id.feature_games_play_field_owners) as? Int ?: 0

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The field with its top bar and a placeholder, for previews and goldens (it fills the window). */
@Composable
internal fun PlayFieldSample() {
    PlayField(title = "Numbers", onBack = {}) {
        Text(
            text = "30 − 25",
            style = PlaygroundType.amount,
            color = Playground.colors.chalk,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Preview(name = "Play field", widthDp = 411, heightDp = 480)
@Composable
private fun PlayFieldPreview(@PreviewParameter(KidTierPreviewProvider::class) tier: KidUiTier) =
    SproutTheme(reducedMotion = true) {
        ProvideKidTier(tier) { PlaygroundTheme(tier) { PlayFieldSample() } }
    }

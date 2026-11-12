package dev.kpetriv.sprout.core.designsystem.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.R
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.SproutPreviewFrame
import dev.kpetriv.sprout.core.model.KidUiTier

/**
 * The top app bar of every screen (M9 §3): cream, a back arrow when [onBack] is given (none on a top-level
 * destination, §5), [title] in headlineMedium announced as a heading, and at most one [trailing] action. The bar is
 * 64 dp tall and grows when the title's line needs more (Seedling's type scale at a large font scale).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SproutTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    val cream = MaterialTheme.colorScheme.background
    val titleStyle = MaterialTheme.typography.headlineMedium
    // The bar grows with the title's line (tier type scale times the user's font scale), so it never clips.
    val titleLine = with(LocalDensity.current) { titleStyle.lineHeight.toDp() }
    TopAppBar(
        title = {
            Text(
                text = title,
                style = titleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        },
        modifier = modifier,
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(SproutIcons.ArrowBack, contentDescription = stringResource(R.string.core_designsystem_back))
                }
            }
        },
        actions = trailing,
        expandedHeight = maxOf(TopAppBarDefaults.TopAppBarExpandedHeight, titleLine + TitleVerticalPadding * 2),
        windowInsets = windowInsets,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = cream,
            scrolledContainerColor = cream,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

private val TitleVerticalPadding = 8.dp

/** A pushed screen's bar (back arrow and one action) over a top-level one (no arrow), for previews and goldens. */
@Composable
internal fun SproutTopBarSample() {
    Column {
        SproutTopBar(
            title = "Leo's wallet",
            onBack = {},
            trailing = {
                IconButton(onClick = {}) { Icon(SproutIcons.MoreVert, contentDescription = "More") }
            },
            windowInsets = WindowInsets(0),
        )
        SproutTopBar(title = "Family dashboard", onBack = null, windowInsets = WindowInsets(0))
    }
}

@Preview(name = "Seedling", showBackground = true)
@Composable
private fun SproutTopBarSeedlingPreview() = SproutPreviewFrame(KidUiTier.SEEDLING) { SproutTopBarSample() }

@Preview(name = "Sprout", showBackground = true)
@Composable
private fun SproutTopBarSproutPreview() = SproutPreviewFrame(KidUiTier.SPROUT) { SproutTopBarSample() }

@Preview(name = "Sapling", showBackground = true)
@Composable
private fun SproutTopBarSaplingPreview() = SproutPreviewFrame(KidUiTier.SAPLING) { SproutTopBarSample() }

@Preview(name = "Parent, dark", showBackground = true)
@Composable
private fun SproutTopBarParentDarkPreview() = SproutPreviewFrame(tier = null, darkTheme = true) { SproutTopBarSample() }

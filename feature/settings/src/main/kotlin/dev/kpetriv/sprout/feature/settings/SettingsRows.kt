package dev.kpetriv.sprout.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.components.SproutCard
import dev.kpetriv.sprout.core.designsystem.components.SproutChip
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId

/** A titled group of settings content, in the display face, with an optional [icon] before it (MG-08). */
@Composable
internal fun SectionHeading(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md - SproutSpacing.xs),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = iconTint)
        Text(text = text, style = MaterialTheme.typography.titleLarge)
    }
}

/** A white settings card (`SproutCard`) whose [content] is spaced by [SproutSpacing.md]. */
@Composable
internal fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    SproutCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(SproutSpacing.md), content = content)
    }
}

/**
 * A row as its own white card: an optional [icon] in a Leaf-tint circle (or any other [leading] content), [title] over [summary] and any [extra]
 * lines, and a chevron when it opens something. With [onClick] the whole card is one button; with [selected] as well
 * (the hub beside a detail pane) it is announced selected or not, and the selected row sits on Leaf tint.
 */
@Composable
internal fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    selected: Boolean? = null,
    leading: (@Composable () -> Unit)? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.medium
    val interaction = when {
        onClick == null -> Modifier
        selected != null -> Modifier.clip(shape).selectable(selected = selected, role = Role.Button, onClick = onClick)
        else -> Modifier.clip(shape).clickable(role = Role.Button, onClick = onClick)
    }
    val scheme = MaterialTheme.colorScheme
    SproutCard(modifier = modifier.fillMaxWidth().then(interaction), contentPadding = PaddingValues(0.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (selected == true) scheme.primaryContainer else Color.Transparent)
                .heightIn(min = RowMinHeight)
                .padding(horizontal = SproutSpacing.cardPadding, vertical = SproutSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md),
        ) {
            when {
                leading != null -> leading()
                icon != null -> IconCircle(icon, selected = selected == true)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SproutSpacing.xs)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                summary?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
                extra?.invoke(this)
            }
            if (onClick != null) {
                Icon(SproutIcons.ChevronRight, contentDescription = null, tint = scheme.onSurfaceVariant)
            }
        }
    }
}

/** A 40 dp Leaf-tint circle holding a Botanical-deep [icon]; white on a [selected] (Leaf-tint) row. */
@Composable
internal fun IconCircle(icon: ImageVector, modifier: Modifier = Modifier, selected: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val circle = if (selected) scheme.surfaceContainerLowest else scheme.primaryContainer
    Box(
        modifier = modifier.size(IconCircleSize).background(circle, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
    }
}

/** Kid choice chips for the per-kid sections: one choice of several, announced as radio buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KidChips(kids: List<Kid>, selected: KidId?, onSelect: (KidId) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
    ) {
        kids.forEach { kid ->
            SproutChip(
                selected = kid.id == selected,
                label = kid.firstName,
                onClick = { onSelect(kid.id) },
                role = Role.RadioButton,
            )
        }
    }
}

/** One choice of several as Linen chips in a selectable group, announced as radio buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> RadioChips(
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
    ) {
        options.forEach { option ->
            SproutChip(
                selected = option == selected,
                label = optionLabel(option),
                onClick = { onSelect(option) },
                role = Role.RadioButton,
            )
        }
    }
}

/** A labelled Linen pill that opens a menu of [options]; [optionLabel] names each (MG-08's retention picker). */
@Composable
internal fun <T> ChoiceDropdown(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Box {
            val value = optionLabel(selected)
            Surface(
                onClick = { expanded = true },
                enabled = enabled,
                shape = CircleShape,
                color = scheme.surfaceContainerLow,
                contentColor = if (enabled) scheme.onSurface else scheme.onSurfaceVariant,
                border = BorderStroke(1.dp, scheme.outlineVariant),
                // Read as "Birth month, March": the label above is a separate node.
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "$label, $value"
                    role = Role.DropdownList
                },
            ) {
                Row(
                    modifier = Modifier
                        .heightIn(min = SproutTheme.tierTokens.buttonHeight)
                        .padding(horizontal = SproutSpacing.lg - SproutSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Icon(
                        ImageVector.vectorResource(R.drawable.feature_settings_ic_expand_more),
                        contentDescription = null,
                    )
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

/** Text fields on Linen with a hairline, Botanical when focused (as the chat composer). */
@Composable
internal fun settingsFieldColors(): TextFieldColors {
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

private val IconCircleSize = 40.dp
private val RowMinHeight = 72.dp

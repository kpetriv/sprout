package dev.kpetriv.sprout.feature.parenthome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.components.CardMeaning
import dev.kpetriv.sprout.core.designsystem.components.SproutCard
import dev.kpetriv.sprout.core.designsystem.components.accentTint
import dev.kpetriv.sprout.core.designsystem.theme.STACK_FONT_SCALE
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.designsystem.theme.SproutTint

/** What leads an attention row: a count in the display face, or an icon for a state without a number. */
internal sealed interface AttentionLead {
    /**
     * A count: on Honey tint while above zero (it needs the parent), on Linen at zero. With [alert] (a ledger problem,
     * M9 §2.1) a count above zero takes the error container instead.
     */
    data class Count(val count: Int, val alert: Boolean = false) : AttentionLead

    /** Something to do that is not about security (the trial household): the icon on Honey tint. */
    data class Symbol(val icon: ImageVector) : AttentionLead

    /**
     * A security state (the household policy missing, the signing key not registered, signing off, a new device, a
     * blocked sync): the icon on the error container, as Settings shows the same states (D-REL-17, audit N5). The
     * row's title says what it is; the colour only repeats it.
     */
    data class Alert(val icon: ImageVector) : AttentionLead
}

/**
 * The dashboard's "Needs you" card (M9 §4, the "Botanical Linen" render): one white card of [rows] with a hairline
 * between them.
 */
@Composable
internal fun AttentionCard(rows: List<@Composable () -> Unit>, modifier: Modifier = Modifier) {
    SproutCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            row()
        }
    }
}

/**
 * One row of the "Needs you" card: the [lead] circle (parents get Honey-tint number circles, M9 §2.6; security states
 * and ledger problems the error container), the [title]
 * as a heading over [detail], any [extra] lines, and the [action] pill on the right. A row whose text runs to a
 * paragraph passes [actionBelow] to put its pill under the text; at large font sizes every pill goes there, so no
 * word breaks mid-way. The words always say the state: the colour only repeats it.
 */
@Composable
internal fun AttentionRow(
    lead: AttentionLead,
    title: String?,
    detail: String?,
    modifier: Modifier = Modifier,
    actionBelow: Boolean = false,
    action: (@Composable (Modifier) -> Unit)? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val below = actionBelow || LocalDensity.current.fontScale >= STACK_FONT_SCALE
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SproutSpacing.cardPadding, vertical = SproutSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md),
        verticalAlignment = if (below || extra != null) Alignment.Top else Alignment.CenterVertically,
    ) {
        LeadCircle(lead)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SproutSpacing.xs)) {
            title?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (title == null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            extra?.invoke(this)
            if (below) action?.invoke(Modifier.padding(top = SproutSpacing.sm))
        }
        if (!below) action?.invoke(Modifier)
    }
}

@Composable
private fun LeadCircle(lead: AttentionLead) {
    val scheme = MaterialTheme.colorScheme
    val tint = when {
        lead is AttentionLead.Alert -> SproutTint(scheme.errorContainer, scheme.onErrorContainer)
        lead is AttentionLead.Count && lead.count == 0 -> CardMeaning.Content.accentTint()
        lead is AttentionLead.Count && lead.alert -> SproutTint(scheme.errorContainer, scheme.onErrorContainer)
        else -> CardMeaning.Attention.accentTint()
    }
    val style = MaterialTheme.typography.titleLarge
    // Grows with the number's line at large font scales, so the digits never spill out of the circle.
    val line = with(LocalDensity.current) { style.lineHeight.toDp() }
    Box(
        modifier = Modifier.size(maxOf(LeadSize, line + SproutSpacing.sm)).background(tint.container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when (lead) {
            is AttentionLead.Count -> Text(
                text = lead.count.toString(),
                style = style,
                color = tint.content,
                maxLines = 1,
            )

            is AttentionLead.Symbol -> Icon(lead.icon, contentDescription = null, tint = tint.content)

            is AttentionLead.Alert -> Icon(lead.icon, contentDescription = null, tint = tint.content)
        }
    }
}

private val LeadSize = 44.dp

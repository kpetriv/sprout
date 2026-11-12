package dev.kpetriv.sprout.core.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.theme.ColourLevel
import dev.kpetriv.sprout.core.designsystem.theme.SproutPreviewFrame
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.designsystem.theme.SproutTint
import dev.kpetriv.sprout.core.model.KidUiTier

/** What a [SproutCard] holds, which decides its tint on colourful tiers (M9 §2.6). */
enum class CardMeaning {
    /** Anything without a colour meaning: always a white card. */
    Content,

    /** Money (balances, credits): Leaf. */
    Money,

    /** Something that needs a look or a tap (approvals, the daily game credit): Honey. */
    Attention,

    /** Screen time: Sky. */
    Time,

    /** A savings goal: Sky. */
    Goal,
}

/** The colours a [SproutCard] resolves to. */
@Immutable
data class SproutCardColors(val container: Color, val content: Color, val border: Color)

/** Defaults for [SproutCard]. */
object SproutCardDefaults {
    /** 1 dp hairline, as in the renders. */
    val BorderWidth = 1.dp

    /** The card's inner padding: 20 dp all round. */
    val ContentPadding = PaddingValues(SproutSpacing.cardPadding)

    /**
     * The card colours for [meaning] at the current [ColourLevel]: a tint block on [ColourLevel.FULL] for every
     * meaning but [CardMeaning.Content]; a white card with a hairline everywhere else (Sprout and Sapling screens put
     * the meaning in a chip or icon circle instead, see [accentTint]).
     */
    @Composable
    @ReadOnlyComposable
    fun colors(meaning: CardMeaning): SproutCardColors {
        val scheme = MaterialTheme.colorScheme
        val tinted = SproutTheme.tierTokens.colourLevel == ColourLevel.FULL && meaning != CardMeaning.Content
        return if (tinted) {
            val tint = meaning.accentTint()
            SproutCardColors(
                container = tint.container,
                content = scheme.onSurface,
                border = if (meaning ==
                    CardMeaning.Attention
                ) {
                    SproutTheme.colors.honeyBorder
                } else {
                    scheme.outlineVariant
                },
            )
        } else {
            SproutCardColors(
                container = scheme.surfaceContainerLowest,
                content = scheme.onSurface,
                border = scheme.outlineVariant,
            )
        }
    }
}

/**
 * The tint that carries this meaning in a chip or an icon circle (the Leaf-tint money chip, the Honey screen-time
 * badge, the Sky goal icon), for screens whose cards stay white. [CardMeaning.Content] is Linen with Ink.
 */
@Composable
@ReadOnlyComposable
fun CardMeaning.accentTint(): SproutTint {
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        CardMeaning.Content -> SproutTint(scheme.surfaceContainerLow, scheme.onSurface)
        CardMeaning.Money -> SproutTint(scheme.primaryContainer, scheme.onPrimaryContainer)
        CardMeaning.Attention -> SproutTint(scheme.secondaryContainer, scheme.onSecondaryContainer)
        CardMeaning.Time, CardMeaning.Goal -> SproutTint(scheme.tertiaryContainer, scheme.onTertiaryContainer)
    }
}

/**
 * Sprout's one card (M9 §3): white with a hairline border, the tier's corner radius (18 / 24 / 28 dp), 20 dp
 * padding, no shadow. [meaning] tints it on Seedling ([SproutCardDefaults.colors]). With [onClick] the whole card
 * is one button (announced as one); it never moves when pressed.
 */
@Composable
fun SproutCard(
    modifier: Modifier = Modifier,
    meaning: CardMeaning = CardMeaning.Content,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = SproutCardDefaults.ContentPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = SproutCardDefaults.colors(meaning)
    val shape = MaterialTheme.shapes.medium
    val border = BorderStroke(SproutCardDefaults.BorderWidth, colors.border)
    if (onClick == null) {
        Surface(
            modifier = modifier,
            shape = shape,
            color = colors.container,
            contentColor = colors.content,
            border = border,
        ) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier.semantics { role = Role.Button },
            shape = shape,
            color = colors.container,
            contentColor = colors.content,
            border = border,
        ) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    }
}

/** Every [CardMeaning] in a column, for previews and goldens. */
@Composable
internal fun SproutCardSample() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(SproutSpacing.gutter),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.rowGap),
    ) {
        CardMeaning.entries.forEach { meaning ->
            SproutCard(modifier = Modifier.fillMaxWidth(), meaning = meaning) {
                Text(text = meaning.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "One idea per card.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(name = "Seedling", showBackground = true)
@Composable
private fun SproutCardSeedlingPreview() = SproutPreviewFrame(KidUiTier.SEEDLING) { SproutCardSample() }

@Preview(name = "Sprout", showBackground = true)
@Composable
private fun SproutCardSproutPreview() = SproutPreviewFrame(KidUiTier.SPROUT) { SproutCardSample() }

@Preview(name = "Sapling", showBackground = true)
@Composable
private fun SproutCardSaplingPreview() = SproutPreviewFrame(KidUiTier.SAPLING) { SproutCardSample() }

package dev.kpetriv.sprout.feature.wallet

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.components.CardMeaning
import dev.kpetriv.sprout.core.designsystem.components.Pictogram
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutButtonStyle
import dev.kpetriv.sprout.core.designsystem.components.SproutCard
import dev.kpetriv.sprout.core.designsystem.components.SproutTopBar
import dev.kpetriv.sprout.core.designsystem.components.accentTint
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind
import dev.kpetriv.sprout.core.ui.ContentWidth
import dev.kpetriv.sprout.core.ui.contentWidth
import dev.kpetriv.sprout.core.ui.rememberMoneyFormatter
import dev.kpetriv.sprout.core.ui.withGutters

/**
 * Navigation entry for the parent's rewards list, reached from the dashboard's "Shop" and from "Edit rewards" in the
 * parent's shop preview. Parent area only.
 *
 * List-detail (as the chore list, M7 §5, D-M7-2): on an Expanded window the app shows this route as the list pane and
 * [RewardEditorRoute] as the detail pane (`listPane(detailPlaceholder = { RewardEditorPlaceholder() })` and
 * `detailPane()`). Pass the reward open in the editor as [selectedRewardId] so its row is marked selected.
 *
 * @param onSeeShop "See the shop as a kid": the app asks which kid, then opens that kid's shop in parent view.
 */
@Composable
fun RewardListRoute(
    onAddReward: () -> Unit,
    onEditReward: (RewardId) -> Unit,
    onSeeShop: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    selectedRewardId: RewardId? = null,
) {
    val viewModel = featureViewModel<RewardListViewModel>(key = "rewards:list", args = Bundle())
    val state by viewModel.state.collectAsStateWithLifecycle()
    RewardListScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onAddReward = onAddReward,
        onEditReward = onEditReward,
        onSeeShop = onSeeShop,
        onBack = onBack,
        modifier = modifier,
        selectedRewardId = selectedRewardId,
    )
}

/**
 * Every reward in the household's shop as white cards: the kind's picture, the title, the price in Botanical, the
 * screen time a screen-time reward gives, who it is for and, for a hidden one, a "Hidden" pill (words, never colour
 * alone). "Add reward" is the floating pill; "See the shop as a kid" opens the preview. With no rewards, an empty
 * state invites the parent to add the first one.
 */
@Composable
fun RewardListScreen(
    state: RewardListUiState,
    onEvent: (RewardListEvent) -> Unit,
    modifier: Modifier = Modifier,
    onAddReward: () -> Unit = {},
    onEditReward: (RewardId) -> Unit = {},
    onSeeShop: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    selectedRewardId: RewardId? = null,
) {
    val ready = state as? RewardListUiState.Ready
    Scaffold(
        modifier = modifier,
        topBar = { SproutTopBar(title = stringResource(R.string.feature_wallet_rewards_title), onBack = onBack) },
        floatingActionButton = {
            if (ready != null && ready.rewards.isNotEmpty()) {
                SproutButton(
                    text = stringResource(R.string.feature_wallet_rewards_add),
                    onClick = onAddReward,
                    icon = SproutIcons.Add,
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                RewardListUiState.Loading -> RewardsLoading(stringResource(R.string.feature_wallet_rewards_loading))

                RewardListUiState.Error -> RewardsError(
                    text = stringResource(R.string.feature_wallet_rewards_error),
                    onRetry = { onEvent(RewardListEvent.Retry) },
                )

                is RewardListUiState.Ready -> if (state.rewards.isEmpty()) {
                    EmptyRewards(onAddReward, onSeeShop)
                } else {
                    RewardRows(state.rewards, selectedRewardId, onEditReward, onSeeShop)
                }
            }
        }
    }
}

@Composable
private fun RewardRows(
    rewards: List<RewardRowUi>,
    selectedRewardId: RewardId?,
    onEdit: (RewardId) -> Unit,
    onSeeShop: () -> Unit,
) {
    ContentWidth(modifier = Modifier.fillMaxSize()) { gutters ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = SproutSpacing.gutter,
                end = SproutSpacing.gutter,
                top = SproutSpacing.sm,
                bottom = ListBottomPadding,
            ).withGutters(gutters),
            verticalArrangement = Arrangement.spacedBy(SproutSpacing.rowGap),
        ) {
            item(key = "intro") {
                Column(verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm)) {
                    Text(
                        text = stringResource(R.string.feature_wallet_rewards_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SproutButton(
                        text = stringResource(R.string.feature_wallet_rewards_see_shop),
                        onClick = onSeeShop,
                        style = SproutButtonStyle.Secondary,
                        icon = SproutIcons.Storefront,
                    )
                }
            }
            items(rewards, key = { it.id.value }) { row ->
                RewardCard(row, selected = row.id == selectedRewardId, onClick = { onEdit(row.id) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RewardCard(row: RewardRowUi, selected: Boolean, onClick: () -> Unit) {
    val formatter = rememberMoneyFormatter()
    val price = formatter.format(row.cost, row.currency)
    val kind = row.kind.label()
    val shortMinutes = row.minutes?.let { formatter.formatMinutes(it.toLong()) }
    // "Screen time · 30 min" under the title; read aloud as "30 min of screen time" in place of the kind.
    val kindLine = shortMinutes?.let { stringResource(R.string.feature_wallet_rewards_kind_minutes, kind, it) } ?: kind
    val spokenKind = shortMinutes?.let { stringResource(R.string.feature_wallet_rewards_minutes, it) } ?: kind
    val audience = row.forKids.label()
    val status = stringResource(
        if (row.active) R.string.feature_wallet_rewards_in_shop else R.string.feature_wallet_rewards_hidden,
    )
    val description =
        stringResource(R.string.feature_wallet_rewards_row_description, row.title, spokenKind, price, audience, status)
    // The reward open in the editor pane beside the list has a Botanical outline and is announced as selected.
    val outline = if (selected) {
        Modifier.border(SelectedOutline, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
    } else {
        Modifier
    }
    SproutCard(
        modifier = Modifier.fillMaxWidth().then(outline).semantics { if (selected) this.selected = true },
        onClick = onClick,
        contentPadding = PaddingValues(SproutSpacing.md),
    ) {
        Row(
            modifier = Modifier.clearAndSetSemantics { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pictogram(row.kind.pictogram, size = RowPictogram)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SproutSpacing.xs)) {
                Text(text = row.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = kindLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = audience,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(SproutSpacing.xs),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = price,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    StatePill(status, if (row.active) CardMeaning.Money else CardMeaning.Content)
                }
            }
        }
    }
}

/**
 * A small pill naming a reward's state on [meaning]'s tint, as the chore list's state pill: "In the shop" on Leaf tint
 * (as an active chore), "Hidden" on Linen (as an archived one). The words carry the meaning, never the colour alone.
 */
@Composable
private fun StatePill(text: String, meaning: CardMeaning) {
    val tint = meaning.accentTint()
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = tint.content,
        modifier = Modifier
            .background(tint.container, CircleShape)
            .padding(horizontal = SproutSpacing.sm + SproutSpacing.xs, vertical = SproutSpacing.xs),
    )
}

@Composable
private fun EmptyRewards(onAddReward: () -> Unit, onSeeShop: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .contentWidth()
            .padding(SproutSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Pictogram(Pictogram.GIFT, size = EmptyPictogram)
        Text(
            text = stringResource(R.string.feature_wallet_rewards_empty_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.feature_wallet_rewards_empty_body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SproutButton(
            text = stringResource(R.string.feature_wallet_rewards_add),
            onClick = onAddReward,
            icon = SproutIcons.Add,
        )
        SproutButton(
            text = stringResource(R.string.feature_wallet_rewards_see_shop),
            onClick = onSeeShop,
            style = SproutButtonStyle.Tertiary,
            icon = SproutIcons.Storefront,
        )
    }
}

/**
 * The detail pane before a reward is picked (list-detail on an Expanded window): the app passes it as the list pane's
 * `detailPlaceholder`.
 */
@Composable
fun RewardEditorPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(SproutSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Pictogram(Pictogram.GIFT, size = EmptyPictogram)
        Text(
            text = stringResource(R.string.feature_wallet_rewards_placeholder),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Treat", "Screen time", ... */
@Composable
internal fun RewardKind.label(): String = stringResource(
    when (this) {
        RewardKind.SCREEN_TIME -> R.string.feature_wallet_reward_kind_screen_time
        RewardKind.TREAT -> R.string.feature_wallet_reward_kind_treat
        RewardKind.OUTING -> R.string.feature_wallet_reward_kind_outing
        RewardKind.OTHER -> R.string.feature_wallet_reward_kind_other
    },
)

/** "All kids", "For Mia, Leo", or "No kids picked" when every chosen kid has left. */
@Composable
private fun RewardAudience.label(): String = when (this) {
    RewardAudience.AllKids -> stringResource(R.string.feature_wallet_rewards_all_kids)

    is RewardAudience.Kids -> if (names.isEmpty()) {
        stringResource(R.string.feature_wallet_rewards_no_kids)
    } else {
        val list = names.reduce { joined, name ->
            stringResource(R.string.feature_wallet_rewards_name_list, joined, name)
        }
        stringResource(R.string.feature_wallet_rewards_for_kids, list)
    }
}

@Composable
internal fun RewardsLoading(description: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = description })
    }
}

@Composable
internal fun RewardsError(text: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(SproutSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        SproutButton(text = stringResource(R.string.feature_wallet_retry), onClick = onRetry)
    }
}

/** Room under the last card for the floating "Add reward" pill. */
private val ListBottomPadding = 96.dp
private val RowPictogram = 40.dp
private val EmptyPictogram = 96.dp
private val SelectedOutline = 2.dp

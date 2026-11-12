package dev.kpetriv.sprout.feature.chores

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.components.CardMeaning
import dev.kpetriv.sprout.core.designsystem.components.InitialAvatar
import dev.kpetriv.sprout.core.designsystem.components.Pictogram
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutButtonStyle
import dev.kpetriv.sprout.core.designsystem.components.SproutCard
import dev.kpetriv.sprout.core.designsystem.components.SproutChip
import dev.kpetriv.sprout.core.designsystem.components.SproutTopBar
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.STACK_FONT_SCALE
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreState
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.ContentWidth
import dev.kpetriv.sprout.core.ui.rememberMoneyFormatter
import dev.kpetriv.sprout.core.ui.withGutters

/**
 * Navigation entry for the parent's chore list, the root of the parent shell's Chores tab (no back arrow, M9 §5.2).
 *
 * List-detail (M7 §5, D-M7-2): on an Expanded window the app shows this route as the list pane and
 * [ChoreEditorRoute] as the detail pane (Navigation 3 `ListDetailSceneStrategy`: this entry's metadata is
 * `listPane(detailPlaceholder = { ChoreEditorPlaceholder() })`, the editor's is `detailPane()`). Pass the chore open
 * in the editor as [selectedChoreId] so its row is marked selected while both panes show.
 *
 * @param onBack the top bar's back arrow; null (the default) on the Chores tab root.
 */
@Composable
fun ChoreListRoute(
    onCreateChore: () -> Unit,
    onEditChore: (ChoreId) -> Unit,
    onOpenApprovals: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    selectedChoreId: ChoreId? = null,
) {
    val viewModel = choresViewModel<ChoreListViewModel>(key = "chores:list")
    val state by viewModel.state.collectAsStateWithLifecycle()
    ChoreListScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onCreateChore = onCreateChore,
        onEditChore = onEditChore,
        onOpenApprovals = onOpenApprovals,
        onBack = onBack,
        modifier = modifier,
        selectedChoreId = selectedChoreId,
    )
}

/**
 * Every kid's chores, grouped by kid under their avatar, as white cards with a pictogram, the schedule, the reward in
 * Botanical and a state pill, with archive / restore, a "Show archived" chip and a "New chore" pill. The approvals
 * count sits in the bar. [selectedChoreId] marks the chore open in the editor pane (list-detail on an Expanded window)
 * with a Botanical outline, announced as selected.
 *
 * @param onBack the top bar's back arrow; null on the Chores tab root (M9 §5.2).
 */
@Composable
fun ChoreListScreen(
    state: ChoreListUiState,
    onEvent: (ChoreListEvent) -> Unit,
    modifier: Modifier = Modifier,
    onCreateChore: () -> Unit = {},
    onEditChore: (ChoreId) -> Unit = {},
    onOpenApprovals: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    selectedChoreId: ChoreId? = null,
) {
    val snackbar = remember { SnackbarHostState() }
    val ready = state as? ChoreListUiState.Ready
    Scaffold(
        modifier = modifier,
        topBar = {
            SproutTopBar(
                title = stringResource(R.string.feature_chores_title),
                onBack = onBack,
                trailing = {
                    if (ready != null) {
                        SproutButton(
                            text = stringResource(R.string.feature_chores_approvals_count, ready.pendingApprovals),
                            onClick = onOpenApprovals,
                            style = SproutButtonStyle.Tertiary,
                            modifier = Modifier.padding(end = SproutSpacing.sm),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (ready != null && ready.groups.isNotEmpty()) {
                SproutButton(
                    text = stringResource(R.string.feature_chores_new_chore),
                    onClick = onCreateChore,
                    icon = SproutIcons.Add,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                ChoreListUiState.Loading -> LoadingContent()

                ChoreListUiState.Error -> ErrorContent(
                    text = stringResource(R.string.feature_chores_error_load_failed),
                    onRetry = { onEvent(ChoreListEvent.Retry) },
                )

                is ChoreListUiState.Ready -> ReadyList(state, onEvent, onEditChore, selectedChoreId)
            }
        }
    }
    if (ready != null) {
        val text = when (ready.message) {
            null -> null
            ChoreListMessage.Archived -> stringResource(R.string.feature_chores_archived_message)
            ChoreListMessage.Restored -> stringResource(R.string.feature_chores_restored_message)
            ChoreListMessage.Failed -> stringResource(R.string.feature_chores_something_failed)
        }
        MessageEffect(snackbar, ready.message, text) { onEvent(ChoreListEvent.MessageShown) }
    }
}

@Composable
private fun ReadyList(
    state: ChoreListUiState.Ready,
    onEvent: (ChoreListEvent) -> Unit,
    onEdit: (ChoreId) -> Unit,
    selectedChoreId: ChoreId?,
) {
    if (state.groups.isEmpty()) {
        Text(
            text = stringResource(R.string.feature_chores_no_kids),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(SproutSpacing.lg),
        )
        return
    }
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
            item(key = "toggle") {
                SproutChip(
                    selected = state.showArchived,
                    label = stringResource(R.string.feature_chores_show_archived),
                    onClick = { onEvent(ChoreListEvent.ToggleArchived) },
                )
            }
            state.groups.forEach { group ->
                item(key = "kid:${group.kidId.value}") { KidHeading(group.kidName, tintIndex = group.tintIndex) }
                if (group.chores.isEmpty()) {
                    item(key = "empty:${group.kidId.value}") {
                        Text(
                            text = stringResource(R.string.feature_chores_kid_no_chores, group.kidName),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(group.chores, key = { it.id.value }) { row ->
                    ChoreRow(
                        row = row,
                        selected = row.id == selectedChoreId,
                        onClick = { onEdit(row.id) },
                        onArchive = { onEvent(ChoreListEvent.Archive(row.id)) },
                        onRestore = { onEvent(ChoreListEvent.Restore(row.id)) },
                    )
                }
            }
        }
    }
}

/** The kid's avatar (tint by their place in the household) and name, as a heading. */
@Composable
private fun KidHeading(name: String, tintIndex: Int) {
    Row(
        modifier = Modifier.padding(top = SproutSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm + SproutSpacing.xs),
    ) {
        InitialAvatar(
            initial = name.take(1).uppercase(),
            tintIndex = tintIndex,
            size = KidAvatar,
            modifier = Modifier.clearAndSetSemantics {},
        )
        SectionHeading(text = name, style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * The detail pane before a chore is picked (list-detail on an Expanded window): the app passes it as the list pane's
 * `detailPlaceholder`.
 */
@Composable
fun ChoreEditorPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(SproutSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Pictogram(Pictogram.SPARKLE, size = PlaceholderPictogram)
        Text(
            text = stringResource(R.string.feature_chores_editor_placeholder),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoreRow(
    row: ChoreRowUi,
    selected: Boolean,
    onClick: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
) {
    val formatter = rememberMoneyFormatter()
    val reward = row.reward.format(formatter)
    val schedule = scheduleLabel(row.schedule)
    val stateLabel = stateLabel(row.state)
    val description = stringResource(R.string.feature_chores_row_description, row.title, reward, schedule, stateLabel)
    val shape = MaterialTheme.shapes.medium
    // The chore open in the editor pane beside the list has a Botanical outline and is announced as selected. Each
    // card is a traversal group, so its archive button is read with its chore.
    val outline = if (selected) {
        Modifier.border(SelectedOutline, MaterialTheme.colorScheme.primary, shape)
    } else {
        Modifier
    }
    SproutCard(
        modifier = Modifier.fillMaxWidth().then(outline).semantics { isTraversalGroup = true },
        contentPadding = PaddingValues(0.dp),
    ) {
        val stacked = LocalDensity.current.fontScale >= STACK_FONT_SCALE
        val details: @Composable (Modifier) -> Unit = { detailsModifier ->
            Row(
                modifier = detailsModifier
                    .clickable(role = Role.Button, onClick = onClick)
                    .semantics(mergeDescendants = true) {
                        contentDescription = description
                        if (selected) this.selected = true
                    }
                    .padding(SproutSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pictogram(ChorePictogram.forTitle(row.title), size = RowPictogram)
                Column(verticalArrangement = Arrangement.spacedBy(SproutSpacing.xs)) {
                    Text(text = row.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = schedule,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(SproutSpacing.xs),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        RewardText(
                            reward = row.reward,
                            formatter = formatter,
                            tier = KidUiTier.SAPLING,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        LabelPill(stateLabel, row.state.meaning)
                    }
                }
            }
        }
        val archived = row.state == ChoreState.ARCHIVED
        val actionDescription = stringResource(
            if (archived) R.string.feature_chores_restore_described else R.string.feature_chores_archive_described,
            row.title,
        )
        val action: @Composable (Modifier) -> Unit = { actionModifier ->
            SproutButton(
                text = stringResource(
                    if (archived) R.string.feature_chores_restore else R.string.feature_chores_archive,
                ),
                onClick = if (archived) onRestore else onArchive,
                style = SproutButtonStyle.Tertiary,
                modifier = actionModifier.semantics { contentDescription = actionDescription },
            )
        }
        if (stacked) {
            Column {
                details(Modifier.fillMaxWidth())
                action(Modifier.padding(start = SproutSpacing.md, end = SproutSpacing.md, bottom = SproutSpacing.md))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                details(Modifier.weight(1f))
                action(Modifier.padding(end = SproutSpacing.md))
            }
        }
    }
}

/** Active chores on Leaf tint, a kid's proposal on Honey tint (it needs a look), archived ones on Linen. */
private val ChoreState.meaning: CardMeaning
    get() = when (this) {
        ChoreState.ACTIVE -> CardMeaning.Money
        ChoreState.PROPOSED -> CardMeaning.Attention
        ChoreState.ARCHIVED -> CardMeaning.Content
    }

@Composable
private fun stateLabel(state: ChoreState): String = stringResource(
    when (state) {
        ChoreState.ACTIVE -> R.string.feature_chores_state_active
        ChoreState.PROPOSED -> R.string.feature_chores_state_proposed
        ChoreState.ARCHIVED -> R.string.feature_chores_state_archived
    },
)

/** Room under the last card for the floating "New chore" pill. */
private val ListBottomPadding = 96.dp
private val KidAvatar = 40.dp
private val RowPictogram = 40.dp
private val PlaceholderPictogram = 96.dp
private val SelectedOutline = 2.dp

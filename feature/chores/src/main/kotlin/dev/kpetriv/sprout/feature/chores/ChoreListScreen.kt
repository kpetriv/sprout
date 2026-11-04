package dev.kpetriv.sprout.feature.chores

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreState
import dev.kpetriv.sprout.core.ui.ContentWidth
import dev.kpetriv.sprout.core.ui.rememberMoneyFormatter
import dev.kpetriv.sprout.core.ui.withGutters

/**
 * Navigation entry for the parent's chore list.
 *
 * List-detail (M7 §5, D-M7-2): on an Expanded window the app shows this route as the list pane and
 * [ChoreEditorRoute] as the detail pane (Navigation 3 `ListDetailSceneStrategy`: this entry's metadata is
 * `listPane(detailPlaceholder = { ChoreEditorPlaceholder() })`, the editor's is `detailPane()`). Pass the chore open
 * in the editor as [selectedChoreId] so its row is marked selected while both panes show.
 */
@Composable
fun ChoreListRoute(
    onCreateChore: () -> Unit,
    onEditChore: (ChoreId) -> Unit,
    onOpenApprovals: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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
 * Every kid's chores, grouped by kid, with state chips, archive / restore and a "New chore" button.
 * [selectedChoreId] marks the chore open in the editor pane (list-detail on an Expanded window).
 */
@Composable
fun ChoreListScreen(
    state: ChoreListUiState,
    onEvent: (ChoreListEvent) -> Unit,
    modifier: Modifier = Modifier,
    onCreateChore: () -> Unit = {},
    onEditChore: (ChoreId) -> Unit = {},
    onOpenApprovals: () -> Unit = {},
    onBack: () -> Unit = {},
    selectedChoreId: ChoreId? = null,
) {
    val snackbar = remember { SnackbarHostState() }
    val ready = state as? ChoreListUiState.Ready
    Scaffold(
        modifier = modifier,
        topBar = {
            ChoresTopBar(title = stringResource(R.string.feature_chores_title), onBack = onBack) {
                if (ready != null) {
                    TextButton(onClick = onOpenApprovals) {
                        Text(stringResource(R.string.feature_chores_approvals_count, ready.pendingApprovals))
                    }
                }
            }
        },
        floatingActionButton = {
            if (ready != null && ready.groups.isNotEmpty()) {
                val newChore = stringResource(R.string.feature_chores_new_chore)
                ExtendedFloatingActionButton(
                    onClick = onCreateChore,
                    // Material's extended FAB does not expose its text to accessibility services (M7 §8).
                    modifier = Modifier.semantics { contentDescription = newChore },
                    icon = { Icon(painterResource(R.drawable.feature_chores_ic_add), contentDescription = null) },
                    text = { Text(newChore) },
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
            modifier = Modifier.padding(24.dp),
        )
        return
    }
    ContentWidth(modifier = Modifier.fillMaxSize()) { gutters ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp).withGutters(gutters),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "toggle") {
                TextButton(onClick = { onEvent(ChoreListEvent.ToggleArchived) }) {
                    val label = if (state.showArchived) {
                        R.string.feature_chores_hide_archived
                    } else {
                        R.string.feature_chores_show_archived
                    }
                    Text(stringResource(label))
                }
            }
            state.groups.forEach { group ->
                item(key = "kid:${group.kidId.value}") {
                    SectionHeading(text = group.kidName, modifier = Modifier.padding(top = 8.dp))
                }
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

/**
 * The detail pane before a chore is picked (list-detail on an Expanded window): the app passes it as the list pane's
 * `detailPlaceholder`.
 */
@Composable
fun ChoreEditorPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.feature_chores_editor_placeholder),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
    // The chore open in the editor pane beside the list is shown in the secondary container colour and announced as
    // selected. Each card is a traversal group, so its archive button is read with its chore.
    val colors = if (selected) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(modifier = Modifier.fillMaxWidth().semantics { isTraversalGroup = true }, colors = colors) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onClick)
                    .semantics(mergeDescendants = true) {
                        contentDescription = description
                        if (selected) this.selected = true
                    }
                    .padding(16.dp),
            ) {
                Text(text = row.title, style = MaterialTheme.typography.titleMedium)
                Text(text = schedule, style = MaterialTheme.typography.bodyMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = reward, style = MaterialTheme.typography.labelLarge)
                    StateChip(stateLabel, row.state)
                }
            }
            val archived = row.state == ChoreState.ARCHIVED
            val actionDescription = stringResource(
                if (archived) R.string.feature_chores_restore_described else R.string.feature_chores_archive_described,
                row.title,
            )
            TextButton(
                onClick = if (archived) onRestore else onArchive,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .semantics { contentDescription = actionDescription },
            ) {
                Text(stringResource(if (archived) R.string.feature_chores_restore else R.string.feature_chores_archive))
            }
        }
    }
}

@Composable
private fun StateChip(label: String, state: ChoreState) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (state) {
        ChoreState.ACTIVE -> colors.primaryContainer to colors.onPrimaryContainer
        ChoreState.PROPOSED -> colors.tertiaryContainer to colors.onTertiaryContainer
        ChoreState.ARCHIVED -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun stateLabel(state: ChoreState): String = stringResource(
    when (state) {
        ChoreState.ACTIVE -> R.string.feature_chores_state_active
        ChoreState.PROPOSED -> R.string.feature_chores_state_proposed
        ChoreState.ARCHIVED -> R.string.feature_chores_state_archived
    },
)

package dev.kpetriv.sprout.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.rememberHiltViewModelFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kpetriv.sprout.core.designsystem.components.InitialAvatar
import dev.kpetriv.sprout.core.designsystem.components.SproutCard
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.ui.ContentWidth
import dev.kpetriv.sprout.core.ui.withGutters

/**
 * Navigation entry for the parent's chat inbox. Behind the parent gate; the app wraps it in `SecureWindow`
 * ([ChatThreadListUiState.secure]) and opens [ParentChatRoute] from [onOpenThread].
 *
 * List-detail (M7 §5, D-M7-2): on an Expanded window the app shows this route as the list pane and
 * [ParentChatRoute] as the detail pane (Navigation 3 `ListDetailSceneStrategy`: this entry's metadata is
 * `listPane(detailPlaceholder = { ChatThreadPlaceholder() })`, the thread's is `detailPane()`). Pass the open
 * thread's id as [selectedThreadId] so its row is marked selected while both panes show.
 *
 * @param onBack null when the inbox is the Chat tab's root (no back arrow, M9 §5.2).
 */
@Composable
fun ChatThreadListRoute(
    onOpenThread: (KidId) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    selectedThreadId: KidId? = null,
) {
    val viewModel: ChatThreadListViewModel = viewModel(factory = rememberHiltViewModelFactory())
    val state by viewModel.state.collectAsStateWithLifecycle()
    ChatThreadListScreen(state, viewModel::onEvent, onOpenThread, modifier, onBack, selectedThreadId)
}

/**
 * One card per kid, in the household's order: the kid's avatar (tinted by that order, as on the dashboard), name,
 * the unread count or when the last message came. [selectedThreadId] marks the thread open in the detail pane
 * (list-detail on an Expanded window) with a Botanical outline, announced as selected.
 */
@Composable
fun ChatThreadListScreen(
    state: ChatThreadListUiState,
    onEvent: (ChatThreadListEvent) -> Unit,
    onOpenThread: (KidId) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    selectedThreadId: KidId? = null,
) {
    Scaffold(
        modifier = modifier,
        topBar = { ChatTopBar(title = stringResource(R.string.feature_chat_threads_title), onBack = onBack) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                ChatThreadListUiState.Loading -> LoadingContent()

                ChatThreadListUiState.Error -> ErrorContent(
                    text = stringResource(R.string.feature_chat_error_load_failed),
                    onRetry = { onEvent(ChatThreadListEvent.Reload) },
                )

                is ChatThreadListUiState.Ready -> if (state.threads.isEmpty()) {
                    EmptyInbox(modifier = Modifier.align(Alignment.Center))
                } else {
                    ContentWidth(modifier = Modifier.fillMaxSize()) { gutters ->
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                horizontal = SproutSpacing.gutter,
                                vertical = SproutSpacing.md,
                            ).withGutters(gutters),
                            verticalArrangement = Arrangement.spacedBy(SproutSpacing.rowGap),
                        ) {
                            itemsIndexed(state.threads, key = { _, row -> row.threadId.value }) { index, row ->
                                ThreadRow(
                                    row = row,
                                    tintIndex = index,
                                    selected = row.threadId == selectedThreadId,
                                    onClick = { onOpenThread(row.threadId) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyInbox(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(SproutSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = SproutIcons.ChatBubble,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(PlaceholderIconSize),
        )
        Text(
            text = stringResource(R.string.feature_chat_threads_empty),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val PlaceholderIconSize = 48.dp
private val CountCircleSize = 32.dp
private val SelectedOutline = 2.dp

/**
 * The detail pane before a thread is picked (list-detail on an Expanded window): the app passes it as the list
 * pane's `detailPlaceholder`.
 */
@Composable
fun ChatThreadPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(SproutSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(PlaceholderIconSize + SproutSpacing.lg)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = SproutIcons.ChatBubble,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            text = stringResource(R.string.feature_chat_thread_placeholder),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ThreadRow(row: ThreadRowUi, tintIndex: Int, selected: Boolean, onClick: () -> Unit) {
    val supporting = when {
        row.unreadCount > 0 ->
            pluralStringResource(R.plurals.feature_chat_thread_unread, row.unreadCount, row.unreadCount)

        row.lastMessageAt != null -> stringResource(R.string.feature_chat_thread_last, timeText(row.lastMessageAt))

        else -> stringResource(R.string.feature_chat_thread_no_messages)
    }
    val outline = if (selected) {
        Modifier.border(SelectedOutline, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
    } else {
        Modifier
    }
    SproutCard(
        onClick = onClick,
        contentPadding = PaddingValues(SproutSpacing.md),
        // The thread open beside the list has a Botanical outline and is announced as selected.
        modifier = Modifier
            .fillMaxWidth()
            .then(outline)
            .then(if (selected) Modifier.semantics { this.selected = true } else Modifier),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md),
        ) {
            InitialAvatar(
                initial = row.kidName.take(1).uppercase(),
                tintIndex = tintIndex,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SproutSpacing.xs)) {
                Text(row.kidName, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.unreadCount > 0) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (row.unreadCount > 0) {
                // The count is in the line beside it; the circle repeats it for a glance and is not read out.
                Box(
                    modifier = Modifier
                        .size(CountCircleSize)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .clearAndSetSemantics { },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = row.unreadCount.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            } else {
                Icon(
                    imageVector = SproutIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

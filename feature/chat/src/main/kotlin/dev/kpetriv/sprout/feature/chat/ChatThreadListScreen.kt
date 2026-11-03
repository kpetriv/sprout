package dev.kpetriv.sprout.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.rememberHiltViewModelFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kpetriv.sprout.core.model.KidId

/**
 * Navigation entry for the parent's chat inbox. Behind the parent gate; the app wraps it in `SecureWindow`
 * ([ChatThreadListUiState.secure]) and opens [ParentChatRoute] from [onOpenThread].
 */
@Composable
fun ChatThreadListRoute(onOpenThread: (KidId) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: ChatThreadListViewModel = viewModel(factory = rememberHiltViewModelFactory())
    val state by viewModel.state.collectAsStateWithLifecycle()
    ChatThreadListScreen(state, viewModel::onEvent, onOpenThread, onBack, modifier)
}

/** One row per kid: initial, name, unread count and when the last message came. */
@Composable
fun ChatThreadListScreen(
    state: ChatThreadListUiState,
    onEvent: (ChatThreadListEvent) -> Unit,
    onOpenThread: (KidId) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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
                    Text(
                        text = stringResource(R.string.feature_chat_threads_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.threads, key = { it.threadId.value }) { row ->
                            ThreadRow(row, onClick = { onOpenThread(row.threadId) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

private val AvatarSize = 48.dp

@Composable
private fun ThreadRow(row: ThreadRowUi, onClick: () -> Unit) {
    val supporting = when {
        row.unreadCount > 0 ->
            pluralStringResource(R.plurals.feature_chat_thread_unread, row.unreadCount, row.unreadCount)

        row.lastMessageAt != null -> stringResource(R.string.feature_chat_thread_last, timeText(row.lastMessageAt))

        else -> stringResource(R.string.feature_chat_thread_no_messages)
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(AvatarSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = row.kidName.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
        headlineContent = { Text(row.kidName, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(supporting) },
        trailingContent = if (row.unreadCount > 0) {
            {
                Column(verticalArrangement = Arrangement.Center, modifier = Modifier.clearAndSetSemantics { }) {
                    Badge { Text(row.unreadCount.toString()) }
                }
            }
        } else {
            null
        },
    )
}

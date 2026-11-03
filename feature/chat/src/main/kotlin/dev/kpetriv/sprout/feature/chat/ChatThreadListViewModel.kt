package dev.kpetriv.sprout.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import kotlin.time.Instant

/** The parent's chat inbox: one thread per kid. */
sealed interface ChatThreadListUiState {
    /** See [ChatUiState.secure]. */
    val secure: Boolean get() = true

    data object Loading : ChatThreadListUiState

    data object Error : ChatThreadListUiState

    /** [threads] in the household's kid order; empty before the first kid is added. */
    data class Ready(val threads: List<ThreadRowUi>) : ChatThreadListUiState
}

/** One row of the inbox. */
data class ThreadRowUi(val threadId: KidId, val kidName: String, val unreadCount: Int, val lastMessageAt: Instant?) {
    override fun toString(): String = "ThreadRowUi(threadId=$threadId, unreadCount=$unreadCount)"
}

sealed interface ChatThreadListEvent {
    data object Reload : ChatThreadListEvent
}

/** The parent's thread list from [ChatThreads]. Opening a thread is navigation, handled by the app. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatThreadListViewModel @Inject constructor(private val threads: ChatThreads) : ViewModel() {
    private val refreshes = MutableStateFlow(0)

    val state: StateFlow<ChatThreadListUiState> = refreshes
        .flatMapLatest {
            threads.observeThreads()
                .map<List<ChatThreadSummary>, ChatThreadListUiState> { list ->
                    ChatThreadListUiState.Ready(
                        list.map { ThreadRowUi(it.threadId, it.kidName, it.unreadCount, it.lastMessageAt) },
                    )
                }
                .catch { emit(ChatThreadListUiState.Error) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ChatThreadListUiState.Loading)

    fun onEvent(event: ChatThreadListEvent) {
        when (event) {
            ChatThreadListEvent.Reload -> refreshes.update { it + 1 }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

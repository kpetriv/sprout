package dev.kpetriv.sprout.feature.chat

import dev.kpetriv.sprout.core.model.KidId
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ChatThreadListViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val threads = FakeChatThreads(
        listOf(ChatTestData.summary(unread = 2), ChatTestData.summary(KidId("kid-mia"), "Mia")),
    )

    @Test
    fun `one row per kid with the unread count`() = runTest {
        val state = subscribe(ChatThreadListViewModel(threads).state)

        val ready = assertIs<ChatThreadListUiState.Ready>(state.value)
        assertEquals(listOf("Leo" to 2, "Mia" to 0), ready.threads.map { it.kidName to it.unreadCount })
        assertEquals(true, ready.secure)
    }

    @Test
    fun `a failure is an error until reload`() = runTest {
        threads.failure = IllegalStateException("db closed")
        val vm = ChatThreadListViewModel(threads)
        val state = subscribe(vm.state)
        assertEquals(ChatThreadListUiState.Error, state.value)

        threads.failure = null
        vm.onEvent(ChatThreadListEvent.Reload)

        assertIs<ChatThreadListUiState.Ready>(state.value)
    }
}

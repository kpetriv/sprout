package dev.kpetriv.sprout.widget

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class GlanceWidgetRefresherTest {
    private val scope = TestScope()
    private var updates = 0
    private var failNext = false
    private val refresher = GlanceWidgetRefresher(
        scope.backgroundScope,
        update = {
            updates += 1
            if (failNext) {
                failNext = false
                error("no widget host")
            }
        },
        debounceMs = 500,
    )

    @Test
    fun `a burst of requests is one update after the quiet period`() = with(scope) {
        repeat(20) { refresher.refresh() }
        runCurrent()
        advanceTimeBy(400)
        assertEquals(0, updates)
        advanceTimeBy(200)
        assertEquals(1, updates)

        refresher.refresh()
        advanceTimeBy(600)
        assertEquals(2, updates)
    }

    @Test
    fun `a failed update is dropped and the next request still updates`() = with(scope) {
        failNext = true
        refresher.refresh()
        advanceTimeBy(600)
        refresher.refresh()
        advanceTimeBy(600)
        assertEquals(2, updates)
    }
}

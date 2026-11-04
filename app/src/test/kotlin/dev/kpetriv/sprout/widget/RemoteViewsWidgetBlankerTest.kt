package dev.kpetriv.sprout.widget

import android.app.Application
import android.content.Context
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class) // Not the Hilt app: its startup opens the encrypted database.
class RemoteViewsWidgetBlankerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pushed = mutableListOf<IntArray>()
    private var composed = 0

    private fun blanker(ids: IntArray) = RemoteViewsWidgetBlanker(
        placedIds = { ids },
        compose = {
            composed += 1
            RemoteViews(context.packageName, android.R.layout.simple_list_item_1)
        },
        push = { ids, _ -> pushed += ids },
    )

    @Test
    fun `every placed widget gets the blank drawing in one push`() = runTest {
        blanker(intArrayOf(7, 9)).blankNow()

        assertEquals(1, composed)
        assertContentEquals(intArrayOf(7, 9), pushed.single())
    }

    @Test
    fun `without a placed widget nothing is composed or pushed`() = runTest {
        blanker(IntArray(0)).blankNow()

        assertEquals(0, composed)
        assertTrue(pushed.isEmpty())
    }

    @Test
    fun `the blank drawing is the widget's Blank state and inflates as RemoteViews`() = runTest {
        val views = blankWidgetViews(context)

        assertEquals(context.packageName, views.`package`)
        // A launcher inflates it: RemoteViews accept only @RemoteView classes, so this fails on anything else.
        views.apply(context, FrameLayout(context))
    }
}

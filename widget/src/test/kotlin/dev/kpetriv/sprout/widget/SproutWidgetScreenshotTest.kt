package dev.kpetriv.sprout.widget

import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotTest
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Goldens of the widget (M7 §7): Glance composes [SproutWidgetContent] into the same [RemoteViews] a launcher would
 * get ([GlanceRemoteViews]), and a Compose host inflates them at the widget's size.
 */
class SproutWidgetScreenshotTest : ScreenshotTest() {
    private val kid = WidgetState.Kid(
        name = "Mia",
        balance = "£12.50",
        minutes = "1 h 20 min",
        dueToday = 3,
        toDo = 2,
        firstToDo = listOf("Tidy room", "Feed the cat"),
    )

    @Test
    fun `kid 2x2`() = capture("widget_kid_small", kid, SproutWidget.SMALL.withHeight(140))

    @Test
    fun `kid 4x2`() = capture("widget_kid_wide", kid, SproutWidget.WIDE.withHeight(140))

    @Test
    fun `open sprout 2x2`() = capture("widget_open_app", WidgetState.OpenApp, SproutWidget.SMALL.withHeight(140))

    /**
     * The widget picker's preview (`previewLayout`): a launcher inflates it as [RemoteViews], which refuse any view
     * class without `@RemoteView` (a plain `View` spacer made the picker say "Can't load widget" on the walk).
     */
    @Test
    fun `picker preview`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val remoteViews = RemoteViews(context.packageName, R.layout.sprout_widget_preview)
        screenshot.capture("widget_picker_preview") {
            Box(Modifier.padding(8.dp)) {
                AndroidView(
                    factory = { remoteViews.apply(it, FrameLayout(it)) },
                    modifier = Modifier.size(SproutWidget.SMALL.width, 140.dp),
                )
            }
        }
    }

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    private fun capture(name: String, state: WidgetState, size: DpSize) {
        val remoteViews = runBlocking {
            GlanceRemoteViews().compose(ApplicationProvider.getApplicationContext(), size) {
                SproutWidgetContent(state, open = null)
            }.remoteViews
        }
        screenshot.capture(name) {
            Box(Modifier.padding(8.dp)) {
                AndroidView(
                    factory = { context -> remoteViews.apply(context, FrameLayout(context)) },
                    modifier = Modifier.size(size.width, size.height),
                )
            }
        }
    }

    private fun DpSize.withHeight(height: Int) = DpSize(width, height.dp)
}
